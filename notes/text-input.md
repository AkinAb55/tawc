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

`TawcInputConnection` extends `BaseInputConnection(view, true)` (fullEditor=true), which maintains an internal `Editable` buffer. We call `super` in all overridden methods so this buffer stays in sync with what we send to the Wayland client. This is critical because Gboard queries the buffer (`getTextBeforeCursor`, `getTextAfterCursor`, `getExtractedText`) to understand editor state for predictions, autocorrect, and cursor tracking.

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
| Client commits with change_cause=other | `InputMethodManager.updateSelection()` |

### Text Input Focus

Text input focus follows keyboard focus (the first alive toplevel's wl_surface). Focus updates happen in the frame timer, after cleanup and before flush.

## Implementation Notes

### finishComposingText

Android's `finishComposingText()` means "commit the current composing text as-is." We track the last preedit text sent to each instance (`current_preedit`). On finishComposingText, we send `commit_string(tracked_preedit)` + `preedit_string(None)` + `done`. Without this, the composing text would just vanish.

### UTF-8 byte counts vs character counts

The Wayland protocol uses UTF-8 byte offsets everywhere. Android's `deleteSurroundingText` uses Java character counts (UTF-16 code units). The compositor converts using the client's stored surrounding text when available, falling back to 1:1 (ASCII assumption) when not.

Backspace and forward-delete are sent as real `wl_keyboard` key events to avoid this conversion entirely — the client handles deletion natively with full knowledge of its own text encoding.

### Keyboard visibility deferred

`sync_keyboard_visibility()` checks the final enabled state of all instances and only shows/hides the keyboard when the state actually changes. This prevents rapid disable+enable cycles (common during cursor movement within a text field) from causing keyboard flicker or failed re-show.

## Open Questions

1. **Content type forwarding**: `set_content_type` from clients is received but not forwarded to Android's EditorInfo. Would improve keyboard layout (URL keyboard for URL bars, etc.).

2. **Surrounding text → InputConnection**: The compositor stores `set_surrounding_text` from clients, but doesn't forward it back to Android. Proper implementation would override `getTextBeforeCursor()` / `getTextAfterCursor()` in TawcInputConnection to return data from the compositor's stored state, rather than relying solely on the BaseInputConnection Editable.

3. **Batch editing**: Android groups IME operations between `beginBatchEdit()` / `endBatchEdit()`. Currently each operation gets its own `done` event. Batching into a single `done` would be more correct but functionally the current approach works for simple cases.
