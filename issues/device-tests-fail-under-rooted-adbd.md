# tawcroot --device tests fail when adbd runs as root

`tawcroot/test.sh --device` on the emulator fails 16 tests (12 steps +
4 `testhost_exit_status` aggregates), all of the shape
`dropped fch{mod,own}at(/dev/null) -> real EPERM/EACCES ... rv = 0`.

Cause: the emulator's adbd is in root mode (`adb shell id` → uid 0,
context `u:r:su:s0`), so the cleat orchestrator and testhost run as
root. The dropped-identity tests expect the *host* chmod/chown of
root-owned `/dev/null` to fail once the virtual identity is dropped —
as real root it succeeds.

Verified pre-existing at commit 7430892 (before the link-emulation
work): `git stash && test.sh --device 'dropped fch.*'` → same 12
failures.

Options: run those steps only when `getuid() != 0`, or have test.sh
warn when adbd is rooted. Physical-device runs (non-root adbd) are
unaffected.
