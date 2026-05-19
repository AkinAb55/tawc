# Clipboard XWayland and cap edge cases need coverage

The first clipboard integration test covers Android ClipboardManager
round-trips through a normal Wayland `wl_data_device` client, but it does
not cover XWayland selection paths or hostile clipboard sources.

## Missing coverage

- Android-owned clipboard text pasted into an X11 client.
- X11-owned clipboard text mirrored back into Android.
- A source that writes more than the 1 MiB eager-pull cap.
- A source that never closes its clipboard pipe and relies on the pull
  timeout.

## Why it matters

These paths have separate compositor code from the Wayland-only roundtrip:
XWayland uses Smithay's XWM selection callbacks, and cap/timeout behavior
lives in the eager mirror reader. A regression there can leave the current
`test_android_wayland_clipboard_text_roundtrip` green.

## Likely fix

Add a small Xlib clipboard test app under `tests/apps/` and deploy it from
`scripts/run-integration-tests.sh`, then add focused `xwayland::` tests.
For cap/timeout behavior, extend one of the debug apps with oversized and
non-closing clipboard source modes.
