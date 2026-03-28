# Notes

This file contains design, architecture and implementation notes, primarily written by
and for LLM agents.

## GPU Driver Strategy (2026-03-28)

### The Problem

A Wayland compositor on Android needs GPU buffer sharing between clients (Linux programs
in a Termux chroot) and the compositor (Android app). On desktop Linux both sides use
Mesa and dmabufs just work. On Android, the client traditionally uses Mesa Turnip while
the compositor uses the stock proprietary driver -- two completely different driver stacks
that can't share buffers.

The Termux ecosystem has **never achieved zero-copy GPU buffer sharing** between Mesa
Turnip and the stock Android driver. Termux:X11 works around it with CPU readback
(GPU -> CPU -> GPU). See "Termux:X11 Analysis" section below.

### Our Solution: Same Driver on Both Sides via libhybris

Instead of fighting cross-driver buffer sharing, we eliminate it. Both client and
compositor use the **stock Android GPU driver**:

- **Compositor**: Normal Android app. Stock driver natively. Nothing special.
- **Client**: glibc program in Termux chroot. Uses **libhybris** to load the stock
  Android GPU driver's bionic `.so` files. Our custom **WSI layer** implements Wayland
  surface/swapchain support.

Same driver = buffer fds are natively compatible. No cross-driver import needed.

### libhybris Status (verified 2026-03-28)

