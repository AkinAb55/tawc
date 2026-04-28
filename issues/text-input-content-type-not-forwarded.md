# `set_content_type` not forwarded to Android `EditorInfo`

`set_content_type` from Wayland clients is received but never propagated to Android's `EditorInfo.inputType`, so every field gets the default keyboard.

## Effect

Android can't show context-specific keyboard layouts (email, URL, password, number, etc.). All fields get the default keyboard regardless of what the app requests via `text-input-v3`.

## References

See [notes/text-input.md](../notes/text-input.md) ("Open Questions").
