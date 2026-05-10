# Consolidate test input/state broadcasts into ExecBroker actions

Migrate the 11 `me.phie.tawc.*` debug broadcast actions into ExecBroker
`ACTION` handlers. Removes the `RECEIVER_EXPORTED` + `DUMP`-permission
dance, gives every host→app channel a single auth model and DEBUG
gate, and drops one whole namespace from the cross-version external
API surface.

Bundle with `input-tests-skip-ic-and-race-system-ime.md`: the test
conversions there ride on these actions, and the `ImeOutput` interface
swap is toggled via two broker actions (`enable-test-input` /
`disable-test-input`).

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
`Handler(Looper.getMainLooper())`, and calls the appropriate method.

### Bypass actions (call NativeBridge directly, skip IC)

Used by `mod wayland_only` tests for pure protocol-ordering coverage.

| Action            | Args                              | Calls                                          |
|-------------------|-----------------------------------|------------------------------------------------|
| `inject-text`     | `text`, `deleteBefore?`, `deleteAfter?` | `NativeBridge.nativeCommitText` (or `nativeSendKeyEvent` for `\n`) |
| `set-composing`   | `text`, `deleteBefore?`, `deleteAfter?` | `NativeBridge.nativeSetComposingText`        |
| `finish-composing`| —                                 | `NativeBridge.nativeFinishComposingText`       |
| `delete-surrounding` | `before`, `after`              | `nativeSendKeyEvent(KEYCODE_DEL/FORWARD_DEL)` xN |
| `key-event`       | `keycode`                         | `NativeBridge.nativeSendKeyEvent`              |

### IC actions (call real InputConnection methods)

Used by `mod ic` tests — exercises the full IC logic
(`computeReplaceDeltas`, Editable mirror, flag tracking, etc.).

| Action            | Args                              | Calls                                          |
|-------------------|-----------------------------------|------------------------------------------------|
| `ic-commit-text`  | `text`                            | `activeInputConnection.commitText`             |
| `ic-set-composing-text` | `text`                      | `activeInputConnection.setComposingText`       |
| `ic-set-composing-region` | `start`, `end`            | `activeInputConnection.setComposingRegion`     |
| `ic-finish-composing` | —                             | `activeInputConnection.finishComposingText`    |
| `ic-set-selection` | `start`, `end`                   | `activeInputConnection.setSelection`           |

### Other actions

| Action            | Args                              | Calls                                          |
|-------------------|-----------------------------------|------------------------------------------------|
| `query-state`     | —                                 | `NativeBridge.nativeQueryState` (on the service, not an activity) |
| `enable-test-input` | —                               | swap the active IC's `ImeOutput` to the test recorder (see `input-tests-skip-ic-and-race-system-ime.md`) |
| `disable-test-input`| —                               | restore production `ImeOutput` (real IMM) |

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
- Broker actions can return structured results (the `ImeOutput`
  test recorder's contents can be returned on the broker response
  if needed, though the primary assertion path is observing
  `ImeOutput` calls as they accumulate).
- One fewer `me.phie.tawc.*` broadcast namespace to track in the
  cross-version API audit.

## Decisions

- **Activity resolution.** Activity-bound actions resolve via a new
  `CompositorService.focusedActivity()` that walks the existing
  `activities: Map<String, WeakReference<CompositorActivity>>` and
  returns the first whose `hasWindowFocus()` is true (reached via
  the existing `NativeBridge.serviceRef`). No focused activity →
  action returns exit 1 with `err("no focused CompositorActivity")`.
  Loud beats silent — silent-skip masks setup mistakes that the
  current broadcast model also masks today, and setting up a
  reliable focused activity is the test's responsibility.
- **`query-state` lives on the Service.** Calls
  `NativeBridge.nativeQueryState()` directly off the broker thread
  (no main-loop post needed — verify before implementing).
- **Result delivery.** Single response per action, no progress
  stream. The broker's existing one-shot ACTION path covers this
  cleanly — see `InstallActions` for the long-running counterpart
  if we ever need it here.

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
- `app/src/main/java/me/phie/tawc/compositor/TawcInputConnection.kt`
  — accept `ImeOutput` interface instead of calling IMM directly.
- `app/src/main/java/me/phie/tawc/compositor/NativeBridge.kt`
  — reverse-JNI keyboard show/hide/restart calls go through
  `ImeOutput` instead of IMM directly.
- `app/src/main/java/me/phie/tawc/compositor/ImeOutput.kt` (new)
  — interface + production `RealImeOutput` + test `RecordingImeOutput`.
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
