# Input tests bypass `TawcInputConnection` and race the device IME

The integration tests in `tests/integration/tests/input.rs` purport to
cover input dispatch end-to-end, but in practice they sit at the
wayland-protocol layer and the entire Android-side adapter
(`TawcInputConnection`) is uncovered. The same architecture also makes
test outcomes depend on whichever system IME the device happens to have
installed, producing different failure modes on different hardware
(documented separately in `input-test-flaky-on-emulator.md`).

This issue subsumes that older one and proposes a plan that fixes both
problems together.

## Problem 1: tests skip the Android-side state machine

The "bypass" broadcasts the test suite uses
(`me.phie.tawc.TEXT_INPUT`, `SET_COMPOSING_TEXT`, `FINISH_COMPOSING_TEXT`,
`DELETE_SURROUNDING_TEXT`, `KEY_EVENT`) hit
`CompositorActivity.testInputReceiver`, which calls one of:

```kotlin
NativeBridge.nativeCommitText(text, before, after)         // 0,0 by default
NativeBridge.nativeSetComposingText(text, before, after)   // 0,0 by default
NativeBridge.nativeFinishComposingText()
NativeBridge.nativeSendKeyEvent(keycode)
```

Each of those native entries (in `compositor/src/lib.rs`) is a one-line
trampoline that puts a `TextInputEvent` on a channel; the text-input
event loop then calls `ti.commit_string()` / `ti.preedit_string()` /
`ti.delete_surrounding_text()` directly on the `ZwpTextInputV3`
wayland-server resource. End-to-end, a test broadcast becomes a
wayland-protocol event sent to the client.

`TawcInputConnection` is bypassed entirely. That means **none** of the
following is covered by the input test suite:

- `commitText`'s `composingRegionIsPreedit` shortcut for OpenBoard's
  per-Enter "re-commit composing region" pattern (notes/text-input.md
  "the bug: every Enter prepends `h` to the previous word").
- `computeReplaceDeltas`: turning Gboard's `commitText("the", 1)`
  issued while `Editable` has composing span `0..3` into the wire
  `(delete_before=3, delete_after=0)` paired with the commit string.
  This is the IC's main job. A regression here doesn't fail any input
  test.
- `setComposingText` / `setComposingRegion` Editable-mirror updates
  and the Android-composing-bytes vs wayland-cursor-relative-overlay
  bridge that the file's header comment goes to length to explain.
- `finishComposingText` flag-clear vs native-call semantics.
- `deleteSurroundingText` → key-event translation.
- `updateFromCompositor` (the inverse direction): Editable replace,
  `composingRegionIsPreedit = false`, `removeComposingSpans`,
  `lastSyncedCursor` tracking, `imm.updateSelection`.
- `sendKeyEvent` Android-keycode → evdev-keycode translation.

The reason the bypass tests appear to pass for things like the
autocorrect scene — `setComposing("teh") + commit("the ")` →
`"the "` — is that text-input-v3's done-ordering does the
preedit-replacement *implicitly* on the wayland side:
`preedit_string(None) + commit_string("the ") + done()` is enough to
make the GTK client replace "teh" with "the ". The test never has to
exercise IC's delta computation; the wayland protocol alone produces
the right observable on the GTK side.

Real Gboard input takes a completely different code path through IC
that has its own state machine, and our test suite happens to look
right because the wayland fallback hides the missing coverage.

## Problem 2: tests race whatever IME the device has installed

