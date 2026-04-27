# Text Input

## Overview

Text input bridges Android's `InputConnection` API and Wayland's `zwp_text_input_v3` protocol. Both are designed around the same concepts (composing text, committing text, deleting surrounding text, content type hints), but differ in details that the compositor must translate correctly.

## Protocol: zwp_text_input_v3

Text-input-v3 is a double-buffered, serial-synchronized protocol.

**Client → compositor (requests), applied atomically on `commit`:**
- `enable` / `disable`: Whether client wants IME input. Enable resets ALL associated state.
- `set_surrounding_text(text, cursor, anchor)`: UTF-8 text around cursor, byte offsets.
- `set_text_change_cause(cause)`: Why text changed — `input_method` (IME did it) or `other` (user touch, arrow keys, etc.).
- `set_content_type(hint, purpose)`: What kind of text field.
- `set_cursor_rectangle(x, y, w, h)`: Cursor position in surface coords.
- `commit`: Atomically applies all pending requests. Compositor counts these.

**Compositor → client (events), applied atomically on `done`:**
- `enter(surface)` / `leave(surface)`: Text input focus.
- `preedit_string(text, cursor_begin, cursor_end)`: Composing text preview. cursor_begin/cursor_end are byte offsets — when equal, shown as a caret; when different, shown as highlight.
- `commit_string(text)`: Final text to insert.
- `delete_surrounding_text(before_length, after_length)`: Bytes to delete. Relative to cursor position from client's last `set_surrounding_text`. If preedit is present, before_length counts from preedit start, after_length from preedit end.
- `done(serial)`: Apply all pending events. Serial = number of client commits seen.

**Done event application order (critical):**
1. Replace existing preedit string with the cursor.
2. Delete requested surrounding text.
3. Insert commit string with the cursor at its end.
4. Calculate surrounding text to send.
5. Insert new preedit text in cursor position.
6. Place cursor inside preedit text.

**Serial synchronization:**
- Compositor counts client `commit` requests per instance.
- `done(serial)` carries this count.
- Matching serial: client updates its state normally.
- Mismatched serial: client still applies text changes, but defers state updates until a matching serial arrives.

**State lifecycle:**
- `enable` resets all state from the previous cycle (surrounding text, change cause, content type, cursor rectangle, AND compositor-side preedit/commit/delete state).
- After `enter` event or committed `disable`: all state is invalidated.

### wl_keyboard requirement

Firefox won't send text-input-v3 `enable` unless the seat has keyboard capability and the surface has keyboard focus. The seat must advertise keyboard capability (`seat.add_keyboard()`) and send `wl_keyboard.enter` to the focused surface.

### Keys sent as wl_keyboard events (not text-input-v3)

Some keys must be real keyboard events, not text-input-v3 operations:
- **Backspace** (evdev KEY_BACKSPACE=14): Real key event lets the client handle deletion natively, avoiding UTF-8 byte/character count mismatch issues.
- **Forward Delete** (evdev KEY_DELETE=111): Same rationale.
- **Enter** (evdev KEY_ENTER=28): Real key event required for single-line fields (URL bars) which ignore `commit_string("\n")`.

### Not needed

- **zwp_input_method_v1/v2**: For when a separate Wayland client acts as the IME.
- **virtual-keyboard-unstable-v1**: For injecting keystrokes from a Wayland client.

## Android Side: InputConnection

### Key methods

| InputConnection method | Action | Wayland equivalent |
|---|---|---|
| `commitText(text, pos)` | Insert finalized text | `commit_string(text)` + `done` |
| `setComposingText(text, pos)` | Set composing text | `preedit_string(text, len, len)` + `done` |
| `finishComposingText()` | Finalize composing text | `commit_string(tracked_preedit)` + `preedit_string(None)` + `done` |
| `deleteSurroundingText(before, after)` | Delete around cursor (char counts) | `delete_surrounding_text(byte_before, byte_after)` + `done` |
| `sendKeyEvent(event)` | Hardware key event | Mapped to wl_keyboard or text-input-v3 |
| `getTextBeforeCursor(n)` | IME queries editor text | Served from BaseInputConnection's Editable |
| `getTextAfterCursor(n)` | IME queries editor text | Served from BaseInputConnection's Editable |

### sendKeyEvent mapping

