//! Integration coverage for ando (notes/ando.md): the production
//! broker that runs Android commands for rootfs guests.
//!
//! Every spawn here goes through the debug exec broker's RUNINSIDE
//! form, so each test inherently runs its ando child concurrently with
//! an ART `ProcessBuilder` child (the guest session itself) — standing
//! regression cover for "nothing else in the app process steals the
//! ando broker's waitpid status".

use tawc_integration::adb;

/// Run `cmd` inside the rootfs; return (exit code, stdout, stderr)
/// with stdio trimmed.
fn run(cmd: &str) -> (i32, String, String) {
    let out = adb::rootfs_run(cmd).unwrap_or_else(|e| panic!("rootfs_run {cmd:?}: {e}"));
    (
        out.status.code().unwrap_or(-1),
        String::from_utf8_lossy(&out.stdout).trim().to_string(),
        String::from_utf8_lossy(&out.stderr).trim().to_string(),
    )
}

/// Count Android-side processes whose command line matches `pattern`
/// (a grep regex; use a [b]racketed first char so the grep itself
/// doesn't match).
fn android_proc_count(pattern: &str) -> usize {
    let out = adb::shell(&format!("ps -A | grep '{pattern}' | wc -l")).expect("adb shell ps");
    String::from_utf8_lossy(&out.stdout).trim().parse().expect("ps count")
}

#[test]
fn test_ando_runs_android_command_on_guest_stdout() {
    let (rc, out, err) = run("ando /system/bin/getprop ro.product.cpu.abi");
    assert_eq!(rc, 0, "stderr: {err}");
    assert!(
        out == "x86_64" || out.starts_with("arm"),
        "unexpected abi from getprop: {out:?}"
    );
}

#[test]
fn test_ando_path_search_and_exit_code() {
    // `sh` is bare (PATH search hits /system/bin) and the exit code
    // must come back verbatim through broker + client.
    let (rc, _, err) = run("ando sh -c 'exit 7'");
    assert_eq!(rc, 7, "stderr: {err}");
}

#[test]
fn test_ando_command_not_found() {
    let (rc, _, err) = run("ando no-such-command-xyz");
    assert_eq!(rc, 127);
    assert!(err.contains("not found"), "stderr: {err:?}");
}

#[test]
fn test_ando_env_hygiene_and_extras() {
    // Nothing from the guest env (libhybris LD_* baggage) may leak…
    let (rc, out, _) = run(r#"ando sh -c 'echo "[${LD_PRELOAD}${LD_LIBRARY_PATH}]"'"#);
    assert_eq!(rc, 0);
    assert_eq!(out, "[]", "guest env leaked into the Android child");
    // …while -e extras must arrive.
    let (rc, out, _) = run("ando -e TAWC_TEST_VAR=hello sh -c 'echo $TAWC_TEST_VAR'");
    assert_eq!(rc, 0);
    assert_eq!(out, "hello");
}

#[test]
fn test_ando_identity_is_app_uid_not_fake_root() {
    let (rc, out, _) = run("echo guest=$(id -u) android=$(ando id -u)");
    assert_eq!(rc, 0);
    let android_uid: u32 = out
        .strip_prefix("guest=0 android=")
        .unwrap_or_else(|| panic!("unexpected identity output: {out:?}"))
        .parse()
        .expect("android uid");
    assert!(android_uid >= 10000, "expected app uid, got {android_uid}");
}

#[test]
fn test_ando_signal_forwarding() {
    // TERM the in-rootfs client: its handler forwards SIG 15, the
    // broker kills the Android-side process group, sleep dies by
    // signal, and the client exits with the broker's 128+15.
    let (rc, out, _) = run("ando sleep 987 & p=$!; sleep 1; kill -TERM $p; wait $p; echo rc=$?");
    assert_eq!(rc, 0);
    assert_eq!(out, "rc=143");
    assert_eq!(android_proc_count("[s]leep 987"), 0, "Android-side sleep survived");
}

#[test]
fn test_ando_client_death_reaps_android_child() {
    // SIGKILL the client (no chance to forward): the broker sees
    // socket EOF before child exit and SIGKILLs the child's group.
    let (rc, out, _) = run("ando sleep 988 & p=$!; sleep 1; kill -KILL $p; wait $p; echo rc=$?");
    assert_eq!(rc, 0);
    assert_eq!(out, "rc=137");
    std::thread::sleep(std::time::Duration::from_secs(1));
    assert_eq!(android_proc_count("[s]leep 988"), 0, "orphaned Android-side sleep");
}

#[test]
fn test_ando_cwd_travels_as_fd() {
    // The child starts in the host directory the guest cwd resolves
    // to — pwd reports the untranslated host path.
    let (rc, out, _) = run("cd /root && ando sh -c pwd");
    assert_eq!(rc, 0);
    assert!(
        out.ends_with("/rootfs/root"),
        "expected host path of guest /root, got {out:?}"
    );
    // Files created there are visible in the guest cwd.
    let (rc, out, _) = run(
        "cd /root && rm -f ando-cwd-x && ando sh -c 'touch ando-cwd-x' && ls ando-cwd-x && rm ando-cwd-x",
    );
    assert_eq!(rc, 0);
    assert_eq!(out, "ando-cwd-x");
    // A bind-mounted cwd resolves to the bind source.
    let (rc, out, _) = run("cd /usr/share/tawc && ando sh -c pwd");
    assert_eq!(rc, 0);
    assert!(
        out.ends_with("/me.phie.tawc/share"),
        "expected bind source of /usr/share/tawc, got {out:?}"
    );
}

#[test]
fn test_ando_broker_absent_and_socket_override() {
    // TAWC_ANDO_SOCKET overrides the socket path (test hook): a bogus
    // path gives one clear error line and exit 127.
    let (rc, _, err) = run("TAWC_ANDO_SOCKET=/usr/share/tawc/no-such-ando.sock ando true");
    assert_eq!(rc, 127);
    assert!(err.contains("broker not running"), "stderr: {err:?}");
    // Empty override falls through to the built-in share-dir path.
    let (rc, out, _) = run("TAWC_ANDO_SOCKET= ando echo default-path-ok");
    assert_eq!(rc, 0);
    assert_eq!(out, "default-path-ok");
}
