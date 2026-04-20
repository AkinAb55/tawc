use std::io;
use std::path::Path;
use std::process::Command;

use crate::adb;

/// A debug-app variant we know how to build and deploy in the chroot.
/// `name` is both the source directory (under `testing/`) and the binary name.
pub struct DebugAppSpec {
    pub name: &'static str,
    pub pkgs: &'static [&'static str],
}

pub const GTK3: DebugAppSpec = DebugAppSpec {
    name: "gtk3-debug-app",
    pkgs: &["gtk3", "pkg-config"],
};

pub const GTK4: DebugAppSpec = DebugAppSpec {
    name: "gtk4-debug-app",
    pkgs: &["gtk4", "pkg-config"],
};

fn chroot_build_dir(spec: &DebugAppSpec) -> String {
    format!("/tmp/{}", spec.name)
}

fn chroot_fs_build_dir(spec: &DebugAppSpec) -> String {
    format!("/data/local/arch-chroot/tmp/{}", spec.name)
}

fn host_staging(spec: &DebugAppSpec) -> String {
    format!("/data/local/tmp/{}-src", spec.name)
}

fn binary_path(spec: &DebugAppSpec) -> String {
    format!("{}/{}", chroot_build_dir(spec), spec.name)
}

/// Check deps and build freshness in a single adb call.
/// Returns (deps_ok, stamp_value).
fn check_status(spec: &DebugAppSpec) -> io::Result<(bool, String)> {
    let build_dir = chroot_build_dir(spec);
    let stamp_chroot = format!("{}/build-stamp", build_dir);
    let binary_chroot = binary_path(spec);
    let pkgs = spec.pkgs.join(" ");
    // Use sentinel-framed output so unrelated warnings (e.g. from pacman) can't
    // be mistaken for the stamp value.
    let cmd = format!(
        "pacman -Q {} >/dev/null 2>&1 && echo DEPS_OK; \
         printf 'STAMP:%s\\n' \"$(test -f {} && cat {} 2>/dev/null || echo missing)\"",
        pkgs, binary_chroot, stamp_chroot
    );
    let output = adb::chroot_run(&cmd)?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let deps_ok = stdout.lines().any(|l| l.trim() == "DEPS_OK");
    let stamp = stdout
        .lines()
        .find_map(|l| l.trim().strip_prefix("STAMP:"))
        .unwrap_or("missing")
        .trim()
        .to_string();
    Ok((deps_ok, stamp))
}

/// Ensure the named packages are installed in the chroot.
fn ensure_build_deps(spec: &DebugAppSpec) -> io::Result<()> {
    eprintln!("Installing build deps in chroot: {}", spec.pkgs.join(" "));
    let output = adb::chroot_run(&format!(
        "pacman -Sy --noconfirm {}",
        spec.pkgs.join(" ")
    ))?;
    if !output.status.success() {
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!(
                "Failed to install build deps:\n{}",
                String::from_utf8_lossy(&output.stdout)
            ),
        ));
    }
    Ok(())
}

/// Ensure deps are installed and the debug app is built.
/// Returns the path to the binary inside the chroot.
/// Skips work that's already done.
pub fn ensure_debug_app(spec: &DebugAppSpec) -> io::Result<String> {
    let binary_chroot = binary_path(spec);
    let source_dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join(spec.name);

    let (deps_ok, stamp) = check_status(spec)?;

    if !deps_ok {
        ensure_build_deps(spec)?;
    }

    // Check if build is fresh
    let source_mtime = latest_mtime(&source_dir)?;
    let stamp_mtime: u64 = stamp.parse().unwrap_or(0);
    if stamp != "missing" && stamp_mtime == source_mtime {
        return Ok(binary_chroot);
    }

    let staging = host_staging(spec);
    let fs_build_dir = chroot_fs_build_dir(spec);

    // Push source files to staging area
    adb::shell(&format!("rm -rf {}", staging))?;
    Command::new("adb")
        .args(["push", &source_dir.to_string_lossy(), &staging])
        .output()?;

    // Copy into chroot filesystem
    adb::shell(&format!(
        "su -c 'mkdir -p {} && cp {}/* {}'",
        fs_build_dir, staging, fs_build_dir
    ))?;

    // Build
    let output = adb::chroot_run(&format!(
        "/bin/bash {}/build.sh",
        chroot_build_dir(spec)
    ))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!("Build failed:\nstdout: {}\nstderr: {}", stdout, stderr),
        ));
    }

    // Write stamp with latest source mtime so we can skip next time
    adb::chroot_run(&format!(
        "echo {} > {}/build-stamp",
        source_mtime,
        chroot_build_dir(spec)
    ))?;

    Ok(binary_chroot)
}

/// Get the latest modification time (as unix seconds) of any file in the directory.
fn latest_mtime(dir: &Path) -> io::Result<u64> {
    let mut latest = 0u64;
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        if let Ok(meta) = entry.metadata() {
            if let Ok(mtime) = meta.modified() {
                let secs = mtime
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs();
                latest = latest.max(secs);
            }
        }
    }
    Ok(latest)
}