| Android KeyEvent | Action |
|---|---|
| KEYCODE_DEL (backspace) | `wl_keyboard` key event (evdev KEY_BACKSPACE=14) |
| KEYCODE_FORWARD_DEL | `wl_keyboard` key event (evdev KEY_DELETE=111) |
| KEYCODE_ENTER | `wl_keyboard` key event (evdev KEY_ENTER=28). Also intercepted from `commitText("\n")`. |
| KEYCODE_TAB | `commit_string("\t")` + `done` |

### BaseInputConnection and Editable

`TawcInputConnection` extends `BaseInputConnection(view, true)` (fullEditor=true), which maintains an internal `Editable` buffer. The IME (Gboard, OpenBoard, etc.) queries this buffer via `getTextBeforeCursor`, `getTextAfterCursor`, and `getExtractedText` to drive predictions, autocorrect, and word-boundary detection. **The Editable must mirror the Wayland client's actual text** for those features to work — otherwise the IME's internal model drifts from reality and autocorrect/composing/delete operate on the wrong positions.

We keep the Editable in sync via two channels:

1. **Outbound (Android → Wayland):** every overridden IME method calls `super` first to update the Editable, then JNI to forward to the compositor. This ensures the Editable predicts what the Wayland client will have right after our event takes effect.

2. **Inbound (Wayland → Android):** when a client commits a `set_surrounding_text`, the compositor calls reverse-JNI `onUpdateEditableText(text, selStart, selEnd)`. That replaces the active `TawcInputConnection`'s Editable contents and selection so the IME sees the editor's truth, not just our predictions. This catches:
   - Cursor moves caused by user touch / arrow keys (`change_cause=other`)
   - Editor-side text changes (autocomplete in Firefox URL bar, paste, undo, mid-stream insertions)
   - Drift between what we sent and what the client actually applied

Without (2), the IME's text model silently desyncs after the first non-IME cursor move and every later operation lands at the wrong position. The single `TawcInputConnection` is cached on `NativeBridge.activeInputConnection` so reverse-JNI updates always hit the live IME session (and broadcast tests share state across multi-step flows).

With `fullEditor=true`, `mFallbackMode=false`, so `sendCurrentText()` is a no-op — calling `super` does NOT cause duplicate input via key events.

### Showing/hiding the keyboard

- When any text input instance is enabled: `InputMethodManager.showSoftInput()`.
- When no instances are enabled: `InputMethodManager.hideSoftInputFromWindow()`.
- Keyboard visibility is tracked to avoid redundant calls and handle rapid disable+enable cycles (e.g., cursor movement within a text field may trigger disable+enable in quick succession).

### Cursor change notification

When the Wayland client reports cursor/selection changes caused by non-IME actions (change_cause=other), the compositor calls `InputMethodManager.updateSelection()`. This is critical — without it, Gboard's internal state (composing region, cursor position, text model) becomes stale after cursor movement, causing broken behavior on subsequent typing.

## Architecture

### State Model

Per text-input instance, the compositor tracks:

1. **Serial counter** (`commit_count`): Number of client commits processed.
2. **Pending state** (applied on next commit): enable/disable, surrounding text, change cause.
3. **Current (committed) state**: enabled flag, surrounding text.
4. **Compositor-side tracking**: current preedit text sent to client (needed for `finishComposingText`).

State is properly double-buffered: pending fields are stored when requests arrive and applied atomically on `commit`, per the protocol spec. The `enabled` flag is per-instance, not global.

### Data Flow

```
Android Gboard (IME)
     ↓
InputConnection callbacks (TawcInputConnection.kt)
  - Calls super (updates BaseInputConnection Editable)
  - Calls JNI (sends to compositor)
     ↓
TextInputEvent enum + calloop channel
     ↓
Event loop source (event_loop.rs)
     ↓
If KeyPress: send wl_keyboard key event (press + release)
Else: text_input_state.handle_android_event()
     ↓
Protocol events to client:
  - preedit_string(text, cursor_begin=len, cursor_end=len)
  - commit_string(text)
  - delete_surrounding_text(byte_before, byte_after)
  - done(serial)
     ↓
Wayland client (Firefox) applies per done ordering
     ↓
Client sends back: set_surrounding_text + set_text_change_cause + commit
     ↓
Compositor processes commit:
  - Stores surrounding text (double-buffered)
  - If change_cause=other: calls updateSelection → Android
  - Updates keyboard visibility
```

