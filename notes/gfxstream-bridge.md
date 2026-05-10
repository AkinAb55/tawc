# gfxstream bridge: a libhybris-free GPU path

**Status:** design sketch, not implemented. Captured here so we can pick
it up if/when libhybris becomes untenable (new Android version breaks
the thunk patcher, x86 demand grows, vendor blob ABIs drift, …).

## The idea in one paragraph

Instead of loading Android vendor `.so`s into the chroot via libhybris,
forward GL/Vulkan command streams **out** of the chroot to a small
Android-side service that uses the system's native EGL/Vulkan in a
normal Android process. The chroot side is plain Mesa with a
GL/Vulkan-over-IPC backend; the Android side is a normal Android
process where the closed-source vendor blob lives in its native
habitat. Buffers are shared as dmabufs (or AHBs where gralloc-backed
allocation is required). No vendor code ever enters the chroot, so the
TLS / linker-namespace / CFI / inlined-`mrs` problem class is gone
wholesale.

This is the same architecture Chrome OS uses for Crostini: Linux apps
in an LXC, Mesa virtio-gpu driver, the host (Chrome OS) renders via
its real GPU stack. We want it in reverse: chroot is the "guest",
Android is the "host".

## Building blocks

**gfxstream** ([google/gfxstream](https://github.com/google/gfxstream))
is the obvious reuse target. It is the wire protocol the Android
emulator already uses to forward Vulkan/GL between the AVD guest and
the host machine. Both halves are open source:

- **Guest side** is upstream in Mesa as `gfxstream-vk` (and a GL
  driver). Builds for x86_64 and aarch64. Talks Vulkan/GL to a
  transport, normally virtio-gpu but pluggable.
- **Host side** is a renderer that decodes the protocol and runs the
  calls against a local GL/Vulkan implementation. An Android host
  backend already exists upstream — ships in AOSP's `com.android.virt`
  APEX for AVF (crosvm-on-Android). Uses EGL-on-EGL and
  `VK_USE_PLATFORM_ANDROID_KHR`. See "Host backend research" below.

**rutabaga / cross-domain context** (crosvm) is the other shape — same
goal, more general protocol, designed for the Sommelier model. Worth
re-evaluating at decision time; at time of writing gfxstream looked
like the shorter path because the protocol and the Mesa-side guest
already exist and Android-side EGL/Vulkan is the renderer's normal
target.

## The transport sharp edge

`virtio-gpu` is a kernel device — you can't fabricate one in
userspace. So we don't get to use Mesa's stock virtio-gpu Gallium
driver against a `/dev/dri/cardN` of our own making. The transport has
to be the gfxstream protocol over a Unix socket (or a memfd ring
buffer), not virtio-gpu kernel queues.

**Resolved:** Mesa's kumquat backend does exactly this — pure
userspace, Unix socket, no kernel driver. See "Transport research"
below for full details.

## Android-side bridge daemon

A normal Android service process linked against `libEGL.so` and
`libvulkan.so` (so the vendor blob loads against its proper bionic,
exactly how the Android system itself uses it). It accepts gfxstream
protocol on a Unix socket exposed into the chroot via the existing
broker mechanism (next to the Wayland socket), holds one EGL/Vulkan
context per chroot client, and translates command streams.

**Runs in-process** (compositor app, separate thread holding the GL
context). The vendor driver is the same one Android uses for every app
— if it's unstable enough to crash, the compositor experience is
degraded regardless. In-process avoids an IPC hop on buffer handoff
(exported AHB stays in the same address space) and simplifies
lifecycle. Can split to a sibling Service later if stability proves
to be a real problem.

SELinux: should be fine as long as the bridge runs as the compositor
app's uid in our app's domain (where EGL/Vulkan already work). No new
permissions, unlike libhybris-in-chroot which fights `runas_app`.

## Buffer sharing

Same question as today: how does a buffer the chroot rendered into
become something the compositor can sample as a GL texture?

Two paths, both feasible:

1. **Bridge allocates AHB on the chroot's behalf.** Chroot client asks
   for a swapchain image; bridge calls `AHardwareBuffer_allocate`,
   sends the dmabuf fd back to the chroot for the GPU to render into,
   and the AHB itself flows through the existing compositor import
   path (`AHardwareBuffer_createFromHandle` → `EGL_ANDROID_native_buffer`
   → GL texture). Reuses everything. Cross-process AHB import already
   works on stock firmware ≥ 12 (verified — see `notes/gpu-strategy.md`).

2. **Plain dmabufs + `linux-dmabuf-v1`.** Bridge uses gralloc/AHB
   internally to allocate, exports the dmabuf fd, sends it via Wayland
   `linux-dmabuf-v1`. Compositor imports via either AHB
   reconstruction or `EGL_EXT_image_dma_buf_import` (if the device's
   EGL exposes it — true on the AVD's gfxstream EGL, sometimes true
   on vendor stacks).

(1) is the safe default because it never leaves the AHB path that
already works. (2) is incrementally interesting if we want a future
where the compositor doesn't depend on Android EGL extensions, but
that's a separate fight.

## Cost vs libhybris

Lose: every GL/Vulkan call crosses an IPC boundary. Vulkan amortizes
this fine — command buffers are designed for exactly this batching,
which is why gfxstream went Vulkan-first. GL is more painful but
liveable; modern Mesa GL drivers already build command buffers
internally and flush in batches.

Gain:

- **No closed-source code in the chroot.** No TLS, no thunk patcher,
  no CFI bypass, no linker-namespace gymnastics, no per-Android-version
  bionic-slot whack-a-mole. Vendor blob runs where it expects to run.
- **x86 and ARM identical.** The one-architecture-only thunk patcher
  is the load-bearing constraint that keeps us aarch64-only today.
  This makes the AVD a first-class GPU target.
- **AVD parity unblocks dev loop.** Most graphics work currently has
  to happen on physical hardware; a working emulator path lets a lot
  more iteration happen on a laptop.
- **No vendor patching.** Today every device is tested against our
  libhybris fork's behaviour with that vendor's quirks. The bridge
  daemon uses the device's own EGL/Vulkan — whatever that vendor
  ships for Android apps already works, by definition, so we
  inherit their stack's quality rather than re-deriving it.

## Open questions to resolve before committing

1. **Does Mesa's `gfxstream-vk` still support a non-virtio transport
   in 2026?** **Yes — answered, see "Transport research" below.**
2. **Does an Android-targeting gfxstream host backend exist?**
   **Yes — answered, see "Host backend research" below.**
3. **GL performance under IPC.** Vulkan is fine, GL might not be.
   **Largely answered: route GL via Zink-on-gfxstream-vk rather than
   native gfxstream-GL — see "GL/GLES path: Zink, not native
   gfxstream-GL" below.** Zink rebuilds the per-call GL pattern into
   batched Vulkan command-buffer submits *before* the IPC boundary,
   which directly mitigates the per-call cost. A microbenchmark is
   still worthwhile before committing if any priority client is
   GL-heavy on legacy compat-profile features Zink doesn't cover
   gracefully.
4. **Does the `linux-dmabuf-v1` / AHB import path stay zero-copy
   when both sides go through gfxstream?** **Yes — answered, see
   "Zero-copy buffer sharing" below.**

## Transport research (May 2026)

**Answer to open question #1: yes, Mesa's gfxstream-vk has a
fully-supported, actively-maintained non-virtio transport called
"kumquat". It communicates over a Unix domain socket (SEQPACKET) and
is exactly the shape we need.**

### Architecture (two layers)

Mesa's gfxstream guest has a clean **platform abstraction**
(`src/gfxstream/guest/platform/`) with four backends:

| Backend | Path | Transport |
|---------|------|-----------|
| `drm` | `platform/drm/` | DRM ioctls on `/dev/dri/renderD*` (real virtio-gpu) |
| `kumquat` | `platform/kumquat/` | Unix socket (`AF_UNIX SEQPACKET`) |
| `fuchsia` | `platform/fuchsia/` | Fuchsia syscalls |
| `windows` | `platform/windows/` | Windows named pipes |

Selection: `createPlatformVirtGpuDevice()` in `platform/VirtGpu.cpp`
checks the env var **`VIRTGPU_KUMQUAT`**. If set (any value), it
calls `kumquatCreateVirtGpuDevice()`. Otherwise it calls
`osCreateVirtGpuDevice()` (the DRM path on Linux).

Build-time: `-Dvirtgpu_kumquat=true` in Mesa's meson options. This
pulls in a Rust library (`src/virtio/virtgpu_kumquat/`) compiled via
meson's Rust support, plus a C FFI wrapper
(`src/virtio/virtgpu_kumquat_ffi/`).

### Kumquat transport details

1. **Socket path:** `VirtGpuKumquatDevice` constructor (C++) builds
   the path `/tmp/kumquat-gpu-<descriptor>` (default
   `/tmp/kumquat-gpu-0`), then calls `virtgpu_kumquat_init()`.

2. **Rust core:** `VirtGpuKumquat::new(gpu_socket)` (in
   `virtgpu_kumquat.rs`) creates a `Tube` connected to that path.
   `Tube` (`src/util/rust/sys/linux/tube.rs`) is a thin wrapper
   around `AF_UNIX SEQPACKET` with `SCM_RIGHTS` fd-passing.

3. **Wire protocol:** `kumquat_gpu_protocol` — a custom
   request/response protocol (defined in
   `src/virtio/protocols/protocols/kumquat_gpu_protocol.rs`). Not
   virtio wire format, not vtest — purpose-built for kumquat. Messages
   are fixed-size structs; fds (for buffer handles, fences) travel via
   `SCM_RIGHTS` ancillary data. Commands include context create/destroy,
   resource create (3d + blob), transfer to/from host, command submit,
   capset queries, and snapshot save/restore.

4. **Buffer sharing:** resources created via kumquat come back with an
   fd (the `MesaHandle`) that can be mapped via `mmap`. For blob
   resources, the host allocates and sends the fd back; the guest maps
   it directly (shared memory). Fences are `eventfd`-based.

5. **No kernel driver needed.** The whole point of kumquat is to work
   without `/dev/dri/cardN`. The guest Mesa library talks directly to
   the kumquat server over the socket — pure userspace.

### What we'd need

1. **A kumquat server (host side).** The server must speak the
   `kumquat_gpu_protocol`, create a gfxstream rendering context (or
   rutabaga context), and dispatch commands to the real GPU. Google has
   a server implementation — it's used for testing and for
   non-virtio-gpu deployments — but it's not clearly upstreamed as a
   standalone binary. We'd need to either find/extract it, or write
   our own using `rutabaga_gfx` as the backend. The server is
   conceptually simple: accept socket, read kumquat commands, forward
   to rutabaga/gfxstream host renderer, send responses + fds back.

2. **Socket path customization.** The default `/tmp/kumquat-gpu-0`
   works fine for our use case — we just need to bind-mount or
   symlink the socket into the chroot. Or: trivially patch to read
   from an env var (one line in `VirtGpuKumquatDevice`).

3. **Build Mesa with `-Dvulkan-drivers=gfxstream
   -Dvirtgpu_kumquat=true`** for the chroot's aarch64 rootfs. The
   Rust toolchain is needed at Mesa build time.

4. **Set `VIRTGPU_KUMQUAT=1`** in the chroot environment. That's the
   only runtime config needed on the guest side.

### Connection to the AOSP gfxstream guest (what changed)

The AOSP gfxstream repo (`platform/hardware/google/gfxstream`) has
an older transport model with four `HostConnectionType` values
(`QEMU_PIPE`, `ADDRESS_SPACE`, `VIRTIO_GPU_PIPE`,
`VIRTIO_GPU_ADDRESS_SPACE`), selected via the Android property
`ro.boot.hardwares.gltransport` or `GFXSTREAM_TRANSPORT` env var.
None of these are Unix sockets — they all go through kernel devices
(goldfish pipe at `/dev/goldfish_pipe`, or virtio-gpu DRM).

Mesa's gfxstream-vk is a **separate, refactored codebase** that
shares protocol encoders with AOSP but has its own platform layer.
The kumquat backend exists only in Mesa, not in the AOSP guest tree.
This is fine — we'd build Mesa for the chroot anyway.

### Viability assessment

**This is viable with moderate effort.** The guest side is
off-the-shelf: build Mesa with the kumquat option, set one env var.
The server side is the real work, but the protocol is well-defined
and the rendering backend (rutabaga → gfxstream host renderer) is
proven. The Unix socket transport with fd-passing gives us zero-copy
buffer sharing for free (the host allocates an fd-backed buffer,
sends the fd to the guest, guest maps it — same physical pages).

## Host backend research (May 2026)

**Answer to open question #2: an Android host backend already exists
upstream, is production quality, and ships in AOSP as part of the
Android Virtualization Framework (AVF).** The "retargeting" work
originally described as "the bulk of the new Android-side work" is
already done.

### Where it lives

The gfxstream host renderer builds for Android via `host/Android.bp`.
The root `Android.bp` defines `gfxstream_host_cc_defaults` with
`-DVK_USE_PLATFORM_ANDROID_KHR` for Android targets. The comment in
that file is explicit:

> "host" in the name means the environment where VMM runs. For the
> Android Virtualization Framework case, this is Android.

`rutabaga_gfx` (in crosvm) links `libgfxstream_backend` and ships
inside the `com.android.virt` APEX. crosvm on Android uses this to
give VMs GPU acceleration — the exact architecture we want, minus the VM.

### GL backend: EGL-on-EGL

`host/gl/glestranslator/egl/egl_global_info.cpp` unconditionally
sets `sEgl2Egl = true` on Android. The EGL-on-EGL backend
(`egl_os_api_egl.cpp`) `dlopen`s the system's `libEGL.so` and
`libGLESv2.so`. No GLX, no WGL — it calls Android EGL natively.

### Vulkan backend: plain dlopen

`host/vulkan/vulkan_dispatch.cpp` loads `libvulkan.so` via
`SharedLibrary::open()` (which calls `dlopen`). On Android this
finds `/system/lib64/libvulkan.so`. The Android.bp enables
`VK_USE_PLATFORM_ANDROID_KHR` for proper surface handling.

### Native window

`host/native_sub_window_android.cpp` implements the display
abstraction using `ANativeWindow_acquire/setBuffersGeometry/release`.
We'd either pass a real Surface from our app or run headless and
extract frames via AHB export (Option A below).

### Init flags

`stream_renderer_init()` accepts a flags bitmask:
`STREAM_RENDERER_FLAGS_USE_EGL_BIT | USE_GLES_BIT | USE_VK_BIT`
gives a fully functional GL+Vulkan renderer calling Android's native
drivers.

### What's left

The host renderer already works on Android. The remaining work is:

1. **Transport**: wire the kumquat Unix-socket protocol into the
   `stream_renderer_*` API (which is transport-agnostic — rutabaga
   provides the transport layer in AVF).
2. **Build outside AOSP**: the host builds with Soong (`Android.bp`).
   We'd need to either port to CMake/NDK or use a Soong-based build
   step. The repo also has CMake support, but Android-target config
   may need work.
3. **Surface plumbing**: decide between passing a real `ANativeWindow*`
   or running headless with AHB export (see zero-copy section below).

## Zero-copy buffer sharing: what the code says (May 2026 research)

**Short answer: zero-copy is feasible, but gfxstream's host renderer
owns its buffers. Our daemon would need to either (a) export
gfxstream's internally-allocated AHBs to the compositor, or (b) add an
`AndroidAHB` external-memory import path so gfxstream renders into
compositor-allocated AHBs. Option (a) works today with no gfxstream
modifications; (b) would require moderate patching but has a template
in the QNX code path.**

### Buffer ownership model

gfxstream's host-side `VkEmulation` always allocates its own buffers.
`createVkColorBuffer()` creates a `VkImage`, calls
`allocExternalMemory()` to get a `VkDeviceMemory`, and binds them.
There is **no API to create a ColorBuffer backed by an
externally-provided VkImage or AHB**. The guest tells the host "I need
a color buffer with these dimensions and format", the host allocates
it, and hands back a handle.

### External memory export (the "gfxstream allocates, we import" path)

On Android, `allocExternalMemory()` sets `ExternalMemory::Mode::AndroidAHB`:
- `VkExportMemoryAllocateInfo` with
  `VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID`
  goes into the `VkMemoryAllocateInfo` pNext chain.
- After allocation, `vkGetMemoryAndroidHardwareBufferANDROID()` exports
  the `VkDeviceMemory` as an `AHardwareBuffer*`.
- `exportColorBufferMemory()` dups and returns that AHB handle.
- `VkExternalMemoryImageCreateInfo` with the AHB handle type is always
  appended to `VkImageCreateInfo::pNext` when external memory is
  supported (either import or export).

**This means gfxstream-allocated ColorBuffers are already backed by
real AHBs on Android.** Our daemon could call `exportColorBufferMemory()`
after a guest `eglSwapBuffers` and hand the resulting AHB to the
compositor for zero-copy sampling.

### How the emulator displays rendered frames

The emulator's `PostWorker` borrows a ColorBuffer via
`borrowForComposition()`/`borrowForDisplay()`, which returns a
`BorrowedImageInfoVk` containing the raw `VkImage`, `VkImageView`,
layout state, and queue family ownership. `DisplayVk::post()` blits
this image into its own swapchain. The host owns the buffer
throughout; the guest never provides one.

### External buffer import (the "we allocate, gfxstream renders into
it" path)

There is **no built-in Android path** for importing an externally-
allocated AHB as the backing store of a gfxstream ColorBuffer. However,
the QNX port (`ExternalMemory::Mode::QnxScreenBuffer`) demonstrates
exactly this pattern:

1. `allocExternalMemory()` allocates a QNX screen buffer externally
2. Queries its Vulkan memory properties via
   `vkGetScreenBufferPropertiesQNX()`
3. Validates size and memory-type compatibility with the VkImage
4. Appends `VkImportScreenBufferInfoQNX` to the allocate chain
5. `vkAllocateMemory()` imports (not allocates) the external buffer

An `AndroidAHB` equivalent would:
1. Call `AHardwareBuffer_allocate()` with the right format/usage
2. Query via `vkGetAndroidHardwareBufferPropertiesANDROID()`
3. Validate size/memory-type compat
4. Append `VkImportAndroidHardwareBufferInfoANDROID` to the chain
5. `vkAllocateMemory()` imports the AHB

This is ~50 lines of new code in `allocExternalMemory()`, following the
QNX template line by line.

### Vulkan path: VK_ANDROID_external_memory_android_hardware_buffer

Fully supported. The `external_memory.cpp` `calculateMode()` function
selects `AndroidAHB` mode on `__ANDROID__` builds. The handle-type
transform functions in `vk_common_operations.cpp` map guest
`VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID`
to the host's AHB handle type (identity transform on Android). All the
Vulkan extension plumbing is already wired.

### Post/present interception

`PostWorker::post()` takes a `ColorBuffer*` and a completion callback.
`PostWorker::composeImpl()` borrows source and target ColorBuffers,
runs composition, and signals a future. The completion callback is a
natural interception point: instead of posting to a swapchain, our
daemon would signal the compositor that the AHB-backed ColorBuffer is
ready to sample.

### Recommended approach

**Option A (no gfxstream modifications):** Let gfxstream allocate
ColorBuffers normally (which produces AHBs on Android). After the
guest swaps, our daemon calls `exportColorBufferMemory()` to get the
AHB, then passes it to the compositor via the existing AHB import
path. The compositor imports it as a GL texture via
`EGL_ANDROID_image_native_buffer`. This is zero-copy: the guest
rendered directly into the AHB that the compositor samples.

**Option B (moderate gfxstream patch):** Our daemon pre-allocates AHBs
via `AHardwareBuffer_allocate()` and tells gfxstream to use them as
ColorBuffer backing. Requires adding an `AndroidAHB` case in
`allocExternalMemory()` following the QNX template. Advantage: the
compositor owns the buffer lifecycle and can triple-buffer without
coordinating with gfxstream's internal reference counting. Disadvantage:
~50-100 lines of gfxstream patching to maintain.

Option A is the right starting point. Option B is worth pursuing only
if buffer lifecycle coordination proves painful.

## Distro Mesa is half the story (May 2026)

We checked what Arch and Debian actually ship: **the gfxstream-vk
driver is there, but built without the kumquat backend.**

- **Arch / Arch ARM:** `vulkan-gfxstream` is a Mesa subpackage that
  ships `libvulkan_gfxstream.so` + the ICD JSON. `vulkan-drivers=...,
  gfxstream,...` is set. `virtgpu_kumquat` does not appear in the
  PKGBUILD ([Arch ARM
  PKGBUILD](https://github.com/archlinuxarm/PKGBUILDs/blob/master/extra/mesa/PKGBUILD),
  [Arch
  PKGBUILD](https://gitlab.archlinux.org/archlinux/packaging/packages/mesa/-/blob/main/PKGBUILD)).
- **Debian unstable:** `-Dgfxstream` enabled on 64-bit-with-LLVM
  archs. Same omission — no `-Dvirtgpu_kumquat` ([Debian
  rules](https://salsa.debian.org/xorg-team/lib/mesa/-/raw/debian-unstable/debian/rules)).

Without `-Dvirtgpu_kumquat=true` at Mesa build time the kumquat
backend isn't compiled in — `VIRTGPU_KUMQUAT=1` at runtime is a
no-op and gfxstream-vk falls through to the DRM/`/dev/dri/cardN`
path, which we don't have.

**Implication for Phase 1:** we don't rebuild Mesa wholesale. The
minimal thing is **ship our own `libvulkan_gfxstream.so`** (built
once, host-side, with `-Dvulkan-drivers=gfxstream
-Dvirtgpu_kumquat=true`) plus an ICD JSON in
`/usr/local/share/vulkan/icd.d/` pointing at it. Vulkan's loader
picks it up via `VK_ICD_FILENAMES`; the rest of Mesa stays the
distro's. Same shape as the existing `LibhybrisInstallProvider`
asset overlay (APK asset → real-file copy at install time, host
kept clean).

Rust toolchain still required at build time (for
`virtgpu_kumquat_ffi`) — host-side, not in the rootfs. Add the
Mesa source and build options to `deps/deps.list` like every other
pinned dep.

### Faking `/dev/dri/cardN` in tawcroot was considered and rejected

Tempting, since tawcroot already does syscall interception. Doesn't
pencil out:

- DRM's UAPI is large (full virtio-gpu ioctl surface plus generic
  DRM bits — `RESOURCE_CREATE`, `EXECBUFFER`, `TRANSFER_*`, `MAP`,
  `WAIT`, `GET_CAPS`, `CONTEXT_INIT`, `PRIME_HANDLE_TO_FD`, …) and
  version-sensitive.
- `mmap(drm_fd, offset)` semantics are the killer — virtgpu blob
  resources are accessed by the kernel mapping host-shared pages.
  Faking that in userspace needs userfaultfd or hand-rolled
  AHB-dmabuf remapping with full lifecycle tracking. Massive
  scope-creep for tawcroot.
- Both wire formats (virtio-gpu DRM, kumquat) terminate at the same
  `stream_renderer_*` API on the Android side — host-side server
  work is identical either way. The only thing tawcroot-fake-DRM
  saves is a one-time cross-build of `libvulkan_gfxstream.so`.
- It only covers tawcroot installs (1/3 of methods); chroot/proot
  installs still need the .so.
- It fights gfxstream-vk's design — kumquat exists for exactly
  this "no kernel virtio-gpu available" case.

## GL/GLES path: Zink, not native gfxstream-GL

GL/GLES is covered by **Zink** (Mesa's GL→Vulkan translator), not by
shipping a separate gfxstream guest GL driver. The path becomes:

```
GL app → Zink → Vulkan calls → gfxstream-vk encoder → kumquat
       → Android-side bridge → Android Vulkan → vendor blob
```

Why Zink over native gfxstream-GL:

- **One driver, one transport, one host integration.** No second
  guest driver to build and pin, no second protocol surface in the
  bridge.
- **Mitigates the GL-over-IPC problem** (open question #3). GL is
  designed for per-call dispatch; Zink rebuilds that into Vulkan
  command-buffer submits, which is exactly the access pattern
  gfxstream's protocol amortizes well. So Zink isn't just GL
  fallback — it's the right answer to the IPC-cost question.
- **Already in our notes.** `notes/desktop-gl-dispatch.md` is the
  libhybris-side version of the same idea (Zink-on-libhybris-vulkan
  for desktop GL). Bridge inherits it for free.
- **Distros already ship Zink** as part of Mesa, no extra packaging.

Caveats: Zink's GL compat profile is good in 2026 but not 100% —
ancient fixed-function paths occasionally surprise. GLES2/3.x is
clean. GTK4, Qt6, Firefox, Chromium, modern games: fine. Truly old
X clients via Xwayland: usually fine, occasionally ugly. If a
priority workload exposes a Zink gap, gfxstream-GL stays available
as a fallback to revisit then — not as a default to maintain now.

Toggle in the rootfs is just `MESA_LOADER_DRIVER_OVERRIDE=zink` +
`__GLX_VENDOR_LIBRARY_NAME=mesa` in `RootfsEnv.kt`'s bridge
branch.

## Coexistence with libhybris

We are NOT dropping libhybris. The bridge ships alongside it and
the user picks per-launch which backend the chroot uses. Selection
is runtime, not install-time — the only thing that actually differs
between the two backends is which `.so` gets loaded, controlled by
env vars; reinstalling a multi-GB rootfs to flip it would be
overkill.

### Granularity

1. **Both backends ship in every install.** Build-time gating
   mirroring `-PtawcMethods=...` lets a release APK drop one for
   size if needed, but the default ships both.
2. **Global default via app setting** → drives env vars at every
   chroot entry. v0 UI: a radio selector on the home page (Hybris
   / Bridge), persisted via Android `SharedPreferences` (no
   datastore in the app today — first user-facing pref). Read by
   `RootfsEnv.build` on each entry.
3. **Per-app override (future, optional).** Power-user feature:
   `.desktop` `Exec=env TAWC_GPU=bridge firefox`. Cheap to add
   later once (2) exists; don't block v1 on it.

### Defaults

- **aarch64 physical:** `hybris` (proven, lower latency, our
  investment).
- **x86_64 emulator:** `bridge` (libhybris doesn't work on AVD —
  see notes/emulator.md "libhybris on x86_64". This is the
  whole reason we're building the bridge.)
- **Eventually flip the physical default to `bridge`** once it's
  shipped on by default for a release on AVD and proven stable.

### Where the env toggle lives

`app/src/main/java/me/phie/tawc/install/RootfsEnv.kt`. Every method
(tawcroot/proot/chroot) funnels through `RootfsEnv.envArgv`, which
is applied via `/usr/bin/env -i KEY=VAL …` on the in-rootfs `bash
-lc`. Single source of truth, no on-disk profile.d state,
consistently applied across all entry paths.

(Old plan referenced `/etc/profile.d/01-tawc.sh`; that file no
longer exists — see `notes/installation.md` "Nothing under
/etc/profile.d/". Don't reintroduce it.)

Branch in `RootfsEnv.build` on a new enum read from the
SharedPreferences-backed setting:

```kotlin
enum class GpuBackend { HYBRIS, BRIDGE }

// inside build():
when (gpu) {
    GpuBackend.HYBRIS -> {
        put("LD_LIBRARY_PATH",
            "${LibhybrisInstallProvider.GUEST_GL_SHIMS_DIR}:${LibhybrisInstallProvider.GUEST_LIB_DIR}")
        put("HYBRIS_EGLPLATFORM", "wayland")
        put("HYBRIS_VULKANPLATFORM", "wayland")
        put("GDK_GL", "gles:always")
    }
    GpuBackend.BRIDGE -> {
        put("VK_ICD_FILENAMES", BridgeInstallProvider.GUEST_ICD_PATH)
        put("VIRTGPU_KUMQUAT", "1")
        put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        put("__GLX_VENDOR_LIBRARY_NAME", "mesa")
        // no LD_LIBRARY_PATH override — distro Mesa is the GL/EGL stack
    }
}
```

Both backends' assets live alongside each other in the rootfs:

- libhybris: `/usr/lib/hybris/` + `/usr/local/lib/gl-shims/` (laid
  down by `TawcInstaller` / `LibhybrisInstallProvider`, untouched
  by this work).
- bridge: `/usr/local/lib/libvulkan_gfxstream.so` +
  `/usr/local/share/vulkan/icd.d/gfxstream_vk_kumquat.json` (new
  `BridgeInstallProvider`, same shape as `LibhybrisInstallProvider`).

The compositor's kumquat server thread is cheap to leave always-on
(or start lazily on first connection); it doesn't conflict with
`android_wlegl` since each Wayland client picks at most one of "use
android_wlegl" (libhybris path) or "use kumquat for buffers"
(bridge path) by virtue of which Vulkan/EGL library it loaded.

### What this changes in the implementation order

The "Phase 1 — guest side off-the-shelf" step in the plan below
is now: **build only `libvulkan_gfxstream.so` (kumquat-enabled)
host-side, ship as APK asset, lay down via a new
`BridgeInstallProvider`, plus a one-line ICD JSON.** Not "rebuild
Mesa for the chroot." The rest of Mesa is the distro's.

The "Phase 7 — retire libhybris" step is dropped. We ship both
indefinitely; the radio selector is the user's choice.

## Build status (May 2026)

**End-to-end Vulkan from chroot to vendor blob via gfxstream is
working on a physical device.** `vulkaninfo --summary` from inside
the chroot enumerates a `Virtio-GPU GFXStream (Adreno (TM) 660)`
device whose `driverID = DRIVER_ID_QUALCOMM_PROPRIETARY` confirms
the host-side renderer reaches the real Adreno blob.

Phases done: 0.1 (host renderer cross-build), 0.2 (kumquat server),
1.1 (guest driver kumquat-enabled), 2 (UI radio + persisted pref +
RootfsEnv branch), **3 (broker-spawned kumquat in untrusted_app
context — see "Why kumquat must run as untrusted_app" below)**. Still
TODO: Phase 1.2 (`BridgeInstallProvider` to install bridge bits via
the regular install path), Phase 4 (zero-copy AHB handoff after
vkQueuePresentKHR), Phase 5 (Wayland WSI plumbing — the
surface_lost_khr error on the wayland-surface query is the gap).

The previous `graphics_bridge.rs` test file has been folded back into
the regular suite: `bash scripts/run-integration-tests.sh --graphics
gfxstream` flips the in-app pref via the `set-graphics-backend` broker
action, runs `tests/integration/tests/graphics.rs` with the
`TAWC_GRAPHICS_BACKEND=gfxstream` env. The runner also auto-launches
the kumquat daemon via the `start-bridge-daemon` action when the
socket isn't already bound (no more "remember to run bridge-setup
first" footgun). `test_vulkaninfo_loads_android_driver` and
`test_weston_simple_shm_uses_shm_buffers` pass on both backends today;
`test_eglinfo_loads_android_driver`, `test_weston_simple_egl_*`, and
`test_vulkan_client_*` skip cleanly under gfxstream (each prints a
`SKIP:` line that points at the missing phase) until WSI plumbing
lands.

### What works

- **gfxstream host renderer cross-builds for Android NDK** (`scripts/build-gfxstream-backend.sh`) — 8.7MB libgfxstream_backend.so, ELF aarch64, dynamically linked against `libdl/libnativewindow/libandroid/liblog/libc++_shared`. Patches in `deps/gfxstream-patches/gfxstream/01-android-host-build.patch` add `host_machine.system() == 'android'` cases to the ~6 meson.build files that switch on platform, fix two case-insensitive include typos (`GlesCompat.h` → `gles_compat.h`), swap one VNDK header for its NDK equivalent (`<vndk/hardware_buffer.h>` → `<android/hardware_buffer.h>`), and add `-DANDROID=1` (NDK clang defines `__ANDROID__` but not bare `ANDROID`, which several files check). The full GLES+Vulkan+Composer surface is built — it was less work than patching for vulkan-only because frame_buffer.cpp / color_buffer.cpp use a lot of unguarded GL constants.
- **kumquat server cross-builds for Android NDK** (`scripts/build-kumquat-server.sh`) — 1.9MB aarch64 PIE binary. cargo build with `--target=aarch64-linux-android --features gfxstream`, linked against the libgfxstream_backend.so above via `GFXSTREAM_PATH_RELEASE`. Two patches at `deps/rutabaga-patches/rutabaga_gfx/`: `01-drop-nativewindow-dep.patch` drops the AOSP-only `nativewindow` Rust crate dep from the PLATFORM_AHB export path (we don't go through that path; AHB handoff happens C++-side via `exportColorBufferMemory()` instead), and `02-keep-server-alive-on-client-error.patch` traps per-connection `process_command` errors and drops just that client instead of unwinding the whole event loop and exiting `main()` — so a guest hitting the unimplemented PLATFORM_AHB export path doesn't take the daemon down with it.
- **kumquat ships as `libkumquat.so` jniLib + spawned by the broker** (`app/src/main/java/me/phie/tawc/dev/BridgeActions.kt`, `start-bridge-daemon` / `stop-bridge-daemon`). The build script copies the binary plus its DT_NEEDED `libgfxstream_backend.so` and `libc++_shared.so` into `app/src/main/jniLibs/arm64-v8a/`; PackageManager extracts those to `nativeLibraryDir` with the `apk_data_file` SELinux label, the only file label `untrusted_app` is allowed to `execute`. Same trick `libtawcroot.so` / `libproot.so` already use. See "Why kumquat must run as untrusted_app" below for why this isn't optional.
- **End-to-end Vulkan works on the device.** `bash scripts/bridge-setup.sh start` (or the integration test runner with `--graphics gfxstream`, which calls the same broker action) drops `libvulkan_gfxstream.so` + ICD JSON into the rootfs and starts kumquat as a child of the compositor app process. After that, `vulkaninfo --summary` from the chroot reports `Virtio-GPU GFXStream (Adreno (TM) 660), driverID=DRIVER_ID_QUALCOMM_PROPRIETARY`.
- **gfxstream host renderer also builds on x86_64 Linux** (`meson setup -Dgfxstream-build=host` on `deps/gfxstream`) — the original validation milestone, now superseded by the NDK cross.
- **`libvulkan_gfxstream.so` + `libvirtgpu_kumquat_ffi.a` cross-build for aarch64 glibc**, 6.9MB shared lib (was 2.9MB before kumquat went in), advertises `VK_KHR_wayland_surface`, loads cleanly under the chroot's stock vulkan-icd-loader. End-to-end verified on the physical device: with `VK_ICD_FILENAMES=…/gfxstream_vk_icd.aarch64.json VIRTGPU_KUMQUAT=1` (no libhybris in scope) the driver loads and logs `MESA: info: Failed to init virtgpu kumquat` — i.e. it's now trying to dial `/tmp/kumquat-gpu-0` and giving up cleanly because no server is listening yet. That's exactly the expected gap — Phase 0.2 (kumquat server on Android side) closes it.
- **Build script: `scripts/build-mesa-gfxstream.sh`.** Mirrors the `build-libhybris.sh` "stub .so + synthetic .pc + cross gcc, no sysroot" pattern. Copies real aarch64 `libwayland-{client,server}.so.0` and `libdrm.so.2` from `build/aarch64-sysroot/` (extracted from the device's installed rootfs) because empty stubs lose the `wl_*_interface` / `drmIoctl` symbols that wayland-scanner-generated protocol files and gfxstream's DRM platform code reference at link time. Pure stubs are fine for libudev / libffi (only DT_NEEDED matters).
- **Mesa patches at `deps/mesa-patches/mesa/`** (xwayland-patches style — sentinel-based idempotent re-apply on patch hash change). Two patches:
  - `01-add-cargo-toml.patch`: drops `Cargo.toml` files into the four Mesa-internal Rust crates (`mesa3d_util`, `mesa3d_protocols`, `virtgpu_kumquat`, `virtgpu_kumquat_ffi`) plus a workspace `Cargo.toml`, so cargo can build them directly. Templates copied from the matching crates in magma-gpu/rutabaga_gfx (which Cargo-builds the same source). Differences: thiserror 2.0 (Mesa floor; rutabaga ships 1.0), zerocopy 0.8.13.
  - `02-meson-external-kumquat-ffi.patch`: adds a meson option `virtgpu_kumquat_external_ffi=true` that, when set, skips the four `subdir(...)` calls that build the Rust pieces via meson and instead resolves `dep_virtgpu_kumquat_ffi` via plain pkg-config. Also gates the `add_languages('rust')` block on the same condition, so meson never spins up the Rust subproject machinery at all.
- **The cargo + meson-external split** is what unblocked kumquat. Mesa's `subprojects/packagefiles/*/meson.build` hard-code `native: true` on every Rust crate's `static_library()`. The proc-macro chain (cfg-if/syn/quote/proc-macro2/unicode-ident) and the regular host-machine crates (cfg-if as `mesa3d_util` dep) can't both satisfy meson in a cross-build context — see git history for the dead-end attempts. Cargo handles cross-builds + proc-macros transparently and produces a static lib that's just linked in like any other dep.
- **`deps/deps.list` pins:** `mesa` (mesa-25.3.6), `gfxstream` (current main), `rutabaga_gfx` (magma-gpu fork — the kumquat server source for Phase 0.2).
- **Cross-build sysroot pull:** the script reads `build/aarch64-sysroot/usr/lib`, which the operator pre-populates with `tar -C /data/data/me.phie.tawc/distros/<id>/rootfs -czf - usr/include usr/lib/pkgconfig usr/lib/libwayland-* usr/lib/libdrm* usr/lib/libudev* …` over `tawc-exec`. **Not yet automated** — TODO is to either bake this into the script (pull from device on demand) or vendor the relevant aarch64 .so files alongside the libhybris assets so it's reproducible without a connected device.

### Why kumquat must run as untrusted_app (the SELinux trap)

The SCM_RIGHTS half of the kumquat protocol is what hands a memfd-backed
ring blob from server to guest on every `ResourceCreateBlob` (the very
first one being the gfxstream AddressSpaceStream). On Android, the
kernel silently drops the FD half of the cmsg whenever the sender and
receiver live in different SELinux domains — the rule that would
allow it (`fd { use }` between source and target) is `dontaudit`'d, so
not even an AVC denial shows up in `dmesg` or `logcat`. The visible
symptom is benign-looking on both ends: `sendmsg` succeeds with the
correct fd count on the server, `recvmsg` returns the same byte count
but `descriptor_vec.is_empty()` on the client, and the guest's
`virtgpu_kumquat::resource_create_blob` falls into its
`_ => Err(MesaError::Unsupported)` arm because the response carries
no SCM_RIGHTS payload to construct a `MesaHandle` from. That bubbles
back through the FFI as `-EINVAL`, and Mesa logs:

```
MESA: error: DRM_VIRTGPU_KUMQUAT_RESOURCE_CREATE_BLOB failed with Invalid argument
MESA: error: Failed to create virtgpu AddressSpaceStream
```

…before the chroot's vulkan-icd-loader gives up with
`vkEnumeratePhysicalDevices failed with ERROR_INITIALIZATION_FAILED`.

The earlier "run kumquat under `su`" approach put the server in
`u:r:magisk:s0` — which is why every Vulkan probe out of the chroot
dead-ended on the AddressSpaceStream blob even though every other
piece of the pipeline (socket bind, message framing, gfxstream init
"Adreno (TM) 660" in logcat) was working. Confirmed by toggling
`setenforce 0` end-to-end: `vulkaninfo --summary` immediately starts
returning the right device.

The fix isn't to ship `magiskpolicy --live` rules at runtime (fragile,
device-specific, requires Magisk). It's to spawn kumquat through the
broker so the child inherits `u:r:untrusted_app:s0:c<…>` — the same
context as the in-rootfs client. Then `fd use` is intra-domain and the
kernel delivers the cmsg unchanged. `BridgeActions.start-bridge-daemon`
is the entry point; everything else in the pipeline (the build-time
jniLib stage so `untrusted_app` can `execute` the kumquat binary at
all, the bridge-setup script, the integration test runner) flows from
that single requirement.

### Confirmed not-blockers

- **gfxstream's host-side Android backend is real and present in the tree.** Confirmed `host/gl/glestranslator/egl/egl_global_info.cpp` unconditionally sets `sEgl2Egl = true` on Android (EGL-on-EGL via dlopen of system libEGL); `host/vulkan/vulkan_dispatch.cpp` dlopens `libvulkan.so`; `host/native_sub_window_android.cpp` wraps `ANativeWindow_*`. The earlier "host backend research (May 2026)" section claimed this from code reading — the host build above confirms it compiles and links.
- **Real-aarch64-`.so`-from-device sysroot pattern works.** No surprises with ABI mismatches; the aarch64 Arch ARM `libwayland-client.so.0` we pulled satisfies link without complaint.
- **`VK_ICD_FILENAMES` cleanly overrides the loader's search path** when libhybris is removed from `LD_LIBRARY_PATH`. We confirmed the chroot's vulkan-icd-loader picks up our ICD JSON exclusively when env is sanitised — no surprise interactions with the libhybris-installed `libvulkan.so.1` (which intercepts at a different layer).

### Remaining work to a fully-integrated bridge backend

End-to-end Vulkan works. The remaining items move it from "manual
setup script + integration test" to "select bridge in the UI and
launch any app":

1. **`BridgeInstallProvider` Kotlin.** Mirrors `LibhybrisInstallProvider`. APK asset → real-file copy under `/usr/local/lib/libvulkan_gfxstream.so` + `/usr/local/share/vulkan/icd.d/gfxstream_vk_icd.aarch64.json` at install time. Backed by Gradle tasks `buildMesaGfxstream` + `packMesaGfxstream` modelled on `buildLibhybris`/`packLibhybris`. (Today `scripts/bridge-setup.sh start` does the staging via the broker on every kumquat launch — fine for dev, wasteful on every fresh entry.)
2. **~~`GpuBackend` enum + `RootfsEnv` branch.~~** Done — `GraphicsBackend` enum lives in `app/src/main/java/me/phie/tawc/Settings.kt`, persisted via `SharedPreferences` (`tawc-settings`), exposed in the in-app Settings screen (tonal button on the home screen). `RootfsEnv.build(method)` reads `Settings.graphicsBackend` and emits libhybris-style env (`LD_LIBRARY_PATH=/usr/lib/hybris/...`) or gfxstream-style env (`VK_ICD_FILENAMES`, `VIRTGPU_KUMQUAT=1`). Tests flip the pref via the broker `set-graphics-backend` action — `bash scripts/run-integration-tests.sh --graphics gfxstream`.
3. **~~Broker-spawned kumquat in untrusted_app context.~~** Done — `BridgeActions.start-bridge-daemon` (called from `bridge-setup.sh start` and the integration test runner). The daemon is a fork-exec'd child of the app process, lives in `app/src/main/jniLibs/arm64-v8a/libkumquat.so`, and inherits the app's SELinux domain so SCM_RIGHTS works (see "Why kumquat must run as untrusted_app" above). Lifecycle is per-app-process: app force-stop / OOM kills the daemon too, which is the correct invariant — a stale daemon with no clients is harmless and the next `start-bridge-daemon` is idempotent. JNI / true in-process is a possible refinement but not on the critical path.
4. **Vulkan WSI Wayland↔kumquat plumbing.** gfxstream-vk's WSI is Android-shaped; need to remap `VK_KHR_wayland_surface` calls to allocate AHB-backed ColorBuffers via the bridge. The vulkaninfo smoke test sees `VK_KHR_wayland_surface failed with SURFACE_LOST_KHR` — that's exactly this gap. Enumeration works fine, presentation doesn't. `tests/integration/tests/graphics.rs::test_vulkan_client_uses_hardware_buffers` is gated on this — currently `SKIP:`s under `--graphics gfxstream`.
5. **AHB buffer handoff.** After `vkQueuePresentKHR`, our daemon calls `exportColorBufferMemory()` and feeds the AHB to the existing compositor import path. Option A in the "Zero-copy buffer sharing" section above — no gfxstream patches needed. The current rutabaga `01-drop-nativewindow-dep.patch` returns `InvalidResourceId` for PLATFORM_AHB exports; replacing that stub with the actual handoff is what unblocks vkcube + Phase 4.

### Sysroot pull (one-time, until automated)

```bash
mkdir -p build/aarch64-sysroot && cd build/aarch64-sysroot
. ../../scripts/lib/select-device.sh
. ../../scripts/lib/tawc-exec.sh
"$TAWC_EXEC_BIN" -- /system/bin/sh -c "cd /data/data/me.phie.tawc/distros/arch/rootfs && tar -czf - \
  usr/include usr/lib/pkgconfig usr/share/pkgconfig \
  usr/lib/libwayland-* usr/lib/libdrm* usr/lib/libudev* usr/lib/libffi* \
  usr/lib/libstdc++.so.6 usr/lib/libgcc_s.so.1 \
  usr/lib/libdisplay-info* usr/lib/libvulkan.so.1 usr/lib/libxkbcommon* \
  2>/dev/null" | tar -xzf -
```

`build/aarch64-sysroot/` is gitignored (under `build/`); regenerate any time the rootfs's library set changes.

## Implementation plan

### Phase 0 — de-risk the unknowns
0.1 **Build gfxstream host renderer with the NDK (no Soong).** The
single biggest unknown. Try the in-tree CMake first; expect to fix
Android-target gaps. Output: `libgfxstream_backend.so` in
`build/gfxstream-host/`, calling `stream_renderer_init(USE_EGL|
USE_GLES|USE_VK)` against system `libEGL/libvulkan`. Smoke test
inside a tiny APK.

**Update (May 2026):** the meson `host` build works fine on x86_64 Linux
(see "Build status" above). Risk class dropped to build-system pain
only — no source-level blockers. NDK cross still TODO.

0.2 **Audit the kumquat server side.** Either lift Google's server
out of AVF/crosvm or write a small one over `rutabaga_gfx` that
proxies kumquat → `stream_renderer_*`. Pick before committing —
that picks build pipeline (Rust + crosvm vs C++/Rust hybrid). Add
to `deps/deps.list` if vendored.

0.3 **GL perf microbench using Zink.** Once 0.1+0.2 stand up,
run a real Zink-on-gfxstream-vk test (`glmark2`, gtk4-debug-app)
through a stub kumquat link. Validate that Zink's command-buffer
batching does in fact tame the per-call cost. If not, scope down
to "Vulkan via bridge, GL stays libhybris" and replan.

### Phase 1 — guest side, ICD overlay
1.1 **Cross-build only `libvulkan_gfxstream.so` from Mesa** with
`-Dvulkan-drivers=gfxstream -Dvirtgpu_kumquat=true`. New
`scripts/build-mesa-gfxstream.sh`, vendored Mesa pinned in
`deps/deps.list`. Adds Rust toolchain to `notes/building.md`.
Output: a single .so + a one-line `gfxstream_vk_kumquat.json` ICD
manifest pointing at our install path.

**Update (May 2026):** done. `scripts/build-mesa-gfxstream.sh`
ships, the .so cross-builds (6.9MB, kumquat enabled), the ICD JSON
drops in. Mesa's meson Rust-subproject mess (a clash between proc-
macro and host-machine consumers of the same crate; see "Build
status" above) is sidestepped by cargo-building `virtgpu_kumquat_ffi`
separately and feeding the static lib + header to gfxstream-vk via
pkg-config. Patches in `deps/mesa-patches/mesa/`. End-to-end on
device: ICD loads, `VIRTGPU_KUMQUAT=1` is recognised, driver tries
to dial `/tmp/kumquat-gpu-0` and logs `MESA: info: Failed to init
virtgpu kumquat` because no server is listening — exactly the gap
Phase 0.2 closes.

1.2 **`BridgeInstallProvider`** mirroring `LibhybrisInstallProvider`:
APK asset → real-file copy under
`/usr/local/lib/libvulkan_gfxstream.so` + ICD JSON at install time.
Symlinks under `/usr/local/lib` per existing convention.

1.3 **Wire env into `RootfsEnv`.** New `GpuBackend` enum, branch
in `RootfsEnv.build`. Default reads from SharedPreferences via the
home-page radio (see Phase 2 below). Default value: `HYBRIS` on
aarch64 physical, `BRIDGE` on x86_64 emulator.

1.4 **Validate guest stack alone** with `vulkaninfo` / `vkcube`
against a stub host — confirm Mesa loads our ICD, gfxstream-vk
picks kumquat, socket connects.

### Phase 2 — UI: GPU backend radio on the home page
Two-option radio (Hybris / Bridge) above or alongside the
"Install new distro" CTA on `MainActivity`. Persisted via
`SharedPreferences` (first user-facing pref in the app).
`RootfsEnv.build` reads on each chroot entry; selection takes
effect on the next process the user launches in the rootfs (not
mid-process).

### Phase 3 — Android-side bridge daemon, in-process
3.1 **Spawn a kumquat-listening thread from
`CompositorService.onCreate`.** Owns one EGL/Vulkan context per
kumquat client. SELinux-wise it inherits the app domain — should
just work, unlike libhybris-in-chroot.

3.2 **Pump kumquat → `stream_renderer_*`.** Resource create/destroy,
transfer, command submit, fences (eventfd). Capability negotiation.
Snapshot/restore stubbed.

3.3 **Headless first.** Run gfxstream host renderer headless (no
`ANativeWindow*`); we never use its DisplayVk. End-to-end smoke:
chroot `vkcube` → bridge → Adreno → no frames yet, but command
stream completes.

### Phase 4 — zero-copy buffer handoff (Option A in §"Zero-copy buffer sharing")
4.1 **Intercept post.** Hook `PostWorker::post()` completion (or
`stream_renderer` post callback) so we don't drive `DisplayVk`. On
swap, call `exportColorBufferMemory(colorBufferHandle)` →
`AHardwareBuffer*`.

4.2 **Map kumquat client → wl_surface.** Bridge needs to know which
compositor surface this AHB is for. Easiest: bridge runs alongside
a tiny per-client wl_display connection that owns a `wl_surface`
and feeds AHBs via the existing `android_wlegl` import path the
compositor already speaks. Reuses everything from the libhybris
path on the compositor side.

4.3 **Triple-buffering / lifecycle.** gfxstream owns the buffer
pool; release callbacks drive its internal refcount. If this gets
painful, fall through to Option B (~50 LOC patch in
`allocExternalMemory()` mirroring `QnxScreenBuffer`).

4.4 **First real frame:** chroot `vkcube` → AHB → compositor
texture → on screen.

### Phase 5 — Vulkan WSI for real apps
5.1 **`VK_KHR_wayland_surface` in the guest.** gfxstream-vk's WSI
is Android-shaped; remap to Wayland either guest-side (mirroring
libhybris's trick) or via a tiny implicit layer. Bridge resolves
the `wl_surface` to a kumquat-side ColorBuffer.

5.2 **Format negotiation** matching what the compositor's gralloc
importer accepts (same constraints as today's libhybris path;
mostly inherits).

5.3 **Suite:** `vulkaninfo`, `vkcube`, Firefox WebGPU, glmark2-vulkan.

### Phase 6 — Zink wired up for GL/GLES
Mostly env-only at this point — the rootfs already has Mesa with
Zink from the distro. Validate:

6.1 `glxgears`, `glmark2` (desktop GL via Zink → bridge).

6.2 `weston-simple-egl`, gtk4-debug-app (GLES2/3 → Zink → bridge).

6.3 Firefox accelerated rendering with `MOZ_X11_EGL=1` /
`gfx.canvas.accelerated=true`.

6.4 Decide whether the `desktop-gl-dispatch.md` design (API-aware
libEGL routing) is still needed at all under the bridge path —
plain `MESA_LOADER_DRIVER_OVERRIDE=zink` may be enough.

### Phase 7 — AVD / x86_64 parity
The whole point. Repeat 1.1–6.3 with `--abi=x86_64`. No thunk
patcher, no bionic-slot whack-a-mole. Default `GpuBackend` flips
to `BRIDGE` on x86_64 emulator targets (already wired in 1.3).

### Cross-cutting
- **Integration tests:** extend `tests/integration/` with bridge
  variants of existing GPU tests; gate on `TAWC_GPU=bridge|hybris`
  (env-var override, separate from the persisted SharedPreferences
  default — same shape as `TAWC_TARGET`) so we run both backends in
  CI.
- **`deps/deps.list`** entries for Mesa (gfxstream-only build),
  gfxstream host renderer, kumquat server (or rutabaga), pinned by
  commit. Don't skip — silent drift here would be brutal.
- **`notes/building.md`** updated per change (Rust toolchain for
  Mesa, NDK/CMake quirks for gfxstream host, any new host pkgs).
- **`notes/gpu-strategy.md`** flip the "Alternative we haven't
  taken" wording to "Both backends ship; user-selectable" once
  Phase 2 lands.
- **`notes/wsi-layer.md`** add a "Bridge backend" subsection once
  Phase 5 is real; libhybris-WSI section stays.

Riskiest milestones: 0.1 (NDK build of gfxstream host), 0.2
(kumquat server source), 0.3 (Zink GL perf). Land those before
committing to phases 1–6.

## Relation to existing notes

- `notes/gpu-strategy.md` — current libhybris-based strategy. The
  bridge is the alternative described there as "out of scope" for
  cross-driver sharing. It supersedes that out-of-scope-ness by
  putting both halves on the *same* driver instance via IPC instead
  of via shared address space.
- `notes/emulator.md` — "libhybris on x86_64" section enumerates
  three options (A/B/C) for porting the thunk patcher, all expensive.
  The bridge sidesteps that whole tree: the chroot doesn't need
  bionic compat at all on x86, because nothing bionic-linked runs
  there.
- `notes/wsi-layer.md` — the chroot's GL/Vulkan WSI today. Under the
  bridge, the chroot's WSI becomes "Mesa with `gfxstream-vk`",
  i.e., upstream off-the-shelf. Libhybris-fork-side WSI patches
  stay needed for users on the `hybris` backend (which we ship
  indefinitely alongside the bridge — see "Coexistence with
  libhybris" above).
- `notes/desktop-gl-dispatch.md` — design for routing desktop-GL
  apps through Zink-on-Vulkan. Originally framed against
  libhybris-vulkan; under the bridge backend the same routing maps
  onto Zink-on-gfxstream-vk and is in fact the *primary* GL path
  for that backend (not just a desktop-GL fallback). May be
  obsoletable on the bridge side by `MESA_LOADER_DRIVER_OVERRIDE=zink`
  alone; revisit during Phase 6.
- `notes/installation.md` — "Nothing under `/etc/profile.d/`" policy
  is what makes the runtime GPU-backend toggle clean: env lives in
  `RootfsEnv.kt`, applied via `env -i` on every entry, no on-disk
  drift between method-specific entry paths.
