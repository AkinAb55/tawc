# Migrate from GL library shims to libglvnd EGL vendor

## Problem

We currently override system GL libraries (`libGL.so`, `libGLESv2.so.2`) with shim libraries in `/tmp/tawc-wsi/` via `LD_LIBRARY_PATH`. These shims re-export libhybris GLES symbols and add GLX stubs returning NULL. This works but fights against libglvnd (the system's GL dispatch layer) rather than working with it.

The shim approach has several downsides:
- Fragile: depends on `LD_LIBRARY_PATH` ordering, soname patching via patchelf, and DT_NEEDED chains to `libGLESv2_real.so`
- Semantically wrong: we provide a `libGL.so` on a system with no desktop GL support
- Requires rebuilding shims whenever libhybris GLES is updated
- Confusing for anyone trying to understand the GL library layout

## Intended architecture

libglvnd is designed for exactly this situation. The proper setup:

1. **System GL libraries stay untouched.** libglvnd's `libGL.so`, `libGLESv2.so`, `libEGL.so` at `/usr/lib/` handle dispatch.
2. **EGL vendor registered via JSON ICD file** in `/usr/share/glvnd/egl_vendor.d/`. Points to our EGL implementation.
3. **libglvnd loads our vendor** and routes `eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND)` to it.
4. **No `LD_LIBRARY_PATH` overrides** for GL libraries.
5. **GLX calls fail gracefully** through libglvnd's own dispatch (no GLX vendor installed = no GLX, apps fall back to EGL).
6. **`libGLESv2.so` dispatch works automatically** through libglvnd's dispatch tables once an EGL context is current.

## libhybris already supports this

`libhybris/hybris/egl/glvnd/eglglvnd.cpp` implements the `__egl_Main` entry point that libglvnd expects. When built with glvnd support, libhybris installs as `libEGL_hybris.so` with a JSON ICD file. The vendor advertises `EGL_EXT_platform_wayland` and `EGL_KHR_platform_android`.

## Design question: where does tawc-egl fit?

Currently tawc-egl is a `libEGL.so` replacement that intercepts EGL calls to add Wayland AHB buffer sharing. With libglvnd, two options:

**Option A: tawc-egl becomes the libglvnd vendor.** It implements `__egl_Main` and wraps libhybris internally. This keeps the current interception approach but packages it as a proper vendor.

**Option B: libhybris is the vendor, tawc-egl wraps differently.** libhybris's glvnd support handles EGL dispatch. tawc-egl intercepts at a different layer (e.g., Wayland protocol side only, or as a second vendor that delegates to libhybris).

Option A is probably simpler since tawc-egl already wraps all the EGL calls we need.

## Steps

1. Build libhybris with glvnd support (check configure flags)
2. Implement `__egl_Main` in tawc-egl (use libhybris's `eglglvnd.cpp` as reference)
3. Install JSON ICD file: `/usr/share/glvnd/egl_vendor.d/10_tawc.json` → `libEGL_tawc.so`
4. Remove GL shim libraries (`libGL.so`, `libGLESv2.so.2`, `libGLESv2_real.so`, `libGL.so.1`)
5. Remove `LD_LIBRARY_PATH` entries for GL libraries (keep for `libEGL_tawc.so` if not installed system-wide)
6. Verify Firefox works with `GDK_GL=gles:always` and zero SHM buffers
7. Verify GTK debug app works with `GDK_GL=gles:always`
8. Check that `glvnd/libeglabi.h` is available in the chroot (may need a dev package)

## References

- [libglvnd](https://github.com/NVIDIA/libglvnd) -- GL Vendor-Neutral Dispatch
- [EGL ICD enumeration](https://github.com/NVIDIA/libglvnd/blob/master/src/EGL/icd_enumeration.md)
- libhybris glvnd impl: `libhybris/hybris/egl/glvnd/eglglvnd.cpp`
- Current shim code: `client/tawc-wsi/libgl-shim.c`, `client/tawc-wsi/libglesv2-shim.c`
