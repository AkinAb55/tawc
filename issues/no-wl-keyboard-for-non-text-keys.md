# No `wl_keyboard` for non-text keys

Arrow keys, Escape, Tab, Ctrl+C/V/Z, and other non-text keys have no `text-input-v3` equivalent and currently don't reach Wayland clients.

## What's needed

A `wl_keyboard` implementation. The text-input protocol only handles text commits and edits, so anything that isn't producing text — navigation, modifiers, shortcuts — has nowhere to go.

## Blocker

We need to solve xkbcommon on Android first. Either `XKB_CONFIG_ROOT` paths have to point at a keymap directory we ship, or the keymap has to be embedded directly.

## References

See [notes/text-input.md](../notes/text-input.md) ("What about wl_keyboard?") and [plan.md](../plan.md) Phase 7.
