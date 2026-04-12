# Migrate from custom WSI to libhybris Wayland EGL platform

## Summary

Replace our custom `tawc-egl.c` WSI layer and `tawc_buffer_v1` protocol with libhybris's built-in Wayland EGL platform (`eglplatform_wayland.so`) and the standard `android_wlegl` buffer sharing protocol. This eliminates ~1500 lines of custom EGL wrapping, the GL shim libraries, the `LD_LIBRARY_PATH` overrides, and our custom Wayland protocol -- replacing all of it with upstream-maintained libhybris code.

This also solves [libglvnd-egl-vendor.md](libglvnd-egl-vendor.md) as a side effect: libhybris's glvnd support (`--enable-glvnd`) installs as a proper EGL vendor, eliminating the GL shim architecture entirely.

## Background

**Current architecture (tawc-egl.c):**
- Custom `libEGL.so` replacement intercepts all EGL calls
- Allocates `AHardwareBuffer` pools, redirects rendering to FBO-backed AHB textures
- Sends AHBs to compositor via Unix socket side-channel + custom `tawc_buffer_v1` Wayland protocol
- GL shim libraries (`libGL.so`, `libGLESv2.so.2`) with patchelf soname hacks
- Everything loaded via `LD_LIBRARY_PATH=/tmp/tawc-wsi`

**Target architecture (libhybris Wayland platform):**
- libhybris's `eglplatform_wayland.so` plugin handles all EGL/Wayland integration
- Allocates gralloc buffers, sends native handles via `android_wlegl` Wayland protocol
- Compositor reconstructs handles into `AHardwareBuffer` via `AHardwareBuffer_createFromHandle` (API 31+)
- libglvnd dispatches EGL calls to `libEGL_libhybris.so` vendor -- no shims needed
- `HYBRIS_EGLPLATFORM=wayland` selects the platform plugin

## What libhybris's Wayland platform gives us for free

Things we currently implement in tawc-egl.c that libhybris already handles:
- Full `ANativeWindow` implementation with proper buffer lifecycle
- Triple buffering with frame callback vsync (we only have double buffering, no vsync)
- Damage region forwarding to compositor (ours is stubbed)
- Proper `wl_egl_window` integration with resize callbacks
- Buffer format/usage negotiation
- Swap interval control
- `EGL_WL_create_wayland_buffer_from_image` extension
- `EGL_EXT_swap_buffers_with_damage` (real implementation, not stub)

## Prerequisites

- **CFI workaround in libhybris:** ✅ Done — the `__cfi_slowpath` patch lives in `hybris/common/q/cfi_bypass.c` in our libhybris fork. tawc-egl.c no longer needs it. See `libhybris/TAWC_FORK.md`.

## Plan

### Phase 1: Build libhybris with Wayland + glvnd support

Update `client/build-libhybris` configure flags:

```
./configure \
    --enable-arch=arm64 \
    --enable-wayland \
    --disable-wayland_serverside_buffers \
    --enable-glvnd \
    --enable-adreno-quirks \
    --enable-property-cache \
    --with-default-hybris-ld-library-path=/vendor/lib64/egl:/vendor/lib64/hw:/vendor/lib64:/system/lib64 \
    --prefix=/usr/local
```