### Reverse Channel: Compositor → Android

| Trigger | Android action |
|---|---|
| Any instance enabled (via sync_keyboard_visibility) | `InputMethodManager.showSoftInput()` |
| No instances enabled | `InputMethodManager.hideSoftInputFromWindow()` |
| Client commits a `set_surrounding_text` (any cause) | `TawcInputConnection.updateFromCompositor(text, selStart, selEnd)` — replaces Editable contents and selection |
| Client commits with change_cause=other | Additionally `InputMethodManager.updateSelection()` so the IME drops its composing region |

### Text Input Focus

Text input focus follows keyboard focus (the first alive toplevel's wl_surface). Focus updates happen in the frame timer, after cleanup and before flush.

## Implementation Notes

### Click during preedit: clear, don't follow

When a preedit is active and the user taps elsewhere, GTK4 keeps rendering the preedit at the (now-moved) cursor position — so the typed-but-not-committed word visually follows the cursor, which is what the user perceives as "the active word moves".

The compositor cannot commit the preedit at its old cursor: by the time `set_surrounding_text` with `cause=other` reaches us, the client's cursor has already moved, and Wayland's text-input-v3 protocol has no way to insert text at any position other than the current cursor. We also can't pre-emptively commit on touch DOWN — most touches don't move the cursor (button taps, scrolls, multi-touch gestures), and over-committing would silently insert the user's preedit into the buffer on every touch.

The principled fix is to **clear the preedit on `cause=other`** — both compositor-side (`current_preedit = None`) and client-side (send `preedit_string(None) + done`). The client emits its `preedit-changed("")` signal, the overlay disappears, and the user's in-progress word is dropped. This matches every desktop text widget's behavior on click during composition: the in-progress word is abandoned, not silently inserted somewhere unexpected.

### Why we don't propagate `setComposingRegion`-driven replacement

Android's `setComposingRegion(start, end)` marks already-committed text as a composing region. The bytes stay in the editor; only the IME's annotation changes. A naive bridge would translate the next `setComposingText("X")` into `delete_surrounding_text + preedit_string` to physically replace the marked region.

But IMEs (Gboard, OpenBoard) issue `setComposingRegion` aggressively — typically marking the word at the cursor on every cursor change, as a hint for predictive correction tracking. Propagating this as a real region replacement causes the "currently active word moves with the cursor" bug: every keystroke after a click moves the word the IME's predictor flagged into the user's typing position.

`TawcInputConnection.setComposingText` therefore always emits `preedit_string` only, never `delete_surrounding_text`. The original committed text stays where it was; the preedit shows up at the cursor. Real autocorrect (commit-on-top-of-typed-preedit) still works because the protocol's done-ordering replaces existing preedit when a `commit_string` is sent.

The compositor *does* still accept replacement deltas in the wire protocol — `TextInputEvent::SetPreeditString` and `CommitString` carry `delete_before`/`delete_after`. Test broadcasts use these to simulate explicit IME-driven word replacement (`test_compose_region_replaces_committed_text`). Real production IME use just doesn't trigger that path.

### finishComposingText

Android's `finishComposingText()` means "commit the current composing text as-is." We track the last preedit text sent to each instance (`current_preedit`). On finishComposingText, we send `commit_string(tracked_preedit)` + `preedit_string(None)` + `done`. Without this, the composing text would just vanish.

**`current_preedit` must be cleared when the Wayland client commits a `set_surrounding_text` with `cause=other`.** A non-IME cursor move (user touch, arrow keys) means the client has finalized any preedit on its side. If we keep our `current_preedit` tracking, a subsequent `finishComposingText` from the IME (which it issues defensively after cursor moves) re-commits the preedit at the new cursor — and "words randomly reappear" in the editor. Cause=other is the explicit signal from the protocol that the client's view has changed independently.

### setComposingRegion + setComposingText (Gboard's "tap to retype")

Android's IME can mark already-committed text as a composing region via `setComposingRegion(start, end)`. The bytes stay in the editor's buffer; only the IME's annotation changes. The next `setComposingText("...", 1)` *replaces* that region with the new preedit.

Wayland's text-input-v3 has no equivalent — preedit is overlay, not a span over committed text. To bridge, `TawcInputConnection` tracks a `composingRegionIsPreedit` flag:

- `setComposingText(text)` → flag = `true` (the new region IS the new Wayland preedit).
- `setComposingRegion(start, end)` → flag = `false` (the marked region is committed text on Wayland).
- `commitText` / `finishComposingText` / `updateFromCompositor` → flag = `false` (no region after).

When the next `setComposingText` or `commitText` runs and the flag is `false`, the IC computes (before, after) UTF-16 unit deltas around the cursor that span the existing composing region. These deltas travel as extra parameters on `nativeSetComposingText` / `nativeCommitText`. The compositor emits `delete_surrounding_text(before_bytes, after_bytes)` first, then the new preedit/commit string. Without this delete, the original word stays in committed text and the replacement becomes a duplicate.

This only works when the cursor is *inside* the composing region — Wayland's `delete_surrounding_text` deletes around the cursor. IMEs typically pick a region that contains the cursor (the word at the click location), so the constraint is rarely violated. When the cursor sits outside the region, the IC falls back to plain preedit_string and accepts the divergence — `set_surrounding_text` from the client will reconcile the Editable.

### UTF-8 bytes vs UTF-16 code units vs Unicode chars

Three units are in play and they don't all agree:

- **Wayland protocol:** UTF-8 byte offsets (`set_surrounding_text` cursor/anchor, `delete_surrounding_text` before/after).
- **Android `InputConnection`:** UTF-16 code units (Java `char`). `deleteSurroundingText(2, 0)` for an emoji means 2 UTF-16 units, which is one non-BMP scalar = 4 UTF-8 bytes.
- **Rust `char`:** Unicode scalar values. One emoji = 1 Rust char = 2 UTF-16 units = 4 UTF-8 bytes.

`utf16_units_to_bytes` walks the stored surrounding text counting UTF-16 units (`char::len_utf16`) and accumulates UTF-8 bytes (`char::len_utf8`). Falls back to 1:1 mapping when no surrounding text is available. `byte_offset_to_utf16_count` does the inverse direction for selection updates pushed back to Android.

Backspace and forward-delete are sent as real `wl_keyboard` key events instead of `delete_surrounding_text`, sidestepping the conversion entirely — the client deletes a character with full knowledge of its own text encoding.

### Keyboard visibility deferred

`sync_keyboard_visibility()` checks the final enabled state of all instances and only shows/hides the keyboard when the state actually changes. This prevents rapid disable+enable cycles (common during cursor movement within a text field) from causing keyboard flicker or failed re-show.

## Open Questions

1. **Content type forwarding**: `set_content_type` from clients is received but not forwarded to Android's EditorInfo. Would improve keyboard layout (URL keyboard for URL bars, etc.).

2. **Batch editing**: Android groups IME operations between `beginBatchEdit()` / `endBatchEdit()`. Currently each operation gets its own `done` event. Batching into a single `done` would be more correct but functionally the current approach works for simple cases.

3. **Composing region preservation across cursor moves**: When the Wayland client moves the cursor mid-compose (`change_cause=other`), we drop any preedit. Strictly correct per spec but some IMEs might want to keep composing if the cursor stayed inside the preedit range.

4. **Composing region replacement when cursor outside region**: `pendingComposingRegionReplacement` only emits a delete when the cursor sits inside the composing region. Outside that case the new preedit lands at the cursor without removing the old region; the next `set_surrounding_text` from the client reconciles the Editable but Wayland transiently shows the original word AND the new preedit. Real IMEs almost always pick regions containing the cursor, so this is acceptable in practice.

## Test infrastructure note

Test broadcasts (`me.phie.tawc.TEXT_INPUT`, `SET_COMPOSING_TEXT`, etc.) **bypass `TawcInputConnection`** and call the native bridge directly. The reason: the device's installed IME (Gboard, OpenBoard) binds to the SurfaceView's `InputConnection` and reacts to every Editable change with its own `setComposingRegion`/`setComposingText` calls, which makes integration tests non-deterministic. Bypassing the IC keeps tests focused on the compositor's text-input pipeline. Real IME usage still flows through `TawcInputConnection` — that's how the system IMM dispatches IME events. To simulate Gboard's "tap to retype" flow in tests, use `input_set_composing_with_delete(text, before, after)` / `input_text_with_delete(...)` which carry explicit replacement deltas.
