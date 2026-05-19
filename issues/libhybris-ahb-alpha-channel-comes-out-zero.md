# Verify libhybris AHB alpha

We previously assumed libhybris/GTK AHB alpha could be `0` even when RGB content
was valid, and papered over that by forcing alpha to `1` for every libhybris
surface. That workaround breaks real per-pixel transparency and has been
removed. RGBA buffers now render using sampled alpha; only explicit no-alpha
Android formats are forced opaque.

## What we know

- Wayland's `wl_surface.set_opaque_region` is an optimization hint. A correct
  compositor may ignore it.
- tawc now ignores client opaque regions in its custom AHB draw path. This keeps
  RGBA transparency honest and avoids using an optimization hint as a correctness
  mechanism.
- `compositor/src/wlegl.rs` records Android buffer formats. `RGBX_8888`,
  `RGB_888`, and `RGB_565` are treated as no-alpha formats and drawn opaque.
- We do not currently have proof that libhybris RGBA alpha is corrupt. The old
  Firefox black-screen evidence was mixed with compositor render scheduling and
  surface-state reentry bugs.

## Still Worth Testing

- Add a tiny libhybris GLES client that draws known RGBA pixels, then verify the
  compositor samples/combines alpha correctly.
- If Firefox/GTK opaque content disappears with sampled alpha, isolate whether
  the producer, libhybris gralloc import, AHB metadata, or our EGLImage import is
  corrupting alpha.
- Confirm which GTK apps use explicit no-alpha Android formats versus RGBA.

## Repro

1. Build and install tawc.
2. Launch a GTK/libhybris client with known transparent content.
3. Verify transparent pixels blend with the compositor background and opaque
   pixels remain visible.
