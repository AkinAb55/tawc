# Xwayland never idle-terminates if its X client is killed mid-present

Xwayland is spawned with `-terminate 5`, so it should exit ~5s after its
last real X11 client disconnects. If the only X client (e.g. xclock) is
SIGKILLed in the narrow window right after its window maps but before its
first present settles, Xwayland instead lingers forever: alive (state S),
zero established connections on the X0 socket, but holding ~8 `/dmabuf`
(gralloc) fds and an unsignaled `anon_inode:sync_file` fence. The
`-terminate` timer never fires until either a new X client connects or
`set_xwayland(false)` runs the kill sweep.

Reproduced on the physical device (arch/tawcroot, cpu backend) by
`test_xwayland_setting_starts_and_stops_process_live` once the test's
waits moved from `adb shell pidof` polling (slow, ~1-2s of accidental
settle time) to broker `query-state` polling (fast): the test reached
`first.stop()` before xclock's first present settled and then timed out
waiting 20s for the Xwayland process to exit. Adding any settle (1.5s
sleep, or waiting for the first SHM surface) before the kill makes it
pass deterministically; the test now waits for the first rendered SHM
buffer, which also matches what it claims to verify.

Suspected mechanism: Xwayland's present path blocks on a buffer
release/fence from the compositor that never arrives once the dead
client's window/host is torn down, so its main loop never processes the
client EOF and never arms the terminate timer. Not confirmed at the
Xwayland source level.

Impact outside tests: a production user killing an X app at exactly the
wrong moment leaves a do-nothing Xwayland process around (it still works
— next client connects fine and restart paths recover). Low severity.

Diagnostics that found it: `ps` watch + `/proc/net/unix` (no established
X0 connections) + `/proc/<pid>/fd` via the broker (dmabuf + sync_file
fds). See `wait_for_first_xclock_render` in `tests/integration/tests/xwayland.rs`.
