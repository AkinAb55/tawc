# libhybris AHB texels arrive with alpha=0 — why?

When a libhybris/GTK client renders into a gralloc1 AHB and we sample
the resulting EGLImage through a stock smithay EXTERNAL_OES shader, the
alpha channel reads back as `0`. We work around it in
`compositor/src/render.rs` by force-overwriting alpha to `1` for every
libhybris-origin surface (`SurfaceKind::force_opaque` →
`force_opaque=true` for `BufferOrigin::Hybris`), but the underlying
cause is unclear and worth investigating.

## What we know

- Symptom: without the override, every GTK toplevel running under the
  libhybris backend renders fully transparent (the compositor
  background `tawc_window_bg` shows through).
- The override is the long-standing fix and is what the previous
  `wlegl_opaque_shader` was built around.
- The same shader is now used **only** for libhybris-origin AHBs.
  Gfxstream-Vulkan AHBs go through `force_opaque=false` and render
  correctly with the texture's actual alpha, so the issue is specific
  to the libhybris path.
- SHM clients aren't affected — smithay's stock shader handles
  RGBA-vs-XRGB SHM formats correctly via the `NO_ALPHA` define driven
  off the wl_shm format.

## Hypotheses (none verified)

1. GTK genuinely never writes the alpha channel because it considers
   its toplevel opaque, and the gralloc buffer's alpha bits are
   left uninitialized at `0`. If true, this is a documentation
   problem — the override is correct and the only question is why
   modern GTK doesn't request an XRGB-style format.
2. libhybris's gralloc1 import path is producing an AHB whose EGL
   image binding doesn't preserve alpha (a format mismatch between
   the producer's view and the consumer's view). If true, the override
   is masking a libhybris bug and a Vulkan/GLES client that legitimately
   wanted per-pixel alpha through libhybris would also lose it.
3. The vendor GLES driver inside libhybris writes alpha=0 into the
   AHB on flush for some buffer formats (Adreno-specific quirk?).

## Where to look

- `wlegl.rs::tawc_wlegl_import` — the gralloc1 buffer reconstruction
  and its `format`/`usage` arguments. Print what GTK is actually asking
  for on the wire.
- `gl_import.rs::AhbTextureImporter::import_ahb` — how we go from AHB
  to EGLImage to GL_TEXTURE_EXTERNAL_OES. A `EGL_IMAGE_PRESERVED` /
  alpha-channel attribute might be the culprit.
- Vendor blob behaviour: render a known-alpha pattern from a non-GTK
  libhybris GLES client (something tiny that explicitly clears to
  `(1, 0, 0, 0.5)`) and observe what the compositor samples back via
  the plain shader (`force_opaque=false`).

## Repro

1. Edit `compositor/src/render.rs`'s `SurfaceKind::force_opaque` to
   return `false` for every variant.
2. Build, install, launch any GTK app under the libhybris backend
   (e.g. `scripts/rootfs-run.sh 'gtk4-demo'`).
3. Observe the GTK window rendering as the dark `tawc_window_bg`
   background — toplevel content is fully transparent.
4. Revert the edit; the window renders normally.

## Why not fix now

The workaround is a one-line uniform pick that's effectively free, the
visible behaviour is correct, and untangling whether this is a
GTK/libhybris/vendor-driver problem needs careful instrumentation. File
this for future investigation rather than blocking on it.
