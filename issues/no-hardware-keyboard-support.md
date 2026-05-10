# No hardware keyboard support

Wayland clients running under tawc receive no key events from a
physical keyboard attached to the Android device. Only the on-screen
IME path (Gboard / OpenBoard / etc. → `TawcInputConnection`) reaches
the compositor, plus the synthesised key events the IC emits itself
(Enter from `performEditorAction`, Backspace/Forward-Delete from
`deleteSurroundingText`). Plug a USB keyboard into the phone or
attach one to the emulator and nothing typed on it makes it to the
focused Linux app.

## Why

`CompositorActivity` does not override `dispatchKeyEvent`,
`onKeyDown`, or `onKeyUp`, and `TawcSurfaceView` doesn't either. On
Android, hardware key events are delivered to the focused View's
`dispatchKeyEvent` by the framework — they do not transit
`InputConnection.sendKeyEvent` (that path is for soft IMEs that
choose to emit a `KeyEvent` instead of `commitText`, e.g. Gboard's
Backspace key).

Result: the framework calls `super.dispatchKeyEvent` on our
SurfaceView, which has no handler, the event falls off the View tree
unconsumed, and nothing reaches the compositor's `wl_keyboard`.

## What needs to happen

1. **Android side**: catch `KeyEvent`s on the focused
   `CompositorActivity` (override `dispatchKeyEvent` on
   `TawcSurfaceView` or the Activity), filter to
   `ACTION_DOWN`/`ACTION_UP`, forward to native with the activity id,
   keycode, and action so the compositor can emit press/release
   pairs (today's `nativeSendKeyEvent` only sends a press).
2. **Native side**: extend `nativeSendKeyEvent` (or add a sibling)
   to carry the action and route through
   `KeyboardHandle::input(... KeyState::Pressed | Released ...)`
   instead of the current synthesised press-then-release. Plumb
   modifier state (Shift/Ctrl/Alt/Meta) through the same call so
   xkbcommon resolves the right keysym.
3. **Keymap coverage**: `compositor/src/keymap.rs` currently maps a
   handful of keycodes (Enter, Del, Forward-Del, Tab, …) — extend it
   to cover the full A–Z / 0–9 / punctuation / function-key range, or
   switch the bridge to USB-HID-derived scancodes to skip the
   Android-keycode → evdev round-trip entirely.
4. **Modifier propagation**: `wl_keyboard.modifiers` events need to
   reflect Shift/Ctrl/Alt state from `KeyEvent.getMetaState()` so
   shifted characters and Ctrl-based shortcuts work.
5. **Repeat handling**: Android delivers repeat events with
   `getRepeatCount() > 0`. Wayland clients prefer to do their own
   repeat from `wl_keyboard.repeat_info`; decide whether to swallow
   Android's repeats or pass them through.

## Out of scope

- Compose / dead-key handling beyond what xkbcommon does on the
  client side.
- IME composition driven from the hardware keyboard
  (Gboard-on-hardware-keyboard already routes through the soft-IME
  path, which works today).
- Per-window keyboard focus routing in multi-activity setups —
  follows the same design as touch routing
  (notes/multi-activity.md), so probably falls out for free once the
  Activity-side hook lands.

## Repro

Plug a USB keyboard into the phone (or attach one in the AVD with
`-keyboard host`), launch a Wayland client (e.g.
`bash scripts/rootfs-run.sh 'gedit'`), and try to type. No text
appears. `WAYLAND_DEBUG=1` on the client confirms no `wl_keyboard.key`
events arrive.

## Notes

- This issue surfaced while planning the in-process test-input gate
  for `input-tests-skip-ic-and-race-system-ime.md`. The gate sits at
  the top of `TawcInputConnection` overrides; the same gate will not
  cover hardware keys when they are eventually wired up, because
  hardware keys go through `View.dispatchKeyEvent` rather than the
  IC. When this work lands, mirror the gate at the
  `dispatchKeyEvent` entry point so hardware-key events also respect
  test-injection mode.
