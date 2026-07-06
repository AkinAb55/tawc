# TAWC-DRI has no frame pacing: X11 GL clients free-run at 2000+ fps

Measured 2026-07-06 on OnePlus 9 while verifying TAWC-DRI v0.3:
`es2gears_x11` sustains ~2500 presents/sec (`wlegl_create_buffer_total`
delta), burning CPU/GPU for frames the 60 Hz display never shows.

The v0.3 `TAWCDRIBufferRelease` event bounds the number of in-flight
buffers (client blocks in `dequeueBuffer` when all 3 are busy), but the
compositor releases a wl_buffer as soon as the next commit replaces it —
release rate tracks the client's own commit rate, not display refresh.
Nothing in the pipe waits for vsync: the X server attaches + commits on
every `TAWCDRIPresentBuffer` immediately, and `eglSwapInterval` is
accepted client-side (`m_swap_interval`) but never enforced.

Pre-existing behaviour (the old round-robin free-ran the same way);
v0.3 just made it observable via the release counters.

Fix directions (pick when it matters):
- Server-side: defer the attach/commit of the latest buffer to the
  compositor's frame callback (`wl_surface.frame`) and drop/replace
  intermediate presents — releases then tick at display rate and the
  v0.3 backpressure clamps the client to ~60 fps for free.
- Or a `TAWCDRIPresentComplete`-style event (Present's CompleteNotify
  analogue) driven by frame callbacks, honoured in the client when
  `m_swap_interval > 0`.

See notes/xwayland.md "TAWC-DRI v0.3" (frame pacing paragraph).
