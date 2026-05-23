# Integration Test Host Transport Plan

## Goal

Make the Rust integration tests stop spawning host-side command helpers for
normal per-test work.

Allowed exceptions:

- `adb exec-out screencap` for pixel tests.
- `tawcroot/test.sh --device --no-build` for the wrapped tawcroot suite.

Everything else should go through one suite-scoped broker path and app-side
debug actions. Logcat is diagnostic output only; tests should not clear,
poll, or parse it.

## Target Shape

`scripts/run-integration-tests.sh` owns device setup:

1. Select target and export `ANDROID_SERIAL`.
2. Build/install/deploy prerequisites.
3. Start the app/compositor.
4. Create one `adb forward tcp:<port> localabstract:me.phie.tawc.exec`.
5. Export `TAWC_EXEC_BROKER_PORT=<port>` into `cargo test`.
6. Remove the forward after the suite exits.

The Rust test binary then talks to `127.0.0.1:$TAWC_EXEC_BROKER_PORT`.
Each broker request may use a short local TCP connection. Do not add a
multiplexed long-lived protocol unless measurement shows local connect cost
matters.

`scripts/tawc-exec.sh` remains the human/script CLI, but the Rust tests do not
spawn it.

## Per-Test Reset

`test-init` should be the authoritative test isolation boundary.

It should:

- Enter in-memory test settings and push live runtime settings.
- Reset recording IME state and active input connection state.
- Force-disconnect all compositor Wayland clients. This must not be a polite
  `xdg_toplevel.close` / X11 close-window request.
- Stop Xwayland clients by disconnecting/stopping the relevant compositor-side
  Xwayland connection.
- Run an app-side rootfs cleanup sweep for the target install using
  `ProcessScanner.killAllInRootfs`.
- Return structured counts: disconnected clients, stopped rootfs processes,
  and any warnings.

The compositor should track connected Wayland client ids so the test hook can
disconnect them through Smithay/wayland-server, e.g. via
`DisplayHandle::backend_handle().kill_client(..., DisconnectReason::ConnectionClosed)`.

`RootfsProcess::stop()` can remain as a convenience for mid-test cleanup, but
test isolation should not depend on host-side pidfiles, PGID reads, `ps`, or
`kill -- -PGID`.

## Remove Logcat Dependencies

Replace every test oracle currently backed by logcat with broker-returned
state.

### Compositor State

Change `query-state` from "log `COMPOSITOR_STATE`" to "return state on stdout".

Keep the existing fields:

- `clients`
- `toplevels`
- `surfaces_wlegl`
- `surfaces_shm`
- `frames`
- `rendered_toplevels`
- `hosts`
- `bound_hosts`
- output dimensions / advertised state

The native compositor query path should round-trip through the compositor
event loop, so success also proves the loop is dispatching.

### Keyboard Readiness

Remove `onShowKeyboard` log polling.

In test mode, `RecordingImeOutput.showSoftInput()` binds the test
`TawcInputConnection`. Tests should wait for:

1. the client-side debug app `READY` line, if applicable;
2. the existing `input-ready` broker action.

### Buffer Path Assertions

Expose compositor counters instead of parsing import logs:

- `shm_imports_total`
- `wlegl_create_buffer_total`
- `wlegl_import_texture_total`
- `last_wlegl_width`
- `last_wlegl_height`
- `last_wlegl_format`
- optionally per-format wlegl counters if tests need more than `fmt=1`.

Use current-surface counts for "is anything currently SHM/AHB-backed" and
monotonic counters for animation/import-volume assertions.

### Xwayland State

Expose state instead of parsing "associated X11 surface" logs:

- `xwayland_running`
- `x11_surfaces`
- `x11_surfaces_with_host`
- optionally Xwayland pid list if a test still needs restart identity.

Existing socket-existence checks can remain broker/rootfs checks; they should
not use logcat.

### Clipboard Timeout

Expose a structured clipboard debug counter/state:

- `clipboard_pull_timeouts_total`
- optionally `last_clipboard_pull_error`

Tests that currently wait for a timeout log should wait for this counter to
advance while asserting the Android clipboard value is unchanged.

## Remaining Host Processes

After this plan:

- Normal broker actions: zero `adb`, zero `tawc-exec`.
- Rootfs command/spawn from tests: zero `adb`, zero `tawc-exec`.
- Test reset: zero `adb`, zero `tawc-exec`.
- Pixel tests: may still spawn `adb exec-out screencap`.
- Wrapped tawcroot suite: may still spawn `tawcroot/test.sh`, which may use
  `adb` internally.

Suite setup/teardown may still use `adb` from shell scripts.

## Implementation Order

1. Add suite-scoped broker forward and `TAWC_EXEC_BROKER_PORT`.
2. Teach `tests/integration/src/exec_broker.rs` to use the preopened port and
   skip per-request `adb forward`, `pidof`, and `am start` in test mode.
3. Convert `query-state` to return structured state.
4. Remove logcat polling for compositor state and keyboard readiness.
5. Add compositor debug counters for SHM/AHB, Xwayland, and clipboard timeout
   assertions.
6. Convert tests to use the new state fields, then delete test logcat helpers.
7. Change `test-init` to force-disconnect clients and run app-side rootfs
   cleanup.
8. Remove host-side pidfile/PGID process cleanup from `RootfsProcess`.

## Verification

- `cargo test --manifest-path tests/integration/Cargo.toml --no-run`
- `scripts/tawc-exec.sh /system/bin/true`
- `scripts/run-integration-tests.sh --no-build <small filter>` on the selected
  `.tawctarget`
- One SHM test, one AHB/wlegl test, one text-input test, one Xwayland test
- Full integration suite before deleting old fallback helpers
