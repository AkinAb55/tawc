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
| Termux:X11 approach | No (CPU copy) | Yes | Yes | Low |

**Nothing is proven.** The only demonstrated path involves CPU readback.

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
