# Android InputConnection coverage and mirror sync need a real design pass

The Gboard autocomplete-row bug exposed a structural problem in our
Android keyboard bridge: we only handle the `InputConnection` methods
that happened to show up in earlier tests. Unhandled methods can mutate
`BaseInputConnection`'s Editable mirror without forwarding anything to
Wayland, leaving Android and the client out of sync. In the observed
case, accepting a suggestion used a range replacement path; the Editable
could change while the Wayland text box stayed unchanged.

## Current risk

- `TawcInputConnection` is not a comprehensive implementation of the
  Android IME contract.
- The Editable mirror is treated as both state and a scratch area for
  `BaseInputConnection`. If `super.*` changes it but we do not emit the
  matching Wayland transaction, later deltas may be computed from a mirror
  state the client never saw.
- Tests mostly assert Wayland-visible text, but they do not yet assert
  mirror/Wayland equivalence after every IME action, nor do they enumerate
  the full `InputConnection` edit surface.
- Some fallbacks return success or silently no-op in ways that could make
  an IME believe a replacement was accepted when nothing reached the
  client.

Recent partial fixes added coverage for `replaceText`,
`commitCorrection`, and `commitCompletion`, but that should be treated as
a patch, not as proof the bridge is complete.

## Desired end state

Text input handling has one authoritative model and one audited Android
entrypoint table:

- Every mutating `InputConnection` method is either implemented and
  forwarded to Wayland, explicitly rejected with a defensible return
  value, or documented as irrelevant for tawc.
- The Editable mirror is never allowed to drift silently from Wayland's
  last committed surrounding text.
- Tests can catch "Editable changed, Wayland did not" and "Wayland
  changed, Editable did not" regressions.
- Replacement logic is shared, not duplicated across `commitText`,
  `setComposingText`, `replaceText`, correction, completion, delete, and
  key-event paths.

## Plan

1. Audit the Android API surface against the compile SDK.

   Use `javap` or a small generated checklist for
   `android.view.inputmethod.InputConnection` and `BaseInputConnection`.
   Classify methods into:

   - text mutations: `commitText` overloads, `replaceText`,
     `setComposingText` overloads, `setComposingRegion`,
     `finishComposingText`, `commitCorrection`, `commitCompletion`,
     deletes, selection changes.
   - non-text actions: editor actions, key events, content commits,
     handwriting, cursor updates.
   - queries/snapshots: surrounding text, extracted text, cursor caps.

   Put the resulting table in `notes/text-input.md` so future API bumps
   have an obvious diff target.

2. Centralize mutation application in `TawcInputConnection`.

   Add a small internal operation layer such as:

   - `commitAtCursor(text, before, after, cursorPolicy)`
   - `replaceAbsoluteRange(start, end, text, cursorPolicy)`
   - `setPreeditReplacingRange(start?, end?, text)`
   - `deleteRangeAroundWireCursor(before, after)`

   Each public IC method should translate Android semantics into one of
   these operations. The operation layer should:

   - compute delete ranges against the wire cursor, not a stale Editable
     cursor;
   - update the Editable only if the corresponding Wayland transaction is
     emitted, or intentionally roll it back / resync otherwise;
   - update `wireCursor`, `activePreeditUtf16Length`,
     `composingRegionIsPreedit`, and newline preservation in one place.

3. Make mirror drift observable in tests.

   Add a debug-only broker query for the active IC mirror:

   - Editable text
   - selection
   - composing span start/end
   - `wireCursor`
   - `lastSyncedCursor`
   - current preedit length

   Integration tests should compare this to `wayland-debug-app`'s last
   `TEXT_CHANGED` / cursor state after every mutating action that claims
   success. Add at least one negative regression shape for an API that
   previously only changed the Editable.

4. Strengthen the test app assertions.

   `wayland-debug-app` already validates `done(serial)`. Extend it so
   replacement tests can assert the actual wire transaction shape:

   - `replaceText(0, 3, "ABC")` should produce one
     `delete_surrounding_text(3, 0)` plus `commit_string("ABC")` in the
     same `done`.
   - correction paths should do the same.
   - completion paths should be tested only for their real semantics, not
     as a proxy for arbitrary suggestion replacement.

5. Add a matrix of IME-shaped flows.

   Keep the deterministic broker-driven tests, but cover the APIs as IMEs
   actually use them:

   - Gboard suggestion replacement: range replacement of current word.
   - Gboard/autocorrect preedit replacement: preedit then commit.
   - Correction API: old/new text at offset.
   - Completion API: editor-provided completion text, if applicable.
   - Selection divergence before replacement.
   - Replacement while active preedit exists.
   - UTF-16/codepoint cases around emoji and combining marks.
   - Surrounding-less clients, where range replacement may need to fail
     loudly instead of pretending success.

6. Decide failure semantics.

   For methods we cannot faithfully represent in text-input-v3, return
   `false` and leave the Editable unchanged/resynced. Do not let `super`
   mutate the mirror before deciding whether the Wayland operation is
   representable. Document each false-return case.

7. Add maintenance guardrails.

   Add a host/unit test or generated file that fails when compile SDK's
   `InputConnection` gains a new mutating method not present in the audit
   table. This keeps future Android API additions from becoming silent
   mirror-only paths.

## Acceptance criteria

- `notes/text-input.md` contains an audited `InputConnection` method table.
- Every mutating IC method is implemented, explicitly rejected, or
  documented as query-only/no-op.
- Debug tests can assert Editable mirror state equals Wayland-visible
  state after successful mutations.
- Full `scripts/run-integration-tests.sh input` passes.
- Manual repro with GTK widget factory + Gboard suggestion row replaces
  the current word reliably.

