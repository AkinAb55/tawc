# Remove touch-down preedit finalization

The compositor unconditionally commits the active preedit on every `TouchEvent::Down` before delivering the touch to the client. This is a dirty workaround that needs to be removed.

## What it does

On touch-down, the compositor synchronously emits `commit_string(current_preedit) + preedit_string(None) + done` for the focused text-input instance, then dispatches `touch.down`. The intent is to commit typed-but-not-confirmed text at the old cursor before the tap moves the cursor — since by the time the client sends `set_surrounding_text` with `cause=other`, the cursor has already moved and the preedit can't be committed at the original position.

## Why it's wrong

The compositor is guessing that a touch will move the cursor. It has no way to know that. If the user taps the current cursor position, taps a non-text UI element, starts a scroll gesture, or the app simply ignores the touch, the preedit gets committed for no reason. The IME still thinks it has an active composing region; the Wayland client now has committed text where it expected preedit. The two are out of sync.

The notes call this "strictly better than silently dropping it," but leaving the preedit alone is the correct behavior when nothing warranted a commit. Premature commit is a bug, not a graceful degradation.

## What should happen instead

Preedit finalization on cursor change should be reactive — driven by the client's `set_surrounding_text(cause=other)` — not speculative based on touch events. The `cause=other` path already exists and clears `current_preedit`; the gap is that it currently drops the preedit rather than committing it, and the cursor has already moved so the commit lands at the wrong position.

Fixing that gap properly may require protocol-level thinking (the client has already moved its cursor by the time we hear about it, and text-input-v3 has no "commit at offset" mechanism). But the right answer is to solve that problem, not to paper over it by speculatively committing on every touch.

## Same issue applies to focus-change (`leave`)

The same pre-emptive commit runs on `leave` (text-input focus change). Same problem: the compositor guesses that losing focus means preedit should be committed, but the client may re-enter the same text field immediately (rapid focus cycling).

## References

- `compositor/src/text_input.rs` — touch-down interception and `cause=other` handling
- `notes/text-input.md` "Click during preedit" section — documents the current workaround
- `notes/testing.md` "Test Input Mechanism" — related test-input path that should not bypass the real IME/IC state machine
