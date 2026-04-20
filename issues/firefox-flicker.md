# Visual flickering reported in Firefox

## Symptom

User report (the whole of the hard evidence): "when content changes it
sometimes flickers between the old frame and the new frame for a few
frames before settling." Not characterized further than that —
specific trigger action, frequency, page vs chrome, size of the
flickering region are all still unknown.

Attempts to reproduce it headlessly via screencap/screenrecord have
not succeeded. A captured `ABCA ABCA`-style pixel-hash cycle on the
URL-bar region turned out to be sub-pixel encoder noise, not real
flicker — the frames were visually identical in a side-by-side
inspection. So nothing in this issue's investigation so far has
actually observed the symptom; all the theories below were worked
from the description alone.

## Things tried that did NOT help

### 1. Early `wl_buffer.release` race (original theory)

**Theory:** the former `render::release_consumed_wlegl_buffers`
path sent `wl_buffer.release` right after the first render of a
wlegl buffer, while the buffer's `GlesTexture` was still in use.
libhybris would hand the slot back to the client, the client would
start overwriting the AHB, and any re-render triggered by another
surface before S's next commit would sample partial pixels.

**Fix attempted:** replaced the custom early-release + pre-commit
hook with Smithay's stock `SurfaceAttributes::merge_into`
release-on-replace (sends `wl_buffer.release` for the previous
buffer at the next commit). The "libhybris deadlocks on first
commit without an early release" claim in the old
`notes/wsi-layer.md` turned out to be inaccurate: with a
3-slot dequeue pool (`setBufferCount(3)` in
`libhybris/hybris/platforms/wayland/wayland_window_common.cpp:196`)
and frame callbacks pacing the client, one release per commit is
plenty.

**Result:** integration tests continued to pass, code got simpler,
**no change to the flicker.** This change was kept — it's a
straightforward simplification independent of the flicker — and is
now documented in `notes/wsi-layer.md`. The git log entry
summarising it is "Remove early wl_buffer.release in wlegl path,
rely on Smithay's merge_into".

### 2. Adreno external-sampler staleness (per-buffer EGLImage rebind)

**Theory:** on Adreno, a `GL_TEXTURE_EXTERNAL_OES` bound once to an
EGLImage captures tile/compression metadata at
`glEGLImageTargetTexture2DOES` time and can keep sampling stale
content when the client rewrites the same AHB. SurfaceFlinger's
`GLConsumer` re-creates the EGLImage on every buffer acquire; we
were creating each EGLImage once and destroying it immediately
after the first bind.

**Fix attempted:** added `WleglBufferData::needs_refresh` (set in
the commit handler on every wlegl attach) and a
`bind_ahb_to_texture` helper that, per re-committed buffer, created
a fresh EGLImage from the AHB, re-ran `glEGLImageTargetTexture2DOES`
on the same GL texture object, then destroyed the previous image.
Old EGLImage tracked on `WleglBufferData` so `Drop` could destroy
it.

**Result:** no observable change. Reverted — not correct to keep
that much complexity for a speculative driver workaround that
didn't help.

### 3. Firefox/WebRender partial-present over libhybris pool

**Theory:** WebRender, when `EGL_EXT_buffer_age` is advertised, does
partial present — it only rewrites the damaged rects of the
currently dequeued buffer. libhybris's dequeue hands back one of N
pool slots whose "previous content" is whatever was rendered last
time *that slot* was current, potentially frames ago. So each pool
slot has a different stale base with current damage layered on top;
cycling through them could produce old-vs-new alternation for a few
frames before all slots converge.

**Fix attempted:** dropped a `user.js` into the test Firefox
profile with

```
user_pref("gfx.webrender.max-partial-present-rects", 0);
user_pref("gfx.webrender.allow-partial-present-buffer-age", false);
user_pref("gfx.webrender.force-partial-present", false);
```

(pref names confirmed against `libxul.so` strings.)

**Result:** no observable change. `user.js` removed from the
device.

## What's still on the table

- The symptom hasn't been reproduced in a controlled capture, so
  there's no concrete signal to triangulate from. Next investigator
  should first get a specific repro (what action, what page, how
  frequently) and record that specific flow rather than random
  browsing.
- Ruled out so far: early-release race, external-sampler staleness
  at the compositor, WebRender partial-present on the client.
- Not yet ruled in or out:
  - SurfaceFlinger / our own EGL back-buffer rotation — we don't
    preserve back-buffer contents and redraw everything fullscreen
    every frame, but check that there's no path that skips drawing
    a surface on a re-render. `collect_surface_draws` pulls from
    `surface_wlegl` / `surface_shm` by live surface lookup; if a
    subsurface entry is momentarily missing between a commit and
    the next render tick, that frame would show the toplevel
    placeholder instead of the subsurface content.
  - Subsurface sync vs desync handling by Smithay. Firefox uses a
    wl_subsurface for WebRender content; if its commit is held
    pending a parent commit (sync mode) while our
    `buffer_commit_pending` flag still triggers a render, the
    subsurface would briefly show its previous buffer.
  - A plain damage-rectangle bug: we pass the whole buffer rect as
    damage to `Frame::render_texture_from_to`, which should be
    correct (damage is in dst-local coords per
    `smithay/src/backend/renderer/gles/mod.rs:2522`), but this is
    worth double-checking on an actual repro.
  - Libhybris's `queueBuffer` path (our patch attaches+commits
    inline from the driver thread). Race between client-side
    `sync_wait` completion and our compositor sampling is in
    principle closed by the fence, but hasn't been verified with
    explicit instrumentation.

## Where the code is now

- `server/compositor/src/render.rs` — `import_wlegl_buffers` /
  `ensure_wlegl_texture`, back to the original "import once, cache
  texture on `WleglBufferData`" form.
- `server/compositor/src/compositor.rs` — `commit` handler just
  records `current_buffer` / `committed_width` / `committed_height`
  and sets `buffer_commit_pending`; Smithay's merge_into does the
  `wl_buffer.release`.
- No pre-commit hook, no early release, no per-commit EGLImage
  recreate.
- `notes/wsi-layer.md` updated to reflect the new release path.

## Suggested next step for whoever picks this up

Get a repro. Ideal setup:

1. Specific page / interaction that reliably flickers (probably
   something with a quick visible state change — a link hover, a
   menu open, a CSS transition).
2. `screenrecord --bit-rate 20000000 --time-limit 5
   /sdcard/flicker.mp4` while triggering it.
3. Extract frames with ffmpeg, diff consecutive frames. Actual
   flicker will show up as non-trivial pixel differences that
   revert (frame N != frame N+1 and frame N ≈ frame N+2). Noise
   alone won't.

If the captured diff shows the oscillation, the content of the
reverted-to frame tells us whether it's stale-buffer-in-pool (old
content from a specific earlier state) or a partial-redraw issue
(mix of new and old). From there one of the unresolved candidates
above becomes the target.
