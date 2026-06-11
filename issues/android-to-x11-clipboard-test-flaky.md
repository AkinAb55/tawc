# test_android_clipboard_text_to_x11 flaked once (empty paste)

One failure in ~4 full-suite runs on the emulator (2026-06-10):
the X11 test client reported `CLIPBOARD_PASTE` with an empty payload
("received: [\"READY\", \"CLIPBOARD_PASTE\"]"). Compositor logs showed a
normal `clipboard-fetch-android` thread attach/detach and no clipboard
warnings, so the Android fetch succeeded and the fd write was silently
short or the xwm-side X11 conversion dropped the data. Passed on
immediate rerun and on two further full-suite runs.

Observed right after the paste-writer deadline change in clipboard.rs,
but for a ~33-byte clip that path behaves the same as the old blocking
`write_all` (one write syscall into an empty pipe; only two extra
fcntls differ), so a pre-existing announce/convert race is more likely.
If it recurs: `write_to_fd_with_deadline` now debug-logs write errors
and timeouts bump `clipboard_write_timeouts_total` in
clipboard-debug-state, so check those first to split fd-write failure
from X11-side loss.
