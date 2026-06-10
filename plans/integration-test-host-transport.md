# Integration Test Host Transport — Remaining Work

Most of the original plan is done: the suite-scoped broker forward
(`TAWC_EXEC_BROKER_PORT`), structured `query-state`, the debug counters
(SHM/wlegl/Xwayland/clipboard), logcat-free tests, force-disconnecting
`test-init` with app-side rootfs cleanup, and the removal of host-side
pidfile/PGID cleanup from `RootfsProcess` are all landed. What remains is
killing the last per-request host process spawns.

Allowed exceptions (unchanged):

- `adb exec-out screencap` for pixel tests.
- `tawcroot/test.sh --device --no-build` for the wrapped tawcroot suite.
- Suite setup/teardown `adb` in shell scripts.

## 1. Skip per-request `pidof` / `am start` in suite mode

`exec_broker::connect()` calls `ensure_broker_ready()` *before* checking
`TAWC_EXEC_BROKER_PORT`, so every broker request still spawns
`adb shell pidof me.phie.tawc`, and every `RunInside` request also spawns
`adb shell am start` to foreground the app.

When `TAWC_EXEC_BROKER_PORT` is set:

- Skip `app_running()` entirely. `scripts/run-integration-tests.sh` starts
  the app and no test force-stops it; a refused/reset connection should be a
  loud error pointing at the script, not a recovery path.
- Decide what to do about the implicit `RunInside` foregrounding. It exists
  because Android blocks background activity starts, and GUI client spawns
  need the app foregrounded to launch a `CompositorActivity`. Verify whether
  the suite still passes without it (the script foregrounds once; Back-key
  tests may background the app mid-suite). If some tests do need it, make
  foregrounding an explicit helper those tests call, not an implicit `adb`
  spawn on every rootfs command.

The CLI path (no env var) keeps the current pidof/am-start behavior —
`scripts/tawc-exec.sh` must keep working against a cold app.

## 2. Expose Xwayland pids through `query-state`

`tests/xwayland.rs` runs `adb shell pidof Xwayland` to detect launch, idle
stop, and restart identity. The compositor spawns Xwayland itself and already
enumerates its pids in `kill_xwayland_processes()`. Add an `xwayland_pids`
field to `query-state` and use it for the launch/stop/restart waits.

## 3. `compositor::is_running` without host `pidof`

`compositor::is_running()` spawns `adb shell pidof me.phie.tawc`. A
successful `query-state` round-trip already proves the app is up *and* the
compositor loop is dispatching — stronger than pidof. Replace the pidof check
with a broker call; keep the wayland-socket existence probe as the
`rootfs_host_exec` check it already is. Connection failure should produce the
same "run the suite via scripts/run-integration-tests.sh" panic.

## 4. Delete `adb shell input` helpers

- `adb::input_tap` is unused by tests (everything goes through the broker
  `inject-touch` action). Delete it.
- `adb::input_back` has one call site (`android_integration.rs`). Add a
  broker action that dispatches Back through the focused activity's
  back-press path on the main thread — same app-side boundary as the
  existing `hardware-key` / `inject-touch` actions — and use it. Note this
  trades the system input path for activity-level dispatch; what the test
  asserts (compositor Back handling) lives below that boundary either way.
- After 2–4, `adb::shell()` has no callers left; delete it. The only
  `Command::new("adb")` in the test crate outside `exec_broker.rs` should be
  `screencap_raw()`.

## Dropped from the original plan (deliberately)

- **Force-stopping the Xwayland connection in `test-init`.** Xwayland's
  Wayland client is spawned with unit client data, so it's intentionally
  outside the `client_ids` kill list. The rootfs kill sweep ends the X11
  client processes and their windows die with them; restarting Xwayland per
  test would add churn without isolation value.
- **`warnings` field in the `test-init` result.** `closed=` /
  `rootfs_killed=` have been sufficient.
- **Multiplexed long-lived broker protocol.** Still unjustified; per-request
  local TCP connects are cheap.

## Verification

- `cargo test --manifest-path tests/integration/Cargo.toml --no-run`
- `scripts/tawc-exec.sh /system/bin/true` against a stopped app (CLI cold
  start still works)
- `scripts/run-integration-tests.sh --no-build` with: one GUI rootfs test
  (exercises the `RunInside` foreground decision), the xwayland tests, and
  the `android_integration` Back test
- `grep -rn 'Command::new("adb")' tests/integration/src/` shows only
  `screencap_raw` and the `exec_broker.rs` CLI/fallback path
