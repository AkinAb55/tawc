# GPU Buffer Sharing on Android: The Core Problem

## The Goal

A Wayland client renders with the GPU. The compositor receives the GPU buffer and displays
it — ideally without the pixels ever touching the CPU. On desktop Linux this is
straightforward (dmabuf fds, single GPU driver stack). On Android it is not.

## The Setup

There are **two different GPU driver stacks** in play:

- **Client side (Termux chroot):** Mesa Turnip (open-source Vulkan for Qualcomm Adreno),
  accessing the GPU via `/dev/kgsl-3d0`. Optionally with Zink for OpenGL→Vulkan translation.
  Only works on Qualcomm devices.

- **Compositor side (Android app):** Stock proprietary Qualcomm Adreno EGL/GLES driver,
  loaded by the Android app process. This is what SurfaceFlinger and all Android apps use.

Both drivers talk to the **same GPU** through the **same kernel driver** (KGSL). The
physical GPU memory is shared. But the userspace driver stacks are completely different
codebases with different assumptions about buffer metadata, handle formats, and supported
extensions.

## The Wayland Protocol Flow

In standard Wayland (`zwp_linux_dmabuf_v1`):
1. Client allocates a GPU buffer
2. Client exports it as a dmabuf fd (a Linux kernel DMA-BUF file descriptor)
3. Client sends the fd + metadata (width, height, format, stride, modifier) to the
   compositor over the Wayland socket
4. Compositor imports the fd as a texture and composites it

Step 4 is where things break on Android.

## What the Compositor Needs

The compositor runs on the stock Android EGL driver. To use a buffer, it needs one of:
- An `EGLImage` (created from some importable handle type)
- An `AHardwareBuffer` (Android's native GPU buffer type, importable via
  `eglGetNativeClientBufferANDROID` → `EGLImage`)

## Strategies and Why They Don't (Straightforwardly) Work

### Strategy 1: Direct dmabuf Import via EGL

**Idea:** Use `EGL_EXT_image_dma_buf_import` on the compositor's EGL driver to import the
client's dmabuf fd directly as an EGLImage.

**Why it probably doesn't work:** `EGL_EXT_image_dma_buf_import` is a Mesa-centric extension.
Stock Android EGL drivers (Qualcomm Adreno, ARM Mali) are designed around
`AHardwareBuffer` / `EGL_ANDROID_image_native_buffer`, not Linux dmabuf import. The
extension is almost certainly not advertised by the stock Adreno driver.

**Status:** Unverified. Could be checked trivially at runtime by querying EGL extensions.
If it IS available on some devices, this is the simplest path. But we cannot design the
architecture around it.

**Even if available:** The stock driver would need to understand the buffer's memory layout
(format + modifier). Turnip may allocate with modifiers or tiling formats that the stock
driver doesn't recognize via this extension, even though both use KGSL under the hood.

### Strategy 2: Vulkan Cross-Handle-Type Conversion

**Idea:** Import the dmabuf fd into Vulkan via `VK_EXT_external_memory_dma_buf`, then
export the same `VkDeviceMemory` as an `AHardwareBuffer` via
`VK_ANDROID_external_memory_android_hardware_buffer`.

**Why it doesn't work:**

1. `VK_EXT_external_memory_dma_buf` is **not available on stock Android Vulkan drivers**.
   The Android Vulkan Profile 2025 requires `VK_KHR_external_memory_fd` (opaque fd) and
   `VK_ANDROID_external_memory_android_hardware_buffer`, but NOT the dmabuf extension.
   Stock Qualcomm drivers don't expose it.

2. Even if both extensions existed, **cross-handle-type export is not guaranteed** by the
   Vulkan spec. Importing as `DMA_BUF` and exporting as `ANDROID_HARDWARE_BUFFER` requires
   both bits in `compatibleHandleTypes`, which drivers are not required to support.

3. `VK_KHR_external_memory_fd` with `OPAQUE_FD` is a different fd type than `DMA_BUF`.
   You cannot import a dmabuf fd as an opaque fd — they are not interchangeable.

