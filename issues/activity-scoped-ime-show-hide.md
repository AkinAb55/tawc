# IME show/hide reverse JNI is still global

`NativeBridge.onShowKeyboard()` and `onHideKeyboard()` still target
`NativeBridge.inputView`, which is process-global. In multi-activity or
late-callback cases, a text-input enable/disable from one host can show or
hide the keyboard against whichever Activity most recently registered its
SurfaceView.

Editable mirror sync is activity-scoped now:
`onUpdateEditableText(activityId, text, selStart, selEnd)` looks up the
matching `CompositorActivity` before updating its `TawcInputConnection`.
Keyboard visibility should follow the same pattern.

## Expected fix

- Thread the focused `activityId` through text-input keyboard visibility
  reverse-JNI calls.
- Replace `NativeBridge.inputView` show/hide use with a
  `CompositorService.getActivity(activityId)` lookup.
- Keep the sticky late-view replay behavior, but scope it by activity.
- Add end-to-end coverage through normal input/client behavior, not private
  Kotlin/Rust state.

Context: [notes/multi-activity.md](../notes/multi-activity.md) already calls
out this design gap under Input / focus / IME.
