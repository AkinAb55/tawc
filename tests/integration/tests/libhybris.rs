//! libhybris regression tests. Bug-for-bug repros of historical aborts
//! that don't fit cleanly into apps:: or graphics:: — those drive real
//! desktop/GPU stacks and only catch libhybris bugs incidentally; these
//! drive the suspect libhybris call directly.
//!
//! libhybris is aarch64-only in tawc, so these fail on the emulator.

use tawc_integration::{adb, rootfs};

/// Regression test for the libhybris TLS-module unregister assertion.
/// First seen on Pixel Fold while launching lxterminal — Mesa/GTK
/// pulled in a TLS-using vendor .so that subsequently got dlclose'd,
/// and unregister_tls_module aborted with:
///   linker_tls.cpp:93: unregister_tls_module CHECK 'mod.static_offset
///   == SIZE_MAX' failed
/// Root cause: libhybris never calls linker_finalize_static_tls() (it
/// has no executable bootstrap, so linker_main() runs nowhere — see
/// DISABLED_FOR_HYBRIS_SUPPORT in linker_main.cpp), so every TLS-using
/// dlopen'd .so was reserving a static-TLS slot that the unregister
/// CHECK then refused to free. Fixed by having register_soinfo_tls
/// always register dynamic (SIZE_MAX) and lazy-promoting in the IE TLS
/// relocation path (linker.cpp:R_GENERIC_TLS_TPREL) only when the
/// access model demands it.
///
/// Repro binary at tests/apps/libhybris-tls-repro/. The bionic-side
/// tls_lib.so (a tiny .so with `__thread int g_tls_var = 42;`) is
/// NDK-cross-built on the host by scripts/install-test-deps.sh; the
/// glibc-side `repro` executable is built inside the rootfs and links
/// `-lhybris-common`. Together they drive a full hybris_dlopen +
/// hybris_dlsym + hybris_dlclose round-trip.
///
/// Pre-fix: SIGABRT (exit 134) on hybris_dlclose.
/// Post-fix: clean exit 0.
#[test]
fn test_libhybris_tls_dlclose_does_not_abort() {
    let bin = rootfs::ensure_libhybris_tls_repro().expect("libhybris-tls-repro build");

    // Run from inside the install dir so the relative `./tls_lib.so`
    // arg keeps the test self-contained.
    let cmd = format!("cd /tmp/libhybris-tls-repro && {} ./tls_lib.so", bin);
    let output = adb::rootfs_run(&cmd).expect("run libhybris-tls-repro");
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    // Code 134 = SIGABRT, the expected mode of failure pre-fix. Surface
    // the literal CHECK string in the assertion message so future
    // regressions are obvious.
    assert!(
        output.status.success(),
        "libhybris-tls-repro exited non-zero ({:?}). \
         If this is `unregister_tls_module CHECK 'mod.static_offset == SIZE_MAX' failed`, \
         libhybris's IE-TLS lazy-promote fix has regressed (see \
         deps/libhybris/hybris/common/q/linker_tls.cpp + linker.cpp:R_GENERIC_TLS_TPREL).\n\
         stdout: {stdout}\nstderr: {stderr}",
        output.status.code()
    );

    // Belt-and-braces: the repro prints these on a successful round-
    // trip. Catches the (unlikely) case where it exits 0 having taken a
    // different failure path entirely (e.g. dlopen returning a stub).
    assert!(
        stderr.contains("hybris_dlclose -> 0"),
        "libhybris-tls-repro exited 0 but never printed \
         `hybris_dlclose -> 0` — did dlopen actually succeed?\n\
         stdout: {stdout}\nstderr: {stderr}"
    );
    assert!(
        stderr.contains("survived; no abort"),
        "libhybris-tls-repro exited 0 but never reached the post-dlclose \
         line — did execution end before unregister_tls_module ran?\n\
         stdout: {stdout}\nstderr: {stderr}"
    );
}
