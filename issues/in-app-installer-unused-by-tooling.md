- The in-app installer puts its rootfs at
  `/data/data/me.phie.tawc/installations/<id>/rootfs/`, but every
  existing tooling path still targets `/data/local/arch-chroot/` via the
  legacy `client/arch-chroot-*` scripts:
  - `testing/integration/src/adb.rs` (`chroot_run`, `chroot_spawn`)
  - `testing/run-integration-tests.sh`, `install-test-deps.sh`,
    `build-debug-app.sh`
  - `client/build-libhybris` (and `build-libhybris-chroot.sh`)
  - All quick-reference commands in `CLAUDE.md` and the notes
- Until tooling moves over (probably via the broadcast `RUN` command
  from `InstallationCommandReceiver`), we can't delete the legacy
  scripts even though the in-app path is feature-complete.
- Steps:
  - Decide whether `build-libhybris` should target the in-app rootfs
    or stay independent (it has its own build cache + stamp).
  - Add a small wrapper that resolves "the canonical chroot path for
    this device" so adb.rs and the bash helpers don't hard-code it.
  - Port `chroot_run`/`chroot_spawn` to use the broadcast `RUN`
    receiver — note the current ANR-fixed `goAsync()` path supports
    long commands, but check that `am broadcast -W` propagates exit
    codes / large output well enough for tests (the result-data field
    has a size limit; may need logcat+sentinel for big output).
