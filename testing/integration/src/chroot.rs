use std::io;
use std::path::Path;
use std::process::Command;

use crate::adb;

const CHROOT_BUILD_DIR: &str = "/tmp/gtk3-debug-app";
const HOST_STAGING: &str = "/data/local/tmp/gtk3-debug-app-src";
const CHROOT_FS_BUILD_DIR: &str = "/data/local/arch-chroot/tmp/gtk3-debug-app";

/// Ensure GTK3 and build tools are installed in the chroot.
pub fn ensure_build_deps() -> io::Result<()> {
    let output = adb::chroot_run("pacman -Q gtk3 pkg-config >/dev/null 2>&1 && echo OK")?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    if stdout.contains("OK") {
        return Ok(());
    }
    eprintln!("Installing build deps in chroot...");
    let output = adb::chroot_run("pacman -Sy --noconfirm gtk3 pkg-config")?;
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

/// Push debug app source to the phone and build inside the chroot.
/// Returns the path to the binary inside the chroot.
pub fn build_debug_app() -> io::Result<String> {
    let source_dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .join("gtk3-debug-app");

    // Push source files to staging area
    adb::shell(&format!("rm -rf {}", HOST_STAGING))?;
    Command::new("adb")
        .args(["push", &source_dir.to_string_lossy(), HOST_STAGING])
        .output()?;

    // Copy into chroot filesystem
    adb::shell(&format!(
        "su -c 'mkdir -p {} && cp {}/* {}'",
        CHROOT_FS_BUILD_DIR, HOST_STAGING, CHROOT_FS_BUILD_DIR
    ))?;

    // Build
    let output = adb::chroot_run(&format!("/bin/bash {}/build.sh", CHROOT_BUILD_DIR))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!("Build failed:\nstdout: {}\nstderr: {}", stdout, stderr),
        ));
    }

    Ok(format!("{}/gtk3-debug-app", CHROOT_BUILD_DIR))
}