### Strategy 3: dmabuf fd → AHardwareBuffer via Public NDK API

**Idea:** Wrap the client's dmabuf fd as an `AHardwareBuffer`, then import via the standard
Android EGL path (`eglGetNativeClientBufferANDROID` → EGLImage → GL texture).

**Why there's no clean path:** The NDK provides `AHardwareBuffer_allocate()` (create new)
and `AHardwareBuffer_recvHandleFromUnixSocket()` (deserialize), but **nothing that accepts
a raw dmabuf fd**. The VNDK has `AHardwareBuffer_createFromHandle()` but it's not accessible
from third-party apps (blocked by Android's linker namespace restrictions since Android 7).

**Socketpair trick:** Forge the `GraphicBuffer` serialization format (as defined in AOSP's
`GraphicBuffer::flatten()`), stuff the client's dmabuf fd + metadata into it, write to a
socketpair, call `AHardwareBuffer_recvHandleFromUnixSocket()` on the other end. Uses only
public NDK API. Hacky — depends on an internal serialization format — but that format has
been stable since Android 8 and is effectively ABI-locked by the existence of the public
`send`/`recv` functions.

**Gralloc 4 IMapper:** Call `android.hardware.graphics.mapper@4.0` HIDL/AIDL interface via
JNI to `importBuffer(native_handle_t)`. More robust, but requires Android 11+ and JNI
plumbing to access a HIDL/AIDL service from native code.

**Status:** Either approach could work but is unproven for this use case. The socketpair
trick requires knowing the exact serialization format. The Gralloc IMapper approach requires
constructing a valid `native_handle_t` from the dmabuf fd + metadata, which is
vendor-specific (Qualcomm's gralloc handle format differs from others).

### Strategy 4: Compositor Allocates AHardwareBuffers, Clients Render Into Them

**Idea:** Flip the allocation ownership. The compositor allocates `AHardwareBuffer`s (via
the NDK, through Android's gralloc). Both the stock EGL driver and Mesa Turnip can import
AHBs. The compositor sends the buffer to the client, the client renders into it, the
compositor reads it back.

**Why this is attractive:** `AHardwareBuffer` is Android's universal GPU buffer currency.
The stock EGL driver imports it via `eglGetNativeClientBufferANDROID` (guaranteed to work).
Mesa Turnip imports it via `VK_ANDROID_external_memory_android_hardware_buffer` (Turnip
supports this — it's required for Android Vulkan conformance). Both see the same gralloc-
backed physical memory. True zero-copy.

**Problems:**

1. **`zwp_linux_dmabuf_v1` has the client allocate, not the compositor.** The protocol
   provides `zwp_linux_dmabuf_feedback_v1` to *guide* client allocation (advertise
   preferred formats/modifiers), but the client still calls its own allocator. The
   compositor cannot push pre-allocated buffers to clients through this protocol.

2. **How does the client receive the AHB?** `AHardwareBuffer_sendHandleToUnixSocket` /
   `recvHandleFromUnixSocket` can serialize AHBs over Unix sockets. But standard Wayland
   clients (Mesa's Vulkan WSI, GTK, Qt) don't know about this. Would need either:
   - A **custom Wayland protocol extension** (`android_buffer`) — compositor sends
     serialized AHB handles, client imports them as VkImage render targets. Requires
     client-side support (a Mesa patch or a client wrapper library).
   - A **client-side shim library** that intercepts buffer allocation and routes through
     AHB. Would `LD_PRELOAD` or patch Mesa's allocator.

3. **Does `AHardwareBuffer_recvHandleFromUnixSocket` work in a chroot/proot?** It calls
   into `libandroid.so` / `libnativewindow.so`. These are Android system libraries that
   may not be available or functional in a Termux chroot. If the client can't deserialize
   AHBs, this path fails on the client side. (The client could potentially use the raw
   dmabuf fd from inside the AHB's `native_handle_t` without going through the AHB API,
   but then it's just a dmabuf with extra steps.)

4. **Buffer pool management.** The compositor must manage a pool of AHBs per client surface
   (typically triple-buffered). It must track which buffers are in-flight, which are being
   rendered to by the client, and which are available. This is doable but adds complexity.

### Strategy 5: Compositor Allocates AHBs, Exports as dmabuf, Tracks Round-Trip

**Idea:** Variant of Strategy 4 that works with standard `zwp_linux_dmabuf_v1`:

1. Compositor allocates AHB
2. Compositor extracts the dmabuf fd from the AHB (via VNDK `AHardwareBuffer_getNativeHandle`
   or by importing into Vulkan and exporting as fd)
3. Somehow make clients allocate from the same pool (via `zwp_linux_dmabuf_feedback_v1`
   hinting)
4. When client sends a dmabuf fd back, compositor matches it to the original AHB

**Problems:**

1. **Extracting dmabuf fd from AHB:** The VNDK `AHardwareBuffer_getNativeHandle()` is not
   public API. The Vulkan path (`VK_ANDROID_external_memory_android_hardware_buffer` import
   → `VK_KHR_external_memory_fd` export) produces an **opaque fd**, not a dmabuf fd. Opaque
   fds are driver-specific and cannot be imported by a different driver (Mesa Turnip).

2. **Client allocation can't be forced.** `zwp_linux_dmabuf_feedback_v1` can suggest
   formats and modifiers, but the client's Mesa driver will still allocate through its own
   GBM/allocator path. There's no guarantee the resulting buffer will be wrappable as an
   AHB on the compositor side — we're back to Strategy 3's import problem.

3. **fd matching is fragile.** Even if the compositor and client use the same underlying
   KGSL allocation, matching an incoming fd to a known AHB requires comparing kernel buffer
   identities (`kcmp(2)`, or dmabuf global IDs from `/proc/self/fdinfo`). This works but
   is an unusual pattern.

### Strategy 6: Run Mesa on the Compositor Side Too

**Idea:** Instead of using the stock Android EGL driver for compositing, load Mesa (Turnip
+ Zink, or Freedreno) in the compositor process too. Then both client and compositor use
the same driver stack and `EGL_EXT_image_dma_buf_import` works natively.

**Problems:**

1. **The compositor is an Android app.** Android apps are loaded by the Zygote process with
   the stock driver's EGL/GLES libraries already in the process's linker namespace. Loading
   a second, conflicting set of GL libraries (Mesa's `libEGL.so`, `libGLESv2.so`) in the
   same process is likely to cause symbol conflicts.

2. **No ANativeWindow integration.** Mesa's EGL doesn't know how to create EGLSurfaces from
   Android's `ANativeWindow`. Mesa's Android support is designed for the Android system
   compositor (SurfaceFlinger), not for apps.

3. **Would need to render offscreen and blit.** Even if Mesa loaded, you'd render to an
   offscreen buffer, then need to get that to SurfaceFlinger — which brings back the AHB
   problem.

### Strategy 7: GPU Blit Through EGL

**Idea:** Accept a GPU→GPU copy. Import the client's dmabuf somehow (even with Mesa's EGL
if needed), blit it to a stock-driver-accessible buffer, present that.

This is what Termux:X11 does today, except they go through the **CPU** (GPU render →
CPU readback → CPU upload → GPU display). A GPU→GPU blit would be faster but requires
the compositor process to have access to an EGL driver that supports dmabuf import.
On stock Android, that driver isn't available (see Strategy 1).

## What Termux:X11 Actually Does (State of the Art)

Termux:X11 solves this by **not solving it**:

- **Path 1 (`MESA_VK_WSI_DEBUG=sw`):** Turnip renders on GPU. Mesa's WSI reads the frame
  back to CPU memory. Sends pixels to X server via `xcb_put_image()` or MIT-SHM. The X
  server (Android app with stock EGL) re-uploads as a GL texture. **GPU → CPU → GPU.**

- **Path 2 (DRI3):** Turnip exports a dmabuf fd. X server receives it via DRI3. Server
  does `mmap(fd, PROT_READ)` — a **CPU memory map**, not a GPU import. Pixel data is
  then uploaded as a GL texture. **GPU → CPU mmap → GPU.**

Both paths involve CPU readback. Nobody in the Termux ecosystem has achieved true GPU→GPU
buffer sharing between Mesa Turnip and the stock Android driver.

## Summary of the Landscape

| Strategy | Zero-copy? | Works on stock Android? | Proven? | Complexity |
|---|---|---|---|---|
| 1. Direct EGL dmabuf import | Yes | Probably not (extension missing) | No | Low |
| 2. Vulkan cross-handle-type | Yes | No (extension missing) | No | Medium |
| 3. dmabuf → AHB wrapping | Yes | Maybe (socketpair/IMapper) | No | High |
| 4. Compositor-allocated AHB | Yes | Needs custom protocol | No | High |
| 5. AHB export + feedback | Yes | Opaque fd not interoperable | No | Very high |
| 6. Mesa on compositor side | Yes | Conflicts with stock driver | No | Very high |
| 7. GPU blit | One GPU copy | Needs dmabuf-capable EGL | No | Medium |
| 8. Mesa in Android app | Yes | Linker namespace eng. required | No | High |
| 9. Mesa in Termux binary | Yes | Needs Android helper for windows | Partially (wlroots-android-bridge) | Medium |
| Termux:X11 approach | No (CPU copy) | Yes | Yes | Low |

**Nothing is proven.** The only demonstrated path involves CPU readback.

### Strategy 8: Mesa Turnip + Zink as the Compositor's GPU Driver (Android App)

**Idea:** The compositor (running as an Android app) loads Mesa's own EGL/GLES/Vulkan stack
(Turnip + Zink) instead of using the stock proprietary Qualcomm EGL driver. Both client and
compositor use the same Mesa driver stack, eliminating the cross-driver buffer sharing problem
entirely.

**Flow:**

1. Compositor app bundles Mesa's `libvulkan_freedreno.so` (Turnip), Zink gallium driver,
   and Mesa's `libEGL.so` + `libGLESv2.so`
2. At startup, compositor loads Mesa's libraries via `android_dlopen_ext()` with a custom
   linker namespace (NOT plain `dlopen()` — see below)
3. Compositor creates a surfaceless EGL display/context via Mesa's EGL
4. Client renders with Turnip in chroot, exports dmabuf fd
5. Compositor imports the dmabuf via `EGL_EXT_image_dma_buf_import` (Mesa's EGL supports
   this natively — same driver stack, no cross-driver import)
6. Compositor composites all surfaces with GLES (Smithay's `GlesRenderer`) into an
   AHardwareBuffer-backed FBO
7. AHB submitted to SurfaceFlinger via `ASurfaceTransaction_setBuffer()`

**Why this is attractive:**

- **No cross-driver import.** Both sides are Mesa/Turnip. dmabuf fds are native currency.
  `EGL_EXT_image_dma_buf_import` just works because the importing driver is the same
  driver that allocated the buffer.
- **No AHB wrapping hacks.** AHBs are only needed at the compositor→SurfaceFlinger boundary.
  The compositor allocates them itself via `AHardwareBuffer_allocate()` and renders into them.
- **Standard Wayland protocol.** `zwp_linux_dmabuf_v1` works unmodified — both sides speak
  dmabuf natively.
- **`/dev/kgsl-3d0` is accessible to all Android apps.** Permissions are `0666`, SELinux
  policy grants `untrusted_app` full access (`open`, `read`, `write`, `ioctl`, `map`).
  Confirmed by Google Project Zero's Adreno GPU research. Turnip accesses KGSL directly
  via ioctls, so no special permissions are needed.
- **One GPU composition pass.** Zero-copy from client to compositor (shared dmabuf), one
  GPU blit for composition, hardware presentation via SurfaceFlinger.

**Linker namespace engineering (critical detail):**

Plain `dlopen("libEGL.so", RTLD_LOCAL)` is NOT sufficient. Android's bionic linker does not
fully isolate `dlopen`'d libraries — `RTLD_LOCAL` only prevents symbols from being found by
subsequent `dlsym(RTLD_DEFAULT, ...)` calls, but transitive dependencies (libraries that Mesa's
libEGL.so itself loads) are resolved in the caller's linker namespace. If any transitive
dependency shares a soname with a system library, you get undefined behavior.

The correct approach is `android_dlopen_ext()` with a custom linker namespace created via
`android_create_namespace()`. This is exactly what **libadrenotools** does to load custom
Vulkan drivers (Turnip) into Android app processes. It is used in production by PPSSPP,
PojavLauncher, and various Switch emulators. However, all known uses load only a Vulkan ICD —
nobody has loaded a full Mesa EGL+GLES+Zink stack this way. It is proven for Vulkan, unproven
for the full stack.

**Zygote bootstrapping concern:**

Android apps are forked from Zygote. The system `libEGL.so` (a thin wrapper/stub) is linked
into all app processes, but the actual vendor EGL driver (`libEGL_adreno.so`) is loaded lazily
on the first EGL call. If the compositor never calls any system EGL function, the vendor driver
is never loaded. However:

- `SurfaceView` triggers Android's HWUI `RenderThread`, which calls `eglInitialize()` on the
  system EGL display. If the compositor uses `SurfaceView`, the system EGL is initialized
  before Mesa can be loaded.
- Solution: never create an EGL surface on the `ANativeWindow`. Use `ASurfaceTransaction` +
  AHB for presentation (which is already the plan). Use `NativeActivity` or avoid View
  hierarchies that trigger HWUI.
- The system `libEGL.so` stub will be present in the process but dormant, as long as no
  system EGL function is ever called.

**Smithay integration concerns:**

- Smithay's `backend/egl/ffi.rs` hardcodes `Library::new("libEGL.so.1")` in a global static.
  This must be patched to load Mesa's EGL from the custom namespace/path. The `khronos-egl`
  crate supports `Instance<Dynamic<Library>>` for custom loading, but Smithay does not use
  that path.
- `EGLDisplay::from_raw()` can wrap an externally-created Mesa EGL display, avoiding
  Smithay's own display creation path.
- GL function pointers loaded via `eglGetProcAddress` will resolve to Mesa's GLES (via Zink)
  as long as the EGL context is Mesa's. But you must verify Smithay does not load
  `libGLESv2.so` separately.
- The `use_system_lib` feature (EGL↔Wayland display binding) requires C `wl_display*`
  pointers. With the pure Rust wayland backend this is unavailable, but it's not needed —
  clients submit buffers via `zwp_linux_dmabuf_v1`, not `wl_drm`.

**Turnip AHB extension status:**

`VK_ANDROID_external_memory_android_hardware_buffer` landed in Turnip around August 2025
(per Mesamatrix). Related extensions also landed mid-2025:
- `VK_KHR_external_fence_fd`
- `VK_KHR_external_semaphore_fd`
- `VK_EXT_external_memory_dma_buf`
- `VK_KHR_external_memory_fd`

This is very new. There were historical dEQP test failures for AHB operations on Turnip
(GitLab mesa issue #5961). Whether these are fully resolved is unknown. The extension being
advertised does not guarantee it passes all CTS tests or works bug-free on every Adreno GPU
generation.

**Fence synchronization:**

`ASurfaceTransaction_setBuffer()` takes an `acquire_fence_fd`. Options:
- Mesa EGL supports `EGL_ANDROID_native_fence_sync` — call `eglCreateSyncKHR()` with
  `EGL_SYNC_NATIVE_FENCE_ANDROID`, then `eglDupNativeFenceFDANDROID()` to get the fd.
  Whether Zink correctly propagates this through to Turnip's fence infrastructure is untested.
- Fallback: `glFinish()` + pass `-1` as fence fd (buffer immediately ready). Wastes GPU
  parallelism but always works.

**EGL platform selection:**

Mesa's EGL supports platforms: `android`, `device`, `drm`, `surfaceless`, `wayland`, `x11`.
The `android` platform is for Mesa-as-system-GLES (AOSP builds), not third-party apps. The
`surfaceless` platform is appropriate here — it creates a headless EGL context for FBO
rendering. Mesa must be built with `-Dplatforms=surfaceless`.

**Build complexity:**

Mesa must be cross-compiled for `aarch64-linux-android`:
```
-Dvulkan-drivers=freedreno
-Dgallium-drivers=zink
-Degl=enabled
-Dgles2=enabled
-Dplatforms=surfaceless
```
The resulting `.so` files (~10-20MB) must be bundled in the APK's `lib/arm64-v8a/` and renamed
to avoid system library collisions (e.g., `libMesaEGL.so`). Prior art: freedreno-builder and
mesa-turnip-android-driver projects already cross-compile Turnip for Android, but adding
EGL + Zink is additional build work.

**Summary:**

| Aspect | Status |
|---|---|
| Core idea (same driver both sides) | Sound |
| `/dev/kgsl-3d0` access | Confirmed working |
| Linker namespace isolation | Proven for Vulkan (libadrenotools), unproven for full Mesa stack |
| Turnip AHB export | Extension landed Aug 2025, maturity unknown |
| Zink+Turnip for GLES compositing | Components work individually, this combination is novel |
| Smithay integration | Requires patching EGL module |
| Fence sync | Fallback exists, proper async path untested |
| Build/bundle | Feasible, prior art exists for Turnip cross-compilation |

**No prior art exists for the complete stack** (Mesa EGL+GLES+Zink+Turnip loaded inside an
Android app process for Wayland compositing). Each piece has precedent individually.

### Strategy 9: Mesa Turnip + Zink as the Compositor's GPU Driver (Termux Binary)

**Idea:** Same driver-stack-unification approach as Strategy 8, but instead of running the
compositor as an Android app, run it as a native binary inside Termux. Mesa is the native GPU
driver environment in Termux, so no linker namespace hacking is needed. Present to SurfaceFlinger
via `ASurfaceTransaction` from the Termux process.

**Flow:**

1. Compositor runs as a Termux binary (same UID as Termux and all Termux plugins)
2. Compositor uses Mesa Turnip + Zink for GLES rendering (Mesa is Termux's native GPU stack)
3. Client renders with Turnip in chroot, exports dmabuf fd
4. Compositor imports dmabuf via `EGL_EXT_image_dma_buf_import` — same driver, just works
5. Compositor composites all surfaces with GLES into an AHardwareBuffer-backed FBO
6. AHB submitted to SurfaceFlinger via `ASurfaceTransaction_setBuffer()`

**Why this is attractive (over Strategy 8):**

- **No linker namespace engineering.** Mesa is the native GPU library in Termux. No conflict
  with stock EGL because the compositor process never loads stock EGL. There is no Zygote,
  no system library preloading, no `android_dlopen_ext()` needed.
- **No Zygote bootstrapping concern.** The compositor is a regular Linux process, not an
  Android app. No HWUI, no `RenderThread`, no risk of the system EGL initializing before
  Mesa is loaded.
- **No Smithay EGL patching for library loading.** Mesa's `libEGL.so` is the only EGL in
  the process. Smithay's `Library::new("libEGL.so")` (with appropriate path/soname) finds
  Mesa directly. (Still need the `"libEGL.so"` vs `"libEGL.so.1"` soname issue resolved,
  but this is trivial.)
- **No cross-app socket/SELinux problem.** Compositor runs under Termux's UID. Wayland
  clients in Termux chroot also run under Termux's UID. Unix domain sockets between them
  work without SELinux restrictions. The socket can live in `$XDG_RUNTIME_DIR` under
  Termux's data directory, exactly like Termux:X11 does it.
- **Proven by wlroots-android-bridge.** This project already demonstrates zero-copy GPU
  compositing from inside Termux using Mesa drivers + `ASurfaceTransaction_setBuffer()`.
  It uses labwc (a wlroots compositor) running as a Termux binary with AHB-backed buffers
  submitted to SurfaceFlinger. The core architecture is proven to work.

**How ASurfaceTransaction works from a Termux binary:**

A Termux process is a regular Linux process running under an Android app UID. It can use the
NDK's `ASurfaceControl` / `ASurfaceTransaction` API if it has a valid `ANativeWindow` handle.
The question is how it obtains the `ANativeWindow`:

- **The wlroots-android-bridge approach:** An `app_process` relay (launched from Termux)
  starts an Android Activity via direct Binder IPC (`IActivityManager.startActivity()`).
  The Activity creates a `SurfaceView`, obtains its `Surface`, and passes the underlying
  file descriptor back to the Termux compositor process via Binder. The compositor then
  uses the surface fd to construct `ASurfaceControl` / submit `ASurfaceTransaction`.

- **Alternative (tawc's existing plan):** The tawc Android app (a thin shell) creates
  Activities with SurfaceViews, extracts `ANativeWindow` handles, and passes them to the
  compositor via a Binder fd handoff. The compositor gets an `ANativeWindow*` per toplevel
  window and submits AHBs to each via `ASurfaceTransaction`.

- **Key requirement:** The Termux compositor needs an Android-side helper (either an
  `app_process` relay or a companion app) to create and manage Android windows. The
  compositor cannot create `ANativeWindow`s on its own — that requires the Android window
  manager, which is only accessible via Binder from a process with an `ActivityThread`.

**What the Android-side helper provides:**

The helper (tawc Android app) handles only Android lifecycle concerns:
- Creating/destroying Activities (one per toplevel Wayland window)
- Providing `ANativeWindow` handles to the compositor via Binder/fd passing
- Forwarding touch/keyboard input events to the compositor
- Managing the Android task stack, recents, freeform window positioning

The helper does NOT do any GPU work. All rendering happens in the Termux compositor process
using Mesa.

**Smithay integration:**

Smithay works naturally in this environment:
- `EGLDisplay` created from Mesa's EGL (the only EGL in the process)
- `GlesRenderer` renders via Zink+Turnip
- `ImportDma` imports client dmabufs as GL textures
- Output to AHB-backed FBO, submitted to SurfaceFlinger via `ASurfaceTransaction`
- `EGLDisplay::from_raw()` may still be needed if the EGL display is created outside
  Smithay (e.g., via the `surfaceless` platform), but no library loading hacks are needed

**Remaining concerns:**

1. **ANativeWindow handle passing.** How does the Android helper app pass an `ANativeWindow`
   to the Termux compositor? Options:
   - `ANativeWindow` is backed by a `BufferQueue` which uses Binder. The underlying
     `IGraphicBufferProducer` Binder handle can be serialized and sent cross-process. This
     is what `Surface.writeToParcel()` does.
   - If using `ASurfaceTransaction` directly (submitting AHBs to a `ASurfaceControl`), the
     compositor needs the `ASurfaceControl` handle, which is also Binder-backed.
   - The wlroots-android-bridge project has working code for this fd/Binder handoff.

2. **Two-process architecture complexity.** The compositor (Termux binary) and the window
   manager helper (Android app) are separate processes that must stay synchronized. Window
   creation, resize, close, focus changes, and input events all cross the process boundary.
   This is additional IPC plumbing.

3. **Process lifecycle.** The Termux compositor binary is subject to Android's phantom process
   killer (Android 12+). Since it's a long-running process (not a brief relay), users must
   disable child process restrictions:
   - Android 12-13: `adb shell device_config put activity_manager max_phantom_processes 2147483647`
   - Android 14+: Developer Options "Disable child process restrictions"

4. **AHB allocation from Termux.** Can a Termux process call `AHardwareBuffer_allocate()`?
   This is an NDK function in `libandroid.so`. Termux processes can `dlopen("libandroid.so")`
   and call it — the function internally uses Binder to talk to the gralloc HAL, which should
   work from any UID with GPU access. This is believed to work but should be tested.

5. **ASurfaceTransaction from Termux.** Can `ASurfaceTransaction_setBuffer()` be called from
   a Termux process? The function is in `libandroid.so` and uses Binder to talk to
   SurfaceFlinger. SurfaceFlinger accepts buffer submissions from any process that holds a
   valid `IGraphicBufferProducer` handle. The Termux process obtains this handle from the
   Android helper app, so it should work. Proven by wlroots-android-bridge.

6. **Mesa build for Termux.** Turnip is already packaged for Termux
   (`mesa-vulkan-icd-freedreno`). Zink is available via `MESA_LOADER_DRIVER_OVERRIDE=zink`.
   Mesa's EGL + GLES may need to be built/packaged separately for Termux if not already
   available. The `mesa` Termux package may already include these.

**Summary:**

| Aspect | Status |
|---|---|
| Core idea (same driver both sides) | Sound — same as Strategy 8 |
| Mesa driver loading | Trivial — Mesa is native in Termux |
| Linker conflicts | None — no stock driver in process |
| Wayland socket | Trivial — same UID, filesystem socket works |
| Client dmabuf import | Same driver, `EGL_EXT_image_dma_buf_import` works |
| ANativeWindow acquisition | Requires Android helper app, proven by wlroots-android-bridge |
| AHB allocation from Termux | Believed to work, needs testing |
| ASurfaceTransaction from Termux | Proven by wlroots-android-bridge |
| Smithay integration | Natural — no library loading hacks needed |
| Process lifecycle | Subject to phantom process killer |
| Two-process complexity | Medium — IPC plumbing for window management |
| Prior art | **wlroots-android-bridge demonstrates this architecture working** |

**Strategy 9 is strictly easier than Strategy 8** for the GPU buffer sharing problem. It trades
linker namespace engineering, Smithay patching, and bootstrapping concerns for two-process IPC
plumbing (compositor ↔ Android helper). The IPC plumbing is well-understood (Binder fd passing)
and has working prior art.

## Key Open Questions

1. **Does stock Qualcomm EGL advertise `EGL_EXT_image_dma_buf_import`?** This can be
   checked in 5 minutes on a real device. If yes, Strategy 1 works and everything is
   simple. Nobody seems to have checked (or reported the result publicly).

2. **Does the socketpair trick (Strategy 3) actually work?** Forging the `GraphicBuffer`
   serialization format and feeding it to `AHardwareBuffer_recvHandleFromUnixSocket` with
   a Turnip-allocated dmabuf fd. This is testable with a small proof-of-concept.

3. **Can `AHardwareBuffer_sendHandleToUnixSocket` / `recvHandleFromUnixSocket` work in a
   chroot?** If the client can deserialize AHBs, Strategy 4 (compositor-allocated AHBs)
   becomes much cleaner.

4. **Is Mesa Turnip in a chroot able to import AHardwareBuffers?** It claims to support
   `VK_ANDROID_external_memory_android_hardware_buffer`. But does it actually work when
   running in a proot/chroot environment, not as a proper Android app?

5. **Can we use the Gralloc IMapper HIDL/AIDL interface from an app?** If so, Strategy 3
   becomes robust and vendor-agnostic (on Android 11+).

## What Needs to Happen Before Choosing an Architecture

Run empirical tests on a real Qualcomm device:

1. **Query EGL extensions** on stock driver — look for `EGL_EXT_image_dma_buf_import`
2. **Attempt socketpair trick** — allocate with Turnip, forge AHB serialization, receive
   on stock side
3. **Test AHB round-trip** — allocate AHB in app, send to chroot process, receive in
   chroot, attempt to import into Turnip's Vulkan
4. **Test Gralloc IMapper** — attempt to import a raw native_handle via the HIDL/AIDL
   interface from app code

The result of these tests determines which strategy (or combination) is viable. Without
them, any architecture choice is speculative.
