# `IC.deleteSurroundingText` mistranslates after the OpenBoard per-Enter pattern

`unitsToKeyCounts` uses the Editable's cursor to translate UTF-16 unit
counts into Backspace / Forward-Delete key counts. After
`commitText` short-circuits (OpenBoard's per-Enter pattern:
`setComposingRegion(0, N) + commitText(word) + commitText("\n")`) the
Editable cursor and the wayland-side cursor diverge, and a subsequent
`deleteSurroundingText` emits keys that land at the wrong wayland-side
position and slice the wrong bytes.

## Repro

Diagnostic instrumentation in `TawcInputConnection.deleteSurroundingText`
(temporary, since reverted) on the emulator's `gtk4-debug-app` after
`scene_recommit_word_then_newline_no_h_prepend` runs:

```
deleteSurroundingText: before=11 after=11 (editable len=8 cursor=6 lastSyncedCursor=5)
emitting keys before=6 after=2
```

State at that point:

- GTK buffer: `"hello\n\n\n"`, cursor at position 8.
- IC Editable: `"hello\n\n\n"`, cursor=6.
- `lastSyncedCursor`: 5.

Request: delete 11/11 UTF-16 units around the cursor. The IC computes 6
Backspaces (codepoints 0..6) + 2 Forward-Deletes (codepoints 6..8) and
fires them. GTK at cursor=8: 6 Backspaces walk back to `"he"`
(cursor=2), 2 Forward-Deletes find no chars past 2 → no-op. Buffer
stuck at `"he"`.

Test integration suite hits this via `reset_buffer` in
`tests/integration/tests/input.rs` between scenes; worked around by
using raw `ic_send_key_event(KEYCODE_DEL/FORWARD_DEL)` instead of
`ic_delete_surrounding_text` for the reset. The workaround is
appropriate for test infra (a "keyboard pressing Backspace until the
buffer's empty" is exactly what a real keyboard does), but the
underlying IC bug remains.

## Why the cursors diverge

Two compounding causes:

1. **`commitText` short-circuit moves Editable cursor silently.** When
   `commitText(text)` matches the active composing region, IC fires
   `super.commitText(text, 1)` to update Editable + clear the
   composing flag, but skips `nativeCommitText` (the buffer already
   contains those bytes; a wire delete-then-commit would be wrong).
   `super.commitText` with `newCursorPosition=1` puts the Editable
   cursor at end-of-replaced-region (e.g. 5). The wayland-side cursor
   stays where it was (e.g. 7, end of buffer). Diverged by 2.

   The next `commitText("\n")` advances each side by 1 — Editable to
   6, wayland to 8. Never resyncs.

2. **GTK hides trailing newlines in `set_surrounding_text`.** For
   buffer `"hello\n\n\n"` with cursor at end (position 8), GTK reports
   surrounding text as `"hello"` with cursor at 5 — the line
   containing the cursor, with the trailing `\n` chopped. So
   `lastSyncedCursor` settles at 5, well behind both Editable cursor
   and the actual wire cursor.

The class-level "Cursor synchronisation" comment in
`TawcInputConnection.kt` documents both edges (the short-circuit
exists *because* of cause 1; the GTK newline edge case is called out
explicitly), but only `commitText` / `setComposingText` paths handle
the divergence (`computeReplaceDeltas` refuses to emit deltas when
`cursor != lastSyncedCursor`). `unitsToKeyCounts` has no equivalent
guard.

## Production impact

Narrow but real:

- After OpenBoard's per-Enter pattern, an IME firing
  `deleteSurroundingText(N, 0)` with `N > 1` before the Editable
  resyncs will delete the wrong characters on the wire. Buffer
  corruption.
- `deleteSurroundingText(1, 0)` (single-char Backspace) happens to
  work either way — it deletes the just-typed `\n` regardless of
  which side's cursor wins, and that's what the user wanted.
- Gboard / OpenBoard fire single-char Backspace for almost all
  deletion. Multi-char `deleteSurroundingText` is rare (autocorrect
  rejection, suggestion-strip clearing). Crossing it with a
  just-Entered line is rarer still.

Not bad enough to be the next thing to fix, but real enough to record
so it's not re-derived next time someone hits it.

## Possible fixes

1. **Track a wire-side cursor separately.** Maintain `wireCursor` in
   the IC, advance it on every `nativeCommitText` /
   `nativeSetComposingText` (by `text.length - before`) and on every
   Backspace / Forward-Delete (by ±1), and reset it from
   `updateFromCompositor` (authoritative). Use it for translation in
   `unitsToKeyCounts` instead of Editable cursor. Most robust.
   Non-trivial — needs wire-side bookkeeping for every outbound op.

2. **Divergence guard in `unitsToKeyCounts` with pass-through
   fallback.** When `Selection.getSelectionStart(ed) != lastSyncedCursor`,
   return `(beforeUnits, afterUnits)` directly (treating each unit as
   one Backspace). Correct for ASCII; over-deletes by one Backspace
   per surrogate-pair within the range when non-BMP text is involved.
   Acceptable trade-off for a documented edge case. Easier than (1).

3. **Have `commitText` short-circuit also push a wire-no-op that
   moves the wayland cursor.** Tricky — text-input-v3 has no "move
   cursor" request. Would require a fake commit_string("") + done or
   similar.

4. **Document and don't fix.** Keep the IC as is; document the edge
   case more visibly. Tests use raw key events for reset_buffer
   (already done). Real Gboard usage is unaffected because it fires
   single-char Backspaces.

(2) is the cheapest correctness improvement; (1) is the right shape
long-term. (4) is what we have today.

## Related code

- `app/src/main/java/me/phie/tawc/compositor/TawcInputConnection.kt`
  - `commitText` short-circuit (lines ~134–174).
  - `computeReplaceDeltas` divergence guard (~258–274).
  - `unitsToKeyCounts` (~221–232) — the missing guard.
  - Class-level "Cursor synchronisation" comment (~57–96).
- `tests/integration/tests/input.rs:reset_buffer` — the workaround
  (raw `ic_send_key_event`).
- `scene_recommit_word_then_newline_no_h_prepend` — the test that
  reproduces the cursor divergence (asserts only the
  user-visible-correct outcome; doesn't catch the cursor drift).