New flags:
- `--enable-wayland`: builds the Wayland EGL platform plugin and links against wayland-client/wayland-egl
- `--disable-wayland_serverside_buffers`: use client-side buffer allocation (simpler -- compositor doesn't need to allocate buffers or extract native handles from AHBs)
- `--enable-glvnd`: builds as `libEGL_libhybris.so` vendor with JSON ICD, requires `libglvnd` dev package in chroot

New chroot package deps: `libglvnd` (provides `glvnd/libeglabi.h` and the dispatch libraries).

The build script's per-directory build order needs updating to include the wayland platform directory.

### Phase 2: Implement `android_wlegl` protocol in the compositor (Rust + C helper)

The compositor needs to implement the server side of `android_wlegl` (defined in `libhybris/hybris/platforms/common/wayland-android.xml`). This replaces the current `tawc_buffer_v1` protocol handling.

The tricky part is buffer import: reconstructing a `native_handle_t`, registering it with gralloc, and constructing an `ANativeWindowBuffer` with the correct binary layout (magic numbers, version, refcount function pointers). This struct layout varies across Android versions and getting it wrong causes silent failures or crashes.

**Use libhybris's server_wlegl as a C helper library** rather than reimplementing buffer import in Rust. The code already exists (~450 lines in `hybris/platforms/common/server_wlegl*.cpp` + `windowbuffer.h`) and handles all the layout details:

- `server_wlegl_handle` reconstructs `native_handle_t` from protocol fds/ints
- `server_wlegl_buffer` calls `hybris_gralloc_import_buffer()` and constructs a `RemoteWindowBuffer` (correct `ANativeWindowBuffer` subclass with proper refcounting)
- `RemoteWindowBuffer` is the `EGLClientBuffer` we pass to `eglCreateImageKHR`

**Architecture split:**

- **Wayland protocol dispatch (Rust):** Register the `android_wlegl` global via Smithay, handle `create_handle`/`add_fd`/`create_buffer` requests, manage `wl_buffer` lifecycle and release events. This integrates with Smithay's event loop and surface state naturally.
- **Buffer import (C helper):** Thin C wrapper around libhybris's gralloc + `RemoteWindowBuffer`. Rust calls into it via FFI with the raw fds/ints/metadata, gets back an opaque `EGLClientBuffer` pointer. Something like:

```c
// C helper API (linked against libhybris-platformcommon)
typedef struct wlegl_buffer wlegl_buffer;

// Import a client-allocated gralloc buffer from its native_handle components.
// fds/ints are the raw data from the android_wlegl protocol.
// Returns NULL on failure.
wlegl_buffer *wlegl_buffer_import(
    int32_t width, int32_t height, int32_t stride,
    int32_t format, int32_t usage,
    const int *fds, int num_fds,
    const int *ints, int num_ints);

// Get the EGLClientBuffer (ANativeWindowBuffer*) for EGL import.
void *wlegl_buffer_get_egl_client_buffer(wlegl_buffer *buf);

// Release (decrements refcount, frees when zero).
void wlegl_buffer_release(wlegl_buffer *buf);
```

This is a small .c file (maybe 50-100 lines) that wraps the existing libhybris code. It links against `libhybris-platformcommon` (which provides `hybris_gralloc_import_buffer` and the `RemoteWindowBuffer` class) and is compiled as part of the compositor's native build.

**Protocol operations (Rust side):**

1. **`android_wlegl` global** (version 2): register via Smithay's `wayland_server`
2. **`create_handle(id, num_fds, ints[])`**: store the ints array, prepare to receive fds
3. **`android_wlegl_handle.add_fd(fd)`**: accumulate fds (called `num_fds` times)
4. **`create_buffer(id, width, height, stride, format, usage, handle)`**:
   - Call into C helper with accumulated fds/ints + buffer metadata
   - Get back `EGLClientBuffer` pointer
   - Import to EGLImage -> GL texture (existing `gl_import.rs` path, minor adaptation)
   - Create `wl_buffer` resource with release semantics

Server-side allocation (`get_server_buffer_handle`) is NOT needed -- we use client-side allocation mode.

**Buffer import path:**

```
native_handle_t (from protocol fds + ints)
  -> C helper: hybris_gralloc_import_buffer() [registers with GPU driver]
    -> C helper: RemoteWindowBuffer construction [correct ANativeWindowBuffer layout]
      -> Rust: eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID, anwb) [existing gl_import.rs]
        -> Rust: glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES) [existing]
```

This is exactly how every existing libhybris compositor (Lipstick/SailfishOS, etc.) imports buffers. The proven path, with no struct layout guesswork.

**Build considerations:**

The C helper links against `libhybris-platformcommon.so`, which is installed in the chroot at `/usr/local/lib`. But the compositor runs on the Android side, not in the chroot. Options:
- Build the C helper inside the chroot (where libhybris headers/libs are available), produce a .so, and load it from the compositor at runtime via `dlopen`
- Or cross-compile it as part of the compositor's Android NDK build, pointing at libhybris headers. This is trickier since libhybris is built for glibc, not bionic.

The `dlopen` approach is more natural: the helper runs in the same process as the compositor but loads `libhybris-platformcommon` (which itself loads bionic/vendor libs via its hooks). This is the same process-level mixing that already works for the client side. Need to verify there aren't issues with having both bionic (compositor) and glibc (libhybris-platformcommon) in the same process on the server side.

**Wait -- important architectural question:** libhybris is designed to let glibc programs call bionic libraries. The compositor is a *bionic* program (Android app). Loading `libhybris-platformcommon.so` (a glibc library) into a bionic process is the reverse direction and won't work directly. Two options:

1. **Run the C helper as a separate process/service** that the compositor talks to over a socket or pipe. The helper runs in the chroot (glibc), receives fd/int data, does the gralloc import, and sends back... hmm, this doesn't work either because the `ANativeWindowBuffer` pointer needs to be in the compositor's address space for `eglCreateImageKHR`.

2. **Do the gralloc import directly from bionic** without libhybris. The compositor is already a native Android app with access to the gralloc HAL. Call `hw_get_module(GRALLOC_HARDWARE_MODULE_ID)` to get the gralloc module, then `gralloc->registerBuffer(handle)` to import the native handle. Construct the `ANativeWindowBuffer` in C (not C++) with the correct layout for the device's Android version. This bypasses libhybris entirely on the server side.

Option 2 is the right approach. The C helper doesn't wrap libhybris -- it calls the Android gralloc HAL directly, same as any native Android compositor would. This means:
- The helper is compiled with the Android NDK as part of the compositor build
- It uses `<hardware/gralloc.h>` and `<system/window.h>` from the NDK/platform headers
- `ANativeWindowBuffer` layout comes from the platform headers, which are correct for the target device by definition
- No glibc/bionic mixing on the server side

```c
// C helper (compiled with Android NDK, linked into compositor .so)
#include <hardware/gralloc.h>
#include <system/window.h>
#include <cutils/native_handle.h>

static gralloc_module_t *gralloc_module = NULL;

int wlegl_init(void) {
    return hw_get_module(GRALLOC_HARDWARE_MODULE_ID,
                         (const hw_module_t **)&gralloc_module);
}

// ... import buffer, construct ANativeWindowBuffer, return as EGLClientBuffer ...
```

The `ANativeWindowBuffer` struct from `<system/window.h>` is the *real* definition -- no guesswork about layout needed. libhybris's `RemoteWindowBuffer` is a copy of this struct for use outside Android; since we're *on* Android, we use the real thing.

### Phase 3: Environment setup changes

Update `client/arch-chroot-run` profile:

```bash
# Before (custom WSI):
export LD_LIBRARY_PATH=/tmp/tawc-wsi:/usr/local/lib
export HYBRIS_EGLPLATFORM=null  # (or unset)

# After (libhybris Wayland platform):
export HYBRIS_EGLPLATFORM=wayland
# LD_LIBRARY_PATH may still be needed for /usr/local/lib (libhybris install prefix)
# but /tmp/tawc-wsi is removed entirely
```

Install libglvnd JSON ICD file (libhybris build should handle this, but verify):
`/usr/share/glvnd/egl_vendor.d/10_libhybris.json` -> `libEGL_libhybris.so.0`

### Phase 4: Remove dead code

- Delete `client/tawc-wsi/tawc-egl.c`
- Delete `client/tawc-wsi/libgl-shim.c`
- Delete `client/tawc-wsi/libglesv2-shim.c`
- Delete `client/tawc-wsi/build`
- Delete `server/compositor/protocols/tawc_buffer_v1.xml`
- Remove `tawc_buffer_v1` protocol code from compositor (`protocol.rs`, `compositor.rs`, `render.rs`)
- Remove AHB side-channel socket code from compositor
- Update `notes/wsi-layer.md` to reflect the new architecture
- Delete `issues/libglvnd-egl-vendor.md` (solved)

## Unknowns

### 1. Gralloc HAL availability
The C helper calls `hw_get_module(GRALLOC_HARDWARE_MODULE_ID)` to load gralloc0. Modern Android has moved to HIDL/AIDL HALs, but most devices still provide a gralloc0 compatibility shim. Needs verification on the target device. If gralloc0 is gone, alternatives: use `GraphicBuffer` from `libui.so` (C++ but stable on-device), or call the mapper HAL directly.

### 2. Platform headers in NDK build
The C helper needs `<hardware/gralloc.h>`, `<system/window.h>`, and `<cutils/native_handle.h>`. These aren't in the public NDK -- they're platform/vendor headers. The compositor build may need to pull these from the device or from the android-headers package. We already use android-headers in the chroot for libhybris; may need them on the host build side too, or compile the helper in the chroot and load it at runtime.

### 3. libglvnd + libhybris interaction on Arch Linux ARM
libhybris's glvnd support was developed for Ubuntu Touch. It should work on Arch, but the package layout differs. Need to verify:
- `glvnd/libeglabi.h` is available (likely in `libglvnd` package)
- JSON ICD file gets installed to a path that libglvnd scans
- libglvnd's `libEGL.so` correctly dispatches to `libEGL_libhybris.so`
- `libGLESv2.so` and `libGL.so` dispatch works without our shims (libglvnd handles this natively)

### 4. EGL context attribute filtering
tawc-egl.c strips desktop-GL-only context attributes (`EGL_CONTEXT_OPENGL_PROFILE_MASK`, `EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE`) and converts `EGL_CONTEXT_MAJOR_VERSION_KHR` to `EGL_CONTEXT_CLIENT_VERSION`. libhybris's EGL may or may not handle these gracefully. GTK3 with `GDK_GL=gles:always` probably doesn't hit this path, but Firefox's `glxtest` might. Needs testing.

### 5. `eglBindAPI(EGL_OPENGL_API)` rejection
tawc-egl.c explicitly returns `EGL_FALSE` for desktop GL bind requests. libhybris may do this already (Android has no desktop GL), but need to verify that apps like GTK3 get the right signal to fall back to GLES.

### 6. Server-side buffer allocation (future optimization)
We start with client-side allocation (`--disable-wayland_serverside_buffers`) because it's simpler. Server-side allocation (the default in libhybris) is more efficient but requires the compositor to:
- Allocate gralloc buffers on behalf of clients
- Extract the `native_handle_t` fds/ints to send back via protocol events
- This means opening the gralloc allocator HAL (not just the mapper) from the compositor side

This can be revisited later if client-side allocation works well.

### 7. wayland-server dependency for libhybris build
`--enable-wayland` requires `wayland-server` pkg-config in the chroot build environment. The server_wlegl code in `libhybris/hybris/platforms/common/` (which implements the compositor-side of android_wlegl) gets compiled into `libhybris-platformcommon`. We don't use the server_wlegl code (our compositor is Rust), but it still needs to compile. Should be fine as long as `wayland` package is installed, but worth noting.

## Testing plan

1. Build libhybris with new flags, verify `eglplatform_wayland.so` and `libEGL_libhybris.so` are produced
2. Verify libglvnd dispatch: `EGL_LOG_LEVEL=debug` should show vendor loading
3. Test GTK3 debug app with `GDK_GL=gles:always HYBRIS_EGLPLATFORM=wayland`
4. Test Firefox with the standard launch command
5. Run integration tests
6. Check for regressions: damage, resize, multi-surface, subsurfaces
7. Performance comparison: frame timing with triple-buffer + vsync vs old double-buffer + glFinish
