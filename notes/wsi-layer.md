# Client-Side EGL WSI Layer

Drop-in `libEGL.so` wrapper that makes unmodified Wayland EGL apps work with tawc.
Located at `client/tawc-wsi/tawc-egl.c`, placed first in `LD_LIBRARY_PATH`.

## Design

Standard Linux apps expect `eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND, ...)`. The stock
Android driver has `EGL_PLATFORM_ANDROID` instead. Our wrapper bridges this gap.

**Intercepted EGL calls (~8 functions):**
- `eglGetDisplay` / `eglGetPlatformDisplay` -- detect Wayland display, bind tawc protocol,
  load stock EGL via libhybris
- `eglCreateWindowSurface` / `eglCreatePlatformWindowSurface` -- allocate AHB pool
  (double-buffered), create EGLImage->texture->FBO per buffer, get tawc side channel
- `eglSwapBuffers` / `eglSwapBuffersWithDamageEXT` -- `glFinish()`, send AHB via side
  channel, call `tawc_ahb_channel_v1.attach` + `wl_surface.commit`, rotate buffers
- `eglChooseConfig` -- rewrite/OR in `EGL_PBUFFER_BIT`
- `eglMakeCurrent` -- bind FBO if tawc_surface
- `eglQuerySurface` -- handle width/height/buffer_age
- Everything else -- passed through to stock driver

**Key design decisions:**
- Non-blocking protocol binding in `eglGetDisplay` -- apps call this from within
  `wl_display_dispatch` callbacks, so a blocking roundtrip deadlocks
- Lazy AHB function loading via `android_dlopen` for libnativewindow.so
- Real 1x1 pbuffer surface for `eglMakeCurrent`, then FBO redirect to AHB-backed renderbuffer
- Depth/stencil renderbuffer attached to FBO for 3D apps

## Implementation Details (Phase 4)

All 44 EGL 1.5 core functions exported. X-macro (`REAL_EGL_FUNCTIONS`) declares all
`real_*` function pointers. Functions split into LOAD_REQUIRED (EGL 1.0-1.4) and
LOAD_OPTIONAL (EGL 1.5, may not exist in libhybris).

**Thread safety:**
- `pthread_once` for init, `pthread_mutex_t` for surface list
- `__thread` for per-thread current surface + context tracking
- Per-surface `current` index only written by `eglSwapBuffers` (no lock needed)

**Extension handling:**
- `eglQueryString(EGL_EXTENSIONS)` returns real driver extensions + appended Wayland
  extensions (`EGL_KHR_platform_wayland`, `EGL_EXT_buffer_age`, etc.)
- `eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS)` returns client extensions for libepoxy
- `eglGetProcAddress` returns intercepted functions for our exports AND forwards unknown
  names to real driver

**Buffer age:** Returns `NUM_BUFFERS` after first N swaps, 0 before (per EGL_EXT_buffer_age spec).

**Resize:** `eglSwapBuffers` checks `wl_egl_window->width/height` against current size.
If changed, frees entire AHB pool and reallocates at new dimensions (including depth/stencil).

**Surface slots:** Use `in_use` flag for reuse after `eglDestroySurface`.

## Fixes Discovered During Testing

- `eglQueryContext` -- libepoxy resolves via dlsym, crashes if missing
- `eglBindAPI(EGL_OPENGL_API)` -- returns EGL_FALSE (Android has no desktop GL)
- `eglCreateContext` attribute filtering -- strips desktop-GL-only attributes, converts
  `EGL_CONTEXT_MAJOR_VERSION_KHR` to `EGL_CONTEXT_CLIENT_VERSION` for GLES
- `eglGetConfigAttrib(EGL_SURFACE_TYPE)` -- ORs in `EGL_WINDOW_BIT`
- libhybris-common execstack -- cleared via `patchelf --clear-execstack`

## GTK3 Compatibility

GTK3 uses libepoxy for EGL/GL dispatch. Libepoxy resolves EGL functions via `dlsym` on
our `libEGL.so`, falling back to `eglGetProcAddress`. Every missing export is a crash.

**GTK3 GLES env var:** `GDK_GL=gles:always` forces GLES path (not desktop GL). GTK3 calls
`eglBindAPI(EGL_OPENGL_ES_API)` directly and compiles GLES shaders. The GLES-aware codepath
was fixed in GTK 3.24.35; our chroot has 3.24.52.

**GLX stub requirement:** libepoxy probes `dlsym(libGLESv2_handle, "glXGetCurrentContext")`
to detect GLX vs EGL contexts. On GLES-only systems without desktop GL, this symbol is
missing, and libepoxy's error handling calls `abort()`. The `libGLESv2.so.2` and `libGL.so`
shims (built by `client/tawc-wsi/build`) export GLX stubs returning NULL to satisfy this
probe. See `notes/firefox.md` for the full GL library shim architecture.

## Vulkan WSI Layer (Stretch Goal)

Standard Khronos implicit layer mechanism. Would advertise `VK_KHR_wayland_surface` +
`VK_KHR_swapchain`, allocate swapchain images backed by AHBs via
`VK_ANDROID_external_memory_android_hardware_buffer`. Same side-channel mechanism as EGL.
Depends on libhybris Vulkan maturity (varies by GPU vendor).
