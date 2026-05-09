# Consolidate test input/state broadcasts into ExecBroker actions

Migrate the 11 `me.phie.tawc.*` debug broadcast actions into ExecBroker
`ACTION` handlers. Removes the `RECEIVER_EXPORTED` + `DUMP`-permission
dance, gives every host→app channel a single auth model and DEBUG
gate, and drops one whole namespace from the cross-version external
API surface.

## What's there today

**Two parallel host→app channels, both debug-only:**

1. **ExecBroker** — `LocalServerSocket`, `SO_PEERCRED`-gated to
   shell/root UID, request types `ARGV` / `ACTION` / `RUNINSIDE`.
   Used by install/uninstall, `rootfs-run`, `install-test-deps`,
   integration tests' chroot ops. Started in
   `TawcApplication.kt:46` behind `BuildConfig.DEBUG`.
2. **Test broadcasts** — `RECEIVER_EXPORTED` + `android.permission.DUMP`,
   registered behind `BuildConfig.DEBUG` (after the recent gating).
   - `CompositorActivity.kt:233-258` — 10 input actions:
     `TEXT_INPUT`, `SET_COMPOSING_TEXT`, `FINISH_COMPOSING_TEXT`,
     `DELETE_SURROUNDING_TEXT`, `KEY_EVENT`, `IC_COMMIT_TEXT`,
     `IC_SET_COMPOSING_TEXT`, `IC_SET_COMPOSING_REGION`,
     `IC_FINISH_COMPOSING`, `IC_SET_SELECTION`.
   - `CompositorService.kt:122-132` — `QUERY_STATE` (one action).

Both are reachable from `adb shell` and gated to release builds. The
duplication exists because broadcasts naturally hit the foreground
Activity (where `hasWindowFocus()` and the active `InputConnection`
live), and the broker runs in `TawcApplication`'s context with no
clean route to "the activity that currently has focus."

## Proposed shape

Add new broker actions under `me.phie.tawc.dev.input` (or similar).
Each action handler resolves the focused `CompositorActivity` via
the existing `CompositorService.activities` map (already used for
reverse-JNI `spawnActivity`/`finishActivity`), posts to
`Handler(Looper.getMainLooper())`, and runs the same code the
broadcast receiver runs today:

| Action            | Args                              | Calls                                          |
|-------------------|-----------------------------------|------------------------------------------------|
| `inject-text`     | `text`, `deleteBefore?`, `deleteAfter?` | `NativeBridge.nativeCommitText` (or `nativeSendKeyEvent` for `\n`) |
| `set-composing`   | `text`, `deleteBefore?`, `deleteAfter?` | `NativeBridge.nativeSetComposingText`        |
| `finish-composing`| —                                 | `NativeBridge.nativeFinishComposingText`       |
| `delete-surrounding` | `before`, `after`              | `nativeSendKeyEvent(KEYCODE_DEL/FORWARD_DEL)` xN |
| `key-event`       | `keycode`                         | `NativeBridge.nativeSendKeyEvent`              |
| `ic-commit-text`  | `text`                            | `activeInputConnection.commitText`             |
| `ic-set-composing-text` | `text`                      | `activeInputConnection.setComposingText`       |
| `ic-set-composing-region` | `start`, `end`            | `activeInputConnection.setComposingRegion`     |
| `ic-finish-composing` | —                             | `activeInputConnection.finishComposingText`    |
| `ic-set-selection` | `start`, `end`                   | `activeInputConnection.setSelection`           |
| `query-state`     | —                                 | `NativeBridge.nativeQueryState` (on the service, not an activity) |

Drop both `registerReceiver` blocks and the `testInputReceiver` /
`queryStateReceiver` field declarations entirely. Drop the
`UnspecifiedRegisterReceiverFlag` suppressions.

Host side: extend `tools/tawc-exec/` (or the existing helper) with
`tawc-exec --action <name> [--arg k=v ...]`. The broker action
protocol already supports this shape — `InstallActions` registers
handlers the same way.

## Why it's better

- One auth model, one wire protocol, one DEBUG gate. The audit's
  "external API surface" drops to LAUNCHER + `tawc://activity/<id>`
  deeplink + LogScreenActivity.
- Eliminates `RECEIVER_EXPORTED` from the codebase entirely (was
  necessary for adb-broadcast access; no longer needed).
- `am broadcast`'s JVM cold start (100–300ms per call on real
  devices) is replaced by a native `tawc-exec` connect to an
  already-running socket (<10ms). Tests that fire many events in
  sequence get measurably faster.
- The IC_* actions today have a structural smell — they call
  `InputConnection` methods directly and the broadcast model is
  fire-and-forget, so any return value is lost. Broker actions can
  return structured results, opening the door to richer assertions.
- One fewer `me.phie.tawc.*` broadcast namespace to track in the
  cross-version API audit.

## Open questions

- **Activity resolution.** `CompositorService.activities` is a
  `Map<String, WeakReference<CompositorActivity>>`. Picking "the
  focused one" reproduces today's `hasWindowFocus()` filter; if no
  Activity is focused (e.g. the device is on the lock screen or
  another app is front), action returns an error rather than
  silently no-op'ing. Decide whether errors are preferred or whether
  we keep the silent-skip behaviour.
- **`query-state` lives on the Service**, not an Activity, so its
  handler doesn't need the focus dance — straight call to
  `NativeBridge.nativeQueryState()` from the broker thread (or
  posted to main if NativeBridge requires it; check before
  implementing).
- **Result delivery.** Broker `ACTION` handlers today (install,
  uninstall) drive long-running operations with progress streams.
  These input actions are one-shot; they should return a small
  status (success/error message). Confirm the action protocol
  cleanly handles "single response, no progress."

## Migration

- **Test code:** rewrite `tests/integration/src/adb.rs:76-212`
  (12 helpers) to issue broker actions instead of `am broadcast`.
  All callers go through these helpers, so individual test files
  don't change.
- **Manual debugging:** update `CLAUDE.md` Quick Reference's
  "Inject text" / "Inject keyevent" lines to use `tawc-exec`.
  Update `notes/text-input.md`, `notes/multi-activity.md`,
  `notes/emulator.md`, `notes/testing.md` references.
- **`tests/apps/gtk4-debug-app/gtk4-debug-app.c`** if it issues any
  broadcasts (verify — most likely just receives Wayland events).
- **`scripts/emulator.sh`** if it broadcasts anything during setup
  (verify).
- No on-device migration needed — these channels are debug-only and
  process-scoped; no persisted state.

## Files affected

- `app/src/main/java/me/phie/tawc/compositor/CompositorActivity.kt`
  — drop `testInputReceiver` field + register/unregister.
- `app/src/main/java/me/phie/tawc/compositor/CompositorService.kt`
  — drop `queryStateReceiver` field + register/unregister.
- `app/src/main/java/me/phie/tawc/dev/` — new `InputActions.kt` (or
  similar) registering the action handlers from
  `TawcApplication.onCreate` next to `InstallActions.registerAll()`.
- `tools/tawc-exec/` — extend host helper if it doesn't already
  cover arbitrary actions.
- `tests/integration/src/adb.rs` — replace 12 helper bodies.
- `notes/exec-broker.md` — document the new action namespace.
- `CLAUDE.md`, `notes/text-input.md`, `notes/multi-activity.md`,
  `notes/emulator.md`, `notes/testing.md` — update reference
  commands.
