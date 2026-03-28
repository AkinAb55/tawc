# Wayland Compositor for Android via Smithay

## Goal

A Rust/Kotlin Android app that runs a Wayland compositor inside an Android Activity.
Linux Wayland clients (running in Termux/proot/chroot) connect via a Unix domain socket
and get GPU-accelerated rendering onto Android Surfaces. All buffer handling stays on the
GPU -- zero-copy between client and compositor because both sides use the same stock
Android GPU driver.

**Key insight:** Clients use **libhybris** to load the stock Android GPU driver (e.g.
Qualcomm Adreno) into their glibc environment. A custom **Wayland WSI layer** (Vulkan
implicit layer + EGL wrapper library) bridges the gap between the stock driver and
Wayland protocol. The compositor is a normal Android app using the stock driver natively.
Same driver on both sides = buffer sharing just works.

---

## Prior Art

### wlroots-android-bridge
[Xtr126/wlroots-android-bridge](https://github.com/Xtr126/wlroots-android-bridge) --
wlroots/labwc compositor on Android. Key design decisions we borrow:
- **One Android Activity per Wayland toplevel** -- Android's window manager handles
  task switching, recents, window positioning
- **ASurfaceTransaction for presentation** -- submit rendered buffers to SurfaceFlinger

Why it doesn't solve our problem: depends on Mesa + minigbm, only works on Intel/x86.
Different approach to GPU drivers entirely.

### Termux:X11
Termux:X11 is the current state of the art for graphical Linux apps on Android. It works
but **all paths involve CPU readback** -- no zero-copy GPU buffer sharing between Mesa
Turnip and the stock Android driver. See notes.md for detailed analysis.

### libhybris
[libhybris/libhybris](https://github.com/libhybris/libhybris) -- compatibility layer
allowing glibc programs to load bionic-linked Android shared libraries. Used by Sailfish
OS and Ubuntu Touch to run stock Android GPU drivers on glibc-based Linux. **Actively
maintained** -- Android 16 support merged March 2026. This is what enables our
architecture.

### ARM vulkan-wsi-layer
[ArmSoM/vulkan-wsi-layer](https://github.com/ArmSoM/vulkan-wsi-layer) -- open-source
Vulkan layer implementing Wayland/X11 WSI independently of the GPU driver. Prior art for
our Vulkan WSI layer approach.

---

## Architecture

### The GPU Driver Strategy

The fundamental problem in running a Wayland compositor on Android is GPU buffer sharing.
Wayland clients and the compositor must share GPU buffers zero-copy. On desktop Linux
this is trivial (both use Mesa, dmabufs). On Android there are two different GPU driver
stacks (Mesa in the chroot, stock proprietary driver in the Android app) that can't share
buffers.

**Our solution: eliminate the driver mismatch.** Both sides use the stock Android GPU
driver:

- **Compositor** (Android app): Uses the stock driver natively. Nothing special.
- **Clients** (glibc programs in Termux chroot): Use **libhybris** to load the stock
  Android GPU driver's bionic `.so` files into the glibc process. A custom **WSI layer**
  implements Wayland surface/swapchain support on top of the stock driver's Vulkan/EGL.

Same driver on both sides means buffer fds are natively compatible. No cross-driver
import hacks, no AHB wrapping tricks, no CPU readback.

### How libhybris Fits In

libhybris reimplements Android's bionic linker so glibc processes can load bionic `.so`
files. It hooks bionic libc calls (pthread, malloc, etc.) and redirects to glibc
equivalents. The loading chain in a client:

```
App (glibc-linked)
  -> dlopen("libEGL.so")  -- finds OUR wrapper (glibc-linked, in LD_LIBRARY_PATH)
    -> our wrapper calls libhybris
      -> libhybris loads /vendor/lib64/egl/libEGL_adreno.so (bionic-linked)
      -> libhybris loads /vendor/lib64/egl/libGLESv2_adreno.so (bionic-linked)
```

For Vulkan, the implicit layer loads `libvulkan_adreno.so` via libhybris similarly.

The stock driver's dependencies (vendor libs in `/vendor/lib64/`) and kernel interface
(`/dev/kgsl-3d0`) are all accessible from the chroot. The process UID and SELinux
context are unchanged, so GPU access and Binder calls to gralloc work as they would
from any Android app.

### The WSI Layer

Standard Linux apps expect `VK_KHR_wayland_surface` (Vulkan) or
`eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND, ...)` (EGL). The stock Android driver
doesn't have these -- it has `VK_KHR_android_surface` / `EGL_PLATFORM_ANDROID`. Our
WSI layer bridges this gap.

**Vulkan: Implicit layer** (clean, standard Khronos mechanism)
- Advertises `VK_KHR_wayland_surface` + `VK_KHR_swapchain` in extension enumeration
- Intercepts `vkCreateWaylandSurfaceKHR`, `vkCreateSwapchainKHR`,
  `vkAcquireNextImageKHR`, `vkQueuePresentKHR`
- Implements swapchain by allocating VkImages via the stock driver, exporting as fds
  via `VK_KHR_external_memory_fd`, sending to compositor via `zwp_linux_dmabuf_v1`
- Passes through all rendering calls untouched
- Activated via env var (`VK_INSTANCE_LAYERS`) or implicit layer manifest -- zero app
  changes needed

**EGL/GLES: Wrapper `libEGL.so`** (covers GTK3, Qt, SDL, most Linux apps)
- Named `libEGL.so`, placed first in `LD_LIBRARY_PATH`
- Intercepts `eglGetPlatformDisplay(WAYLAND)` -- creates surfaceless/pbuffer display on
  stock driver, stores `wl_display` for protocol work
- Intercepts `eglCreateWindowSurface` -- wraps `wl_surface` in our buffer management
- Intercepts `eglSwapBuffers` -- does present (send buffer to compositor via Wayland)
- Passes through everything else (all GL calls, `eglMakeCurrent`, etc.) to stock driver
- Apps load it without modification

### System Diagram

```
+------------------------ Android App Process -------------------------+
|                                                                       |
|  Kotlin / UI Thread                                                   |
|  +------------------------------------------------------------------+ |
|  |  MainActivity                                                     | |
|  |    +-- manages SurfaceViewActivity x N (one per window)           | |
|  |         +-- SurfaceView -> ANativeWindow --+                      | |
|  |         +-- onTouchEvent / onKeyEvent ----+|                      | |
|  +------------------------------------------+|----------------------+ |
|                                    JNI calls ||                       |
|  Compositor Thread (Rust)                    ||                       |
|  +-------------------------------------------vv---------------------+ |
|  |  smithay-android                                                  | |
|  |                                                                   | |
|  |  +-- GlesRenderer (stock Smithay, stock GLES driver)              | |
|  |  +-- AndroidEglBackend (EGL on ANativeWindow)                     | |
|  |  +-- AndroidInputBackend (events from Kotlin via channel)         | |
|  |  +-- buffer import (same driver = trivial EGLImage/texture)       | |
|  |  +-- Wayland state (xdg-shell, wl_seat, wl_output, etc)          | |
|  +-------------------------------------------------------------------+ |
|         |                                                             |
|    Unix domain socket (listening fd received from relay)               |
|         |                                                             |
+---------|-------------------------------------------------------------+
          |
  app_process relay (Termux UID, exits after handoff)
    +-- Creates socket at $XDG_RUNTIME_DIR/wayland-0
    +-- Passes listening fd to app via Intent + Binder
    +-- Exits
          |
  Termux / chroot
    +-- Wayland clients (GTK, Qt, SDL, etc.)
    +-- libhybris loads stock GPU driver (bionic .so in glibc process)
    +-- Our WSI layer (Vulkan implicit layer + EGL wrapper)
    +-- GPU access via /dev/kgsl-3d0 (stock driver, same as compositor)
```

### Buffer Sharing Flow

1. Client allocates GPU buffer via stock driver (through libhybris)
2. Client exports buffer fd via `VK_KHR_external_memory_fd` (Vulkan) or equivalent
3. WSI layer sends fd + metadata to compositor via `zwp_linux_dmabuf_v1` over Wayland
   socket
4. Compositor imports fd -- same driver, so import is straightforward
5. Compositor composites as GL texture via GlesRenderer
6. `eglSwapBuffers()` -> SurfaceFlinger presents

Zero-copy from client to compositor. One GPU composition pass for display.

### Wayland Socket Sharing

Cross-app Unix sockets are blocked by SELinux on Android 9+. Solution: `app_process`
relay launched from Termux.

The relay creates `$XDG_RUNTIME_DIR/wayland-0`, passes the **listening fd** to the
compositor app via Binder (Intent + ParcelFileDescriptor, using the TermuxAm pattern
of direct `IActivityManager` calls). The compositor calls `accept()` on the fd. After
handoff, the relay exits.

This works because SELinux checks apply to `connect()`/`bind()`, not to
`read()`/`write()`/`accept()` on inherited fds. Proven pattern -- used by
wlroots-android-bridge and Termux:X11.

---

## Components

### 1. Kotlin App Shell (`app/`)

Standard Android app. Receives the Wayland listening socket fd from the `app_process`
relay via a Binder object passed through an Intent.

- **`MainActivity`** -- entry point. Loads `libsmithay_android.so` via
  `System.loadLibrary()`. Starts compositor thread via JNI. Listens for callbacks from
  Rust to create/destroy window Activities.
- **`SurfaceViewActivity`** -- one per Wayland toplevel. Uses `SurfaceView` for a
  dedicated SurfaceFlinger layer. On surface ready: calls JNI to give compositor direct
  access to ANativeWindow. Forwards touch/key events to Rust via JNI.
- **`Relay.kt`** -- `app_process` entry point (runs in Termux). Creates Wayland socket,
  passes fd to app, exits.

JNI interface (Kotlin -> Rust):
- `nativeStartCompositor(socketFd: Int)` -- start compositor event loop
- `nativeStopCompositor()`
- `nativeOnSurfaceCreated(windowId: Long, surface: Surface)`
- `nativeOnSurfaceChanged(windowId: Long, width: Int, height: Int)`
- `nativeOnSurfaceDestroyed(windowId: Long)`
- `nativeOnTouchEvent(windowId: Long, action: Int, x: Float, y: Float, pointerId: Int)`
- `nativeOnKeyEvent(windowId: Long, action: Int, keyCode: Int, scanCode: Int)`

JNI callbacks (Rust -> Kotlin):
- `requestNewActivity(windowId: Long, appId: String, title: String)`
- `requestCloseActivity(windowId: Long)`
- `requestResizeActivity(windowId: Long, w: Int, h: Int)`

### 2. Compositor Rust Library (`smithay-android/`)

The core compositor, running as a background thread in the Android app process.

**EGL backend** (`egl.rs`): Initialize EGL from Android Surface. Use
`EGLDisplay::from_raw()` to wrap an Android-created EGL display in Smithay's types,
or implement `EGLNativeDisplay` with `EGL_PLATFORM_ANDROID_KHR`. Create one EGLSurface
per Activity's ANativeWindow.

**Buffer import** (`import.rs`): Since both client and compositor use the same stock
driver, buffer import should be straightforward. The compositor imports client buffer
fds as EGLImages / GL textures via the stock driver's standard import mechanisms. The
exact import path (direct fd import, or via AHardwareBuffer wrapping) depends on what
the stock driver supports -- but same-driver import is a solved problem. Test early.

**Presentation** (`output.rs`): GlesRenderer composites all surfaces for a toplevel
onto the Activity's EGLSurface, then `eglSwapBuffers()`. One EGLSurface per Activity.

**Input** (`input.rs`): Implements Smithay's `InputBackend`. Android touch/key events
arrive via JNI -> crossbeam channel -> Smithay `wl_seat`. AKEYCODE -> Linux KEY_*
mapping + XKB keymap.

**Wayland protocols** (`compositor.rs`): Standard Smithay protocol handling:
- MVP: `wl_compositor`, `zwp_linux_dmabuf_v1`, `xdg_shell`, `wl_seat`, `wl_output`,
  `wp-viewporter`
- Later: `wl_shm`, `xdg-decoration`, `wp-fractional-scale-v1`, `zwp_text_input_v3`,
  `wl_data_device_manager`, Xwayland

### 3. Client WSI Layer (`tawc-wsi/`)

Installed in the Termux chroot. Two components:

**Vulkan implicit layer** (`tawc_wsi_vulkan.so` + manifest JSON):
- Intercepts WSI calls, passes through everything else
- Implements `VK_KHR_wayland_surface` and `VK_KHR_swapchain` on top of
  `VK_KHR_external_memory_fd`
- Talks `zwp_linux_dmabuf_v1` to our compositor
- Activated automatically via implicit layer manifest

**EGL wrapper** (`libEGL.so`):
- Wrapper around stock EGL loaded via libhybris
- Intercepts Wayland platform calls, implements buffer management + present
- Passes through all GL/EGL rendering calls to stock driver
- First in `LD_LIBRARY_PATH` so apps find it before any system libEGL

### 4. app_process Relay (`app/src/.../Relay.kt`)

Lightweight Kotlin class run via `app_process` from Termux. Creates Wayland socket,
hands listening fd to compositor app via Binder, exits. Uses TermuxAm pattern (direct
`IActivityManager` calls via reflection) since `app_process` has no Android Context.

---

## Crate Structure

```
smithay-android/
+-- Cargo.toml
+-- src/
    +-- lib.rs              # JNI entry points
    +-- egl.rs              # Android EGL display/context/surface
    +-- import.rs           # Buffer import (same-driver, straightforward)
    +-- output.rs           # eglSwapBuffers presentation
    +-- input.rs            # AndroidInputBackend
    +-- keymap.rs           # AKEYCODE -> Linux scancode mapping
    +-- compositor.rs       # Wayland state machine
    +-- window.rs           # Per-toplevel state

tawc-wsi/
+-- vulkan-layer/
|   +-- tawc_wsi_layer.c   # Vulkan implicit layer (or Rust)
|   +-- tawc_wsi_layer.json # Layer manifest
+-- egl-wrapper/
    +-- egl_wrapper.c       # EGL wrapper using libhybris (or Rust + FFI)
```

Android app:
```
app/
+-- src/main/
    +-- java/com/tawc/
    |   +-- MainActivity.kt
    |   +-- SurfaceViewActivity.kt
    |   +-- NativeBridge.kt           # JNI extern declarations
    |   +-- RelayReceiver.kt          # Receives fd from relay
    |   +-- Relay.kt                  # app_process entry point
    +-- res/
+-- build.gradle.kts
```

Dependencies:
- `smithay` (`default-features = false`, features: `wayland_frontend`, `renderer_gl`)
  Avoids libdrm, libgbm, libinput, libudev, libseat, X11.
- `ndk` -- AHardwareBuffer, ANativeWindow bindings
- `jni` -- JNI interop
- `crossbeam-channel` -- lock-free MPSC for input events
- `xkbcommon` -- keymap handling (requires cross-compiled libxkbcommon.so)

---

## Build System

- Rust compositor cross-compiled for `aarch64-linux-android` via `cargo-ndk`
- Gradle invokes cargo-ndk, copies `.so` into `jniLibs/arm64-v8a/`
- Target API level: 29 (Android 10) minimum
- libxkbcommon cross-compiled from source with NDK toolchain
- wayland-rs uses pure Rust backend (no libwayland dependency)

Client-side WSI layer:
- Cross-compiled for `aarch64-linux-gnu` (glibc, for Termux chroot)
- Links against libhybris
- Installed in chroot's library path

---

## Implementation Order

### Phase 1: Build Toolchain & EGL Proof
1. Android app scaffold: single Activity with SurfaceView
2. Cross-compile toolchain: cargo-ndk, NDK, cross-compile libxkbcommon
3. Rust JNI library: receive ANativeWindow, create EGL context via Smithay
4. Render solid color to EGLSurface via GlesRenderer + `eglSwapBuffers`
5. **Milestone: GlesRenderer renders to Android Surface**

### Phase 2: libhybris + WSI Proof of Concept
6. Set up libhybris in Termux chroot, verify stock Adreno Vulkan/EGL loads
7. Write minimal Vulkan WSI layer: allocate VkImage, export fd, send over
   socket, verify compositor can import it
8. Write minimal EGL wrapper: surfaceless display via libhybris, render to
   buffer, export, send
9. Test buffer round-trip: client renders with stock driver via libhybris,
   compositor imports and displays
10. **Milestone: zero-copy GPU buffer sharing proven end-to-end**

### Phase 3: Wayland Server + Socket Relay
11. Initialize Smithay Wayland state (Display, compositor, xdg_shell,
    `zwp_linux_dmabuf_v1`, wl_output)
12. Build `app_process` relay for listening socket handoff
13. Test: Termux client connects to `$XDG_RUNTIME_DIR/wayland-0`
14. Handle client buffer commit: import fd -> GL texture -> composite -> present
15. Test with a simple Wayland EGL client from chroot
16. **Milestone: GPU-accelerated Wayland client visible on screen**

### Phase 4: Input
17. Implement AndroidInputBackend (touch + pointer first, keyboard second)
18. Wire Kotlin events -> JNI -> crossbeam channel -> Smithay wl_seat
19. AKEYCODE -> Linux scancode mapping + XKB keymap
20. **Milestone: can interact with a Wayland client**

### Phase 5: Multi-Window
21. JNI callback: compositor notifies Kotlin of new xdg_toplevels
22. MainActivity spawns SurfaceViewActivity per toplevel
23. Each Activity's SurfaceView gets its own EGLSurface
24. Window lifecycle (map, unmap, close, resize)
25. **Milestone: multiple Wayland windows as separate Android Activities**

### Phase 6: Polish & Protocols
26. Frame callbacks (`wl_surface.frame`)
27. Server-side decorations (xdg-decoration)
28. Fractional scaling (wp-fractional-scale) for high-DPI Android screens
29. `wl_shm` support (software rendering fallback)
30. Clipboard bridge (wl_data_device <-> Android ClipboardManager)
31. IME bridge (zwp_text_input_v3 <-> Android InputMethodManager)

---

## Known Risks

| Risk | Mitigation |
|---|---|
| libhybris can't load stock Adreno Vulkan driver from chroot | EGL/GLES path is proven (Sailfish OS). Vulkan support is actively developed (PRs #604, #607). Test early in Phase 2. |
| Stock driver needs Binder/gralloc from chroot | Process UID/SELinux context is unchanged. Normal Android apps use gralloc under same SELinux domain. `/dev/kgsl-3d0` is 0666. Bind-mount `/vendor` and `/system`. |
| WSI layer complexity | The protocol work is simple. ARM's vulkan-wsi-layer is prior art. The Vulkan layer mechanism is a stable Khronos standard. |
| SELinux blocks cross-app Wayland socket | `app_process` relay with fd handoff (proven by wlroots-android-bridge, Termux:X11). |
| Smithay has never been built for Android | `default-features = false` avoids Linux deps. wayland-rs pure Rust backend. `EGLDisplay::from_raw()` avoids GBM. Test early. |
| Buffer fd type (opaque vs dmabuf) between same-driver instances | Same driver = understands its own fd format. Not a cross-driver problem. |
| Hidden API reflection in relay breaks across Android versions | TermuxAm pattern has worked through Android 15. Monitor for breakage. |
| Phantom Process Killer (Android 12+) | Relay exits after fd handoff. Users need Developer Options toggle for other Termux processes. |
| Qualcomm-only GPU support | This architecture works for any device where libhybris can load the stock GPU driver. Non-Qualcomm needs testing. `wl_shm` fallback for unsupported devices. |
| Freeform windowing not universal | On phones, each Activity is fullscreen (switch via recents). True freeform on Samsung DeX, ChromeOS, Android 15+ desktop mode. |

---

## References

- [libhybris/libhybris](https://github.com/libhybris/libhybris) -- bionic compatibility layer
- [Smithay](https://github.com/Smithay/smithay) -- Rust Wayland compositor library
- [Xtr126/wlroots-android-bridge](https://github.com/Xtr126/wlroots-android-bridge)
- [AHardwareBuffer NDK docs](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- [Vulkan Layer mechanism](https://vulkan.lunarg.com/doc/view/latest/linux/loader_and_layer_interface.html)
- [ndk crate](https://crates.io/crates/ndk) -- Rust NDK bindings
