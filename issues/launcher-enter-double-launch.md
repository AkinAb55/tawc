# Launcher: hardware Enter launches the top app twice

`LauncherActivity`'s search field `setOnEditorActionListener` treats
both `KEYCODE_ENTER` `ACTION_DOWN` and the IME `GO`/`DONE` action as
"launch". A hardware Enter press (external keyboard, `adb shell input
keyevent 66`) delivers a key event *and* triggers the editor action, so
`launchTop()` runs twice ~10ms apart and spawns the app twice.

Observed while verifying launch-failure reporting: two identical
`tawc-launcher: launch bssh: ...` warnings from one Enter press.

Soft-keyboard taps and row taps are unaffected. Likely fix: only handle
the IME action and let the key event fall through (or vice versa), or
debounce in `launchTop` since the activity `finish()`es anyway.
