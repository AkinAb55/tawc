## Surrounding text not fully bridged to Android

The compositor now stores `set_surrounding_text` from Wayland clients (for byte/char conversion and `updateSelection`), but this data is not fully bridged to the Android side:

- `TawcInputConnection` calls `super` in overridden methods, which keeps `BaseInputConnection`'s internal `Editable` approximately in sync. Gboard can query it via `getTextBeforeCursor()` etc.
- However, the `Editable` may drift from the Wayland client's actual text over time (e.g., if the client applies changes that aren't reflected in the `Editable`).
- The fully correct fix: override `getTextBeforeCursor()` / `getTextAfterCursor()` in `TawcInputConnection` to return data from the compositor's stored surrounding text via reverse JNI.
