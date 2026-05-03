pub mod adb;
pub mod chroot;
pub mod chroot_process;
pub mod compositor;
pub mod debug_app;
pub mod helpers;

/// On-device scratch dir for everything that doesn't live in the app's
/// private data dir. Test/debug only — production never touches this.
/// See `client/tawc-scratch.sh` for the rationale.
pub const TAWC_SCRATCH: &str = "/data/local/tmp/tawc-dev";

/// Install id for the in-app distro this test run targets. Reads
/// `TAWC_INSTALL_ID` (matches `client/tawc-chroot-run`) and defaults to
/// `arch` for backwards compatibility with the chroot/proot suite.
/// Used to parameterize all the hardcoded `/data/data/.../distros/<id>/`
/// paths so a single APK can host multiple installs (e.g. `arch` chroot
/// alongside `arch-tawcroot`) and the suite can target either.
pub fn install_id() -> String {
    std::env::var("TAWC_INSTALL_ID").unwrap_or_else(|_| "arch".to_string())
}
