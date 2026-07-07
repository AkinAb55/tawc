# Magisk-style `su -c` fails on emulator's AOSP su

`uninstall_wipe::test_wipe_gate_and_su_retry` panics on the emulator
(2026-07-06, full-suite run):

    this test needs a rooted target (su unavailable):
    su: invalid uid/gid '-c'

The test helpers use Magisk-style `su -c '...'` / `su -mm -c '...'`,
but the AVD ships AOSP `/system/xbin/su` with `su [WHO [COMMAND...]]`
syntax, which rejects `-c`. So `require_root()` fails even though the
shell is root-capable (`su 0 sh -c ...` works fine).

Unrelated to rendering work; observed while verifying the SHM
black-render fix. Options: teach the helpers both su flavors, or have
`require_root` detect non-Magisk su and mark the test skipped instead
of failed (the suite already has per-target ignore machinery).

Same bug in `scripts/emulator.sh`: its `su -c "setenforce 0"` fails on
AOSP su, so even where it runs, SELinux stays enforcing. Also, the
script skips `setenforce` entirely on the rootless AVD, but the
google_apis x86_64 image is userdebug with `/system/xbin/su`, so
`su 0 setenforce 0` does work there (verified 2026-07-06 on
emulator-5554). Relevant to the SHM-black diagnosis in
`emulator-shm-black-shader-translator.md`.
