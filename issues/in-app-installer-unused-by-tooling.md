- The in-app installer puts its rootfs at
  `/data/data/me.phie.tawc/installations/<id>/rootfs/`, but every
  existing tooling path still targets `/data/local/arch-chroot/` via the
  legacy `client/arch-chroot-*` scripts:
  - `testing/integration/src/adb.rs` (`chroot_run`, `chroot_spawn`)
  - `testing/run-integration-tests.sh`, `install-test-deps.sh`,
    `build-debug-app.sh`
  - `client/build-libhybris` (and `build-libhybris-chroot.sh`)
  - All quick-reference commands in `CLAUDE.md` and the notes
- We deliberately removed the broadcast `RUN` endpoint (it was an
  unauthenticated root-as-a-service hole; see git log for
  `InstallationCommandReceiver` deletion). So the bridge from host
  tooling into the in-app rootfs no longer exists; whatever replaces
  it needs auth designed in from the start.
- Until then, the legacy scripts can't be deleted even though the
  in-app path is feature-complete.
- Open questions for whoever picks this up:
  - Can host tooling skip the in-app rootfs entirely and just `chroot`
    into `/data/data/me.phie.tawc/installations/<id>/rootfs/` over
    `adb shell su` (the same pattern the legacy scripts use)? That
    avoids needing an in-app endpoint at all — the in-app installer
    becomes "creates the rootfs", and tooling drives it externally.
  - Should `build-libhybris` target the in-app rootfs or stay
    independent (it has its own build cache + stamp)?
  - If we *do* end up wanting an in-app endpoint, gate it on
    `Process.SHELL_UID` / `Process.ROOT_UID` / our own UID, OR on a
    signature-level permission. Don't ship an unauthenticated one
    again.
