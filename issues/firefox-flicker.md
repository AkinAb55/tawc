# Visual flickering reported in Firefox

## Evidence

User reports visual flickering while using Firefox in the compositor.
That's all the hard evidence we have right now — no screenshots, no
logs, no reproduction steps, no frequency/pattern characterization.
Everything below is a theory about a plausible cause; it has not been
confirmed against the symptom.

## Theory (unconfirmed)

The early `wl_buffer.release` in the wlegl path creates a window where
our cached texture points at an AHB the client is actively
overwriting. If any re-render fires during that window, we'd draw
partially-written contents.

Flow:

1. Client commits buffer B1 on surface S.
2. Frame timer tick: `import_wlegl_buffers` imports texture T1 from
   B1, `render_frame` draws T1, `release_consumed_wlegl_buffers`
   (`server/compositor/src/render.rs:535`) sends `wl_buffer.release(B1)`
   and sets `wlegl_state.released = true`. T1 and
   `surface_wlegl[S].current_buffer = Some(B1)` remain in place.
3. libhybris dequeues B1 back into its pool and the client starts
   rendering a new frame into it.
4. Something *else* triggers `needs_render` before S commits a new
   buffer — e.g. another surface's commit sets
   `buffer_commit_pending`, a popup commits, a subsurface elsewhere
   in the tree updates, or `toplevels_changed` fires
   (`server/compositor/src/event_loop.rs:256-275`).
5. The re-render walks `collect_surface_draws` for wlegl
   (`render.rs:490`), finds T1 still on `WleglBufferData`, and draws
   it — now showing whatever partial pixels libhybris has written so
   far. Next frame, once S commits B2, the correct content comes
   back. Net effect: a flicker.

Firefox runs a toplevel surface (placeholder buffer) plus a WebRender
subsurface (real content) plus occasional popup/tooltip subsurfaces
(see `notes/rendering.md:22`), so "some other surface commits between
release and next-commit on the WebRender subsurface" isn't rare.

## Why the early release exists

The early release is required for libhybris's `setBufferCount(3)`
dequeue pool: without it, `dequeueBuffer` blocks forever waiting for
a release that Smithay's auto-release only sends on the *next*
commit, which never happens. See `notes/wsi-layer.md:158` and the
comment on `release_consumed_wlegl_buffers` in `render.rs`.

Any fix has to preserve that property.

## Possible fixes (if the theory holds)

- Skip drawing a wlegl surface in `collect_surface_draws` when
  `wlegl_state.released == true` — the last rendered frame stays on
  screen (front buffer) until the client commits a new buffer. Risk:
  if something else forces a redraw, the wlegl surface disappears
  for that frame instead of flickering. Need to check whether that's
  actually better.
- Defer the release by one frame: render frame N with T1, release
  B1 *after* presenting frame N+1. Keeps libhybris's pool fed (with
  a one-frame lag) and guarantees we never draw a released buffer.
  Needs a small per-surface "previous buffer to release" slot.
- Keep a reference to the AHB (via `AHardwareBuffer_acquire`) from
  our side until the client commits a replacement, so libhybris
  allocates a new slot instead of reusing the one we're still
  showing. This fights the pool cap and probably isn't the right
  answer.

## Next steps

- Reproduce and characterize first. What kind of flicker — whole
  window, a region, a scanline? Correlated with scrolling, cursor
  movement, popups, animation? How often?
- If the symptom matches the theory, try the "skip drawing when
  released" fix and see if the flicker goes away.
- If it doesn't match, this issue should be re-theorized or
  closed. Candidates to rule in/out then: Firefox's WebRender
  double-buffering, the xdg-configure/buffer-scale mismatch
  described in `notes/rendering.md:57`, or something in the
  libhybris attach-in-`queueBuffer` patch.

## Where

- `server/compositor/src/render.rs:535` — `release_consumed_wlegl_buffers`
- `server/compositor/src/render.rs:490` — wlegl draw collection
- `server/compositor/src/event_loop.rs:246` — frame timer / render gating
- `server/compositor/src/compositor.rs:217` — pre-commit hook suppressing
  Smithay's auto-release
- `notes/wsi-layer.md:158` — background on the release timing