libhybris is **actively maintained**. Key facts:
- Android 16 support merged March 25, 2026 (PR #609)
- Android 14 AIDL HAL support merged January 2026 (PR #578)
- Active contributors from Sailfish OS ecosystem
- EGL/GLES via libhybris is battle-tested (Sailfish OS, Ubuntu Touch, years of production)
- Vulkan support is newer -- active PRs (#604, #607) for improvements
- Loads bionic `.so` files into glibc processes by reimplementing bionic's linker

### WSI Layer Design

The stock Android GPU driver has `VK_KHR_android_surface` (Vulkan) and
`EGL_PLATFORM_ANDROID` (EGL), but not the Wayland equivalents that Linux apps expect.
Our WSI layer bridges this:

**Vulkan path (implicit layer):**
- Standard Khronos layer mechanism -- zero app changes needed
- Advertises `VK_KHR_wayland_surface` + `VK_KHR_swapchain`
- Intercepts WSI calls, passes through all rendering calls
- Allocates VkImages via stock driver, exports fds via `VK_KHR_external_memory_fd`
- Sends fds to compositor via `zwp_linux_dmabuf_v1` Wayland protocol
- Prior art: ARM's vulkan-wsi-layer does exactly this for non-Mesa drivers

**EGL path (wrapper libEGL.so):**
- Wrapper library, first in `LD_LIBRARY_PATH`
- Uses libhybris to load real stock EGL/GLES
- Intercepts `eglGetPlatformDisplay(WAYLAND)`, `eglCreateWindowSurface(wl_surface)`,
  `eglSwapBuffers` -- implements buffer management + Wayland protocol
- Passes through all other EGL/GL calls to stock driver

### Open Questions for This Strategy

1. **libhybris Vulkan maturity.** EGL/GLES is proven. Vulkan has active PRs but may
   not be fully baked. EGL/GLES covers most Linux apps as fallback.

2. **Stock driver dependencies from chroot.** Need `/vendor/lib64/` bind-mounted.
   Binder access to gralloc needed for buffer allocation -- should work since
   UID/SELinux context is unchanged. Test early.

3. **Buffer fd type.** `VK_KHR_external_memory_fd` produces "opaque fds". Since both
   sides are the same driver, these are interoperable. But verify the compositor can
   actually import fds exported by a different process using the same driver.

4. **Device breadth.** libhybris + stock driver should work beyond just Qualcomm
   (unlike Mesa Turnip). Needs testing on Mali, MediaTek, etc.

---

## Termux:X11 Analysis (2026-03-27)

How Termux:X11 handles GPU buffers today -- both paths involve CPU readback:

**Path 1: `MESA_VK_WSI_DEBUG=sw` (common/default)**
1. Client renders with Mesa Turnip (Vulkan) on GPU
2. Mesa WSI reads frame back to CPU memory
3. Pixels sent to X server via `xcb_put_image()` or MIT-SHM
4. Lorie X server uploads as GL texture on stock driver
5. **GPU -> CPU -> GPU**

**Path 2: DRI3**
1. Turnip exports dmabuf fd via DRI3
2. Server does `mmap(fd, PROT_READ)` -- CPU mmap, not GPU import
3. Pixel data uploaded as GL texture
4. **GPU -> CPU mmap -> GPU**

Nobody in the Termux ecosystem has achieved true zero-copy GPU buffer sharing between
Mesa Turnip and the stock Android driver.

---

## Unix Domain Socket Access (2026-03-27)

Cross-app Unix socket communication is blocked by SELinux on Android 9+.

- SELinux `app_neverallows.te` does not grant `connectto` between `untrusted_app` domains
- Abstract namespace sockets also blocked by SELinux MAC checks
- Filesystem sockets face both DAC (app dirs are 0700) and MAC barriers

**How Termux works around it:** Termux and plugins share `sharedUserId="com.termux"` =
same UID = bypasses both DAC and SELinux.

**Our solution:** `app_process` relay creates socket in Termux's `$XDG_RUNTIME_DIR`,
passes **listening fd** to compositor app via Binder/Intent. SELinux checks apply to
`connect()`/`bind()`, not to `accept()`/`read()`/`write()` on inherited fds.

---

## app_process Relay (2026-03-27)

`app_process` does NOT provide an Android `Context`. The relay cannot call
`bindService()`. Solution: reverse the Binder flow.

The relay creates Binder objects and sends them TO the app by launching an Activity via
direct `IActivityManager.startActivityAsUser()` calls (reflection, bypassing Context).
The TermuxAm and wlroots-android-bridge projects both use this pattern.

Flow:
1. Relay creates `$XDG_RUNTIME_DIR/wayland-0` listening socket
2. Wraps fd in `ParcelFileDescriptor` inside Binder-compatible object
3. Sends to tawc app via `IActivityManager.startActivity()` reflection
4. Tawc Activity extracts fd, passes to compositor via JNI
5. Relay exits

---

## Smithay Integration (2026-03-27)

### API Findings (verified against Smithay 0.7.0)

- **`EGLDisplay::from_raw(display, config_id)`** -- escape hatch accepting pre-initialized
  raw EGL handles. Skips `eglTerminate` on drop. No GBM assumption.
- **`DisplayHandle::insert_client(stream, data)`** -- adds clients from custom sockets.
- **`GlesRenderer`** -- implements `ImportDma`, `ImportDmaWl`, `ImportMem`, etc.
- **`InputBackend`** -- 25 associated types. Use `UnusedEvent` for unsupported categories.
- **`UnusedEvent`** -- uninhabited enum implementing all event traits.
- Features `wayland_frontend` + `renderer_gl` are the minimal set needed.

### Minimal Feature Set
```toml
smithay = {
    version = "0.7",
    default-features = false,
    features = ["wayland_frontend", "renderer_gl"]
}
```
Avoids all Linux-specific backends (DRM, GBM, libinput, udev, libseat).

### wayland-rs: Pure Rust Backend
The `wayland-backend` crate defaults to a pure Rust Wayland protocol implementation.
Do NOT enable `server_system` feature. No `libwayland-server.so` needed. Proven by
the EWC compositor project.

### Native Dependencies
| Dependency | Status |
|---|---|
| EGL/GLESv2 | Provided by Android NDK |
| libxkbcommon | Must cross-compile for aarch64-linux-android |
| libwayland | Not needed (pure Rust backend) |
| libdrm/libgbm | Not needed (disabled features) |

---

## EGL Context and Surfaces (2026-03-27)

- An EGL context CAN move between threads (release on old, bind on new), but expensive
- One thread can render to multiple EGLSurfaces via `eglMakeCurrent` switches
- Each switch flushes the pipeline -- overhead per switch
- Recommended: single render thread, one context, switch surfaces per window
- `ASurfaceTransaction` + AHB avoids `eglMakeCurrent` overhead entirely (future opt)

---

## Multiple Activities (2026-03-27)

- All Activities in one app share the same process (single heap, static state, threads)
- One SurfaceView per Activity avoids Z-ordering issues
- Single background render thread maintains list of active surfaces
- Activity launch creates visual transitions -- suppress with
  `overridePendingTransition(0, 0)`
- Activities may be killed under memory pressure -- handle surface loss gracefully

---

## DRM <-> AHardwareBuffer Format Mapping

DRM fourcc = MSB-to-LSB channel order. AHB = memory byte order. On little-endian:

| DRM Format | AHB Format |
|---|---|
| `DRM_FORMAT_ABGR8888` | `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` |
| `DRM_FORMAT_XBGR8888` | `AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM` |
| `DRM_FORMAT_RGB565` | `AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM` |

---

## AHardwareBuffer EGL Import

`eglGetNativeClientBufferANDROID` is a **driver extension**, not NDK API-level-gated.
Must query at runtime via `eglQueryString` + `eglGetProcAddress`. Widely available on
Android 8+ but not guaranteed. Always check:
1. `eglQueryString(display, EGL_EXTENSIONS)` for `"EGL_ANDROID_get_native_client_buffer"`
2. `eglGetProcAddress("eglGetNativeClientBufferANDROID")` -- verify non-NULL

---

## ASurfaceTransaction (2026-03-27)

`ASurfaceTransaction_setBuffer` has accepted `AHardwareBuffer` since API 29 (Android 10).
The "API 34 claim" from older notes was incorrect. Signature:
```c
void ASurfaceTransaction_setBuffer(
    ASurfaceTransaction *transaction,
    ASurfaceControl *surface_control,
    AHardwareBuffer *buffer,
    int acquire_fence_fd
);
```

---

## Frame Scheduling: AChoreographer (2026-03-27)

- API 29: `AChoreographer_postFrameCallback64()` -- use this
- API 30: `AChoreographer_registerRefreshRateCallback()` -- refresh rate changes
- API 33: `AChoreographer_postVsyncCallback()` -- multi-timeline frame pacing
- Requires thread with `ALooper` -- compositor thread must call `ALooper_prepare()`

---

## Freeform Windowing (2026-03-27)

The "one Activity per toplevel" design gives full multi-window only with freeform:
- Samsung DeX
- ChromeOS
- Android 15+ desktop mode
- Some custom ROMs

On standard phones: each Activity is fullscreen, switch via recents.

---

## Phantom Process Killer (Android 12+)

Android 12 introduced `PhantomProcessKiller` -- kills child processes of apps when >32
exist. Mitigations:
- Android 12-13: `adb shell device_config put activity_manager max_phantom_processes 2147483647`
- Android 14+: Developer Options "Disable child process restrictions"

Our relay exits after fd handoff so only needs to survive briefly.