`updateFromCompositor` (`TawcInputConnection.kt:330`) calls
`imm.updateSelection(view, sel, sel, -1, -1)` whenever the wayland
client reports `set_surrounding_text` back to the compositor. IMM
forwards that to the currently-bound IME (Gboard on stock Android,
AOSP latin on the emulator, OpenBoard on someone's daily driver, …).
Most IMEs read `composingStart=-1, composingEnd=-1` as "the editor
cleared its composing region" and react by calling
`IC.finishComposingText` defensively, which calls
`nativeFinishComposingText`, which on the wayland side commits any
preedit currently set by the bypass path.

So a typical scenario from `scene_compose_lifecycle`:

1. `TEXT_INPUT "hello "` broadcast → bypass → wayland `commit_string`
2. GTK commits, sends `set_surrounding_text("hello ", 6, 6)` back
3. compositor → JNI → `mainHandler.post { ic.updateFromCompositor(...) }`
4. `SET_COMPOSING_TEXT "w"` broadcast → bypass → wayland
   `preedit_string("w")` — the **expected** end state is buffer="hello "
   with preedit="w"
5. (Async) Android main thread: `updateFromCompositor` runs →
   `imm.updateSelection(..., -1, -1)`
6. Gboard / AOSP IME / whatever reacts: `IC.finishComposingText`
7. `IC.finishComposingText` → `nativeFinishComposingText`
8. Compositor commits the just-set preedit "w" → buffer="hello w"

The order of steps 4 and 5–8 is unstable because step 5 was queued by
step 3 and the broadcast in step 4 races it. Whether you lose the race
depends on:

- the IME's "react to updateSelection(-1,-1)" timing
- the device's CPU / scheduler
- prior state accumulated across scenes (which is why the issue
  manifests in `scene_compose_lifecycle` even though a freshly-launched
  app + the same broadcast sequence works in isolation — see the
  diagnostic notes in `input-test-flaky-on-emulator.md`).

That's why this test passes consistently on the OnePlus 9, fails ~20%
on the x86_64 emulator, and fails ~100% on a Pixel 4a — three
different IMEs, three different timing profiles, same code under test.

## What the test suite misses (concrete examples)

Each of these would be a real regression that the current test suite
does not catch:

- Reverting any of the three text-input bug fixes from `7ad5869` (Fix
  three text-input bugs: vanishing preedit, duplicated word, prepended
  h) — all live in IC, not in the wayland path.
- Breaking `computeReplaceDeltas` so `delete_before`/`delete_after`
  always come back zero: Gboard would still commit the right text
  visually but inserts duplicates because the previous composing
  region wouldn't be deleted.
- Breaking `composingRegionIsPreedit` flag tracking: OpenBoard's
  per-Enter re-commit would re-introduce the "prepended h" behaviour.
- `updateFromCompositor` not calling `removeComposingSpans`: stale
  composing spans in the Editable would mis-classify subsequent
  `commitText` calls.

## Plan

Bundle with `consolidate-test-broadcasts-into-exec-broker.md` — the
broker actions there are the transport for the IC-driven test path.

### Step 1 — extract `ImeOutput` interface

`TawcInputConnection` and the reverse-JNI keyboard-visibility code
currently call `InputMethodManager` methods directly. Extract an
interface for all outbound IMM calls:

```kotlin
interface ImeOutput {
    fun updateSelection(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    fun showSoftInput()
    fun hideSoftInput()
    fun restartInput()
}
```

Production implementation wraps the real `InputMethodManager`.
Test implementation records every call (so tests can assert on
outbound behaviour) and never touches the real IMM.

This solves the IME race by construction: `showSoftInput()` goes to
the test recorder, the real keyboard never appears, the real IME
never receives `updateSelection`, so it never fires defensive
`finishComposingText` calls. No per-method gates, no markers, no
suppression flags.

`input-test-flaky-on-emulator.md` (Problem 2 above) is closed by
this step.

### Step 2 — drive tests through `TawcInputConnection`

After the broker-action migration lands, convert the input test
suite to call the real IC methods. Broker actions post to the main
thread and call `ic.commitText(text, 1)`,
`ic.setComposingText(text, 1)`, `ic.setComposingRegion(start, end)`,
etc. — the exact same `InputConnection` surface the real IME calls.
Every line of IC logic (`computeReplaceDeltas`, the Editable mirror,
`composingRegionIsPreedit` flag tracking, `unitsToKeyCounts`,
the `commitText` short-circuit) runs on the test path. No owned
code is skipped.

The test observes results from two directions:

- **Wayland side:** the GTK debug app reports what the client
  received (`TEXT_CHANGED`, `PREEDIT`, `CURSOR_POS`, etc.).
- **IME side:** the `ImeOutput` test implementation records what
  outbound IMM calls were made (`updateSelection` args,
  `showSoftInput`/`hideSoftInput` timing, `restartInput`).

Test structure — two groups:

- **`mod ic`** — the workhorse. Every Gboard/OpenBoard-shaped
  scene drives real IC methods and asserts both the wayland-side
  observable and the outbound `ImeOutput` calls.
- **`mod wayland_only`** — the small "compositor's text-input-v3
  done-ordering is correct given these exact protocol events" set.
  Keeps the bypass `inject-text` / `set-composing` actions
  (calling `NativeBridge.native*` directly). Today this is roughly
  `scene_autocorrect_replaces_preedit` and
  `test_surroundingless_client_uses_keyboard_for_backspace`.

Convert existing scenes and add the missing-coverage cases:
Gboard commit-replace, OpenBoard per-Enter, `setComposingRegion`
then commit, `updateFromCompositor` clearing composing spans.

### Toggling test mode

Two broker actions: `enable-test-input` swaps the `ImeOutput`
implementation on the active IC to the test recorder;
`disable-test-input` restores the real IMM implementation.
Process death resets to production (crash-safe by construction).
Release builds have no broker, so neither action exists.

### Cross-reference: touch-down preedit finalization

The compositor's touch-down preedit commit
(`remove-touch-down-preedit-finalization.md`) is a dirty workaround
that should be removed. It unconditionally commits the preedit on
every touch whether or not the cursor moves — wrong when the user
taps the current position, taps a non-text element, or scrolls.

### Cross-reference: hardware keyboard

When hardware-keyboard support lands
(`no-hardware-keyboard-support.md`), hardware key events arrive via
`View.dispatchKeyEvent`, not `IC.sendKeyEvent`. The `ImeOutput`
interface doesn't cover this path — it will need its own test
injection point.

## Out of scope for this issue

- Real-IME-reactivity coverage. The point of the `ImeOutput` swap
  is to remove the system IME from the test loop because it's a
  non-deterministic third-party. A separate, smaller test suite
  that explicitly drives Gboard-via-instrumentation could come
  later, but it should be a focused regression suite, not the
  workhorse.
- State machine refactoring (moving state between Kotlin and Rust).
  The current split works; the `ImeOutput` interface is orthogonal
  to where state lives.
- Multi-window / multi-activity input dispatch.

## References

- `notes/text-input.md` — the IC ↔ wayland-text-input-v3 bridge design.
- `app/src/main/java/me/phie/tawc/compositor/TawcInputConnection.kt`
  — header comment goes through the Android↔wayland model mismatch
  in detail.
- `app/src/main/java/me/phie/tawc/compositor/CompositorActivity.kt:93`
  — `testInputReceiver` (both bypass and `IC_*` paths).
- `compositor/src/text_input.rs:518` — bypass-side
  `TextInputEvent::CommitString` handler.
- `tests/integration/tests/input.rs` — the test suite this issue is
  about.
- `input-test-flaky-on-emulator.md` — predecessor that this issue
  subsumes; documents the failure mode without identifying the
  underlying architecture problem.
