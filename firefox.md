# Firefox on tawc - Progress Report

## Current Status (2026-03-31)

Firefox 149 (Arch Linux ARM aarch64) is installed in the chroot and **creates an xdg_toplevel window, attaches SHM buffers, and renders a single frame** that is visible in the compositor (with magenta SHM tint). However, rendering is intermittent -- the SHM buffer import works in some builds but not others due to a Smithay cached-state timing issue that needs further investigation.

**GTK3 GPU rendering is fully working** - confirmed with gtk3-widget-factory rendering via AHB buffers through our tawc-egl wrapper.

## What Works

1. **Firefox installs and launches** - `pacman -S firefox` in the chroot, runs with `MOZ_ENABLE_WAYLAND=1`
2. **Wayland connection** - Firefox connects, binds all required globals (wl_compositor, xdg_wm_base, wl_shm, wl_seat, wl_output, wl_data_device_manager, tawc_buffer_manager_v1)
3. **Window creation** - Creates xdg_toplevel with title "Mozilla Firefox" (requires pointer capability on wl_seat -- see fixes below)
4. **SHM buffer rendering** - Firefox renders via software (SHM buffers), visible with magenta tint in compositor. First frame confirmed 972x1040.
5. **Multi-process** - Firefox content processes connect as separate Wayland clients
6. **Stability** - Firefox runs without crashes (with all sandbox vars disabled)
7. **GTK3 GPU rendering** - gtk3-widget-factory renders correctly via AHB (confirmed working screenshot)

## Compositor Fixes Made

### 1. wl_seat pointer capability (CRITICAL for Firefox)

**Problem:** Firefox refuses to create an xdg_toplevel window if the compositor's wl_seat advertises 0 capabilities (no pointer, keyboard, or touch). The Wayland debug showed Firefox binding globals and creating surfaces but never calling `xdg_wm_base.get_xdg_surface()`.

**Fix:** Added `seat.add_pointer()` in `compositor.rs` TawcState::new(). Now the seat advertises `capabilities(1)` (WL_SEAT_CAPABILITY_POINTER), and Firefox proceeds to create its window.

### 2. Physical output dimensions

**Problem:** Output physical size was (0, 0) mm, which could cause Firefox to compute 0 DPI.

**Fix:** Changed to (68, 150) mm in `lib.rs` (approximate for a 6.7" 1080x2400 phone).

### 3. SHM buffer import via commit callback

**Problem:** The render-loop approach of checking `cached_state.get::<SurfaceAttributes>().current().buffer` for `BufferAssignment::NewBuffer` was unreliable -- the buffer field was always `None` by the time the render loop ran.

**Partial fix:** Added capture of SHM buffers in the `CompositorHandler::commit()` callback, queuing them in `pending_shm_commits` for the render loop to import with renderer access. Also added a surface tree scan fallback. This worked intermittently -- one build successfully imported a 972x1040 SHM frame, but subsequent builds showed `current=None pending=None` in the commit handler with only 3-4 commits total per Firefox session.

**Root cause unknown:** Smithay's transaction system applies state before calling `commit()`, so `current().buffer` should have the buffer. But it doesn't consistently. Possible causes:
- Firefox's Wayland proxy multiplexing multiple process connections into different compositor clients
- Smithay transaction ordering with multiple concurrent clients
- The buffer attach+commit arriving on a different client connection than the one owning the toplevel surface

### 4. SHM texture vertical flip

**Problem:** SHM buffers rendered upside-down (toolbar at bottom instead of top).

**Fix:** Changed SHM rendering transform from `Transform::Normal` to `Transform::Flipped180`. This is correct because SHM buffers have top-left origin while GL textures have bottom-left origin. AHB textures (GL_TEXTURE_EXTERNAL_OES) handle Y-flip internally in the driver, so they remain `Transform::Normal`.

### 5. Frame callbacks for SHM surfaces

**Problem:** Firefox needs frame callbacks on its rendering surfaces to know when to submit new frames. The compositor only sent callbacks to toplevel surface trees, which might miss standalone SHM surfaces.

**Fix:** Added frame callback sending to all surfaces in `surface_shm` in addition to toplevel trees.

## Key Findings

### Firefox falls back to software rendering

LD_DEBUG tracing confirmed: **Firefox never dlopen's libEGL.so at all** in our environment. It only loads `libwayland-egl.so.1`. Firefox normally uses GL/Vulkan via WebRender on most systems, but something in our environment causes its GPU detection to fail, so it falls back to SHM (software) rendering.

Possible reasons Firefox doesn't attempt EGL:
- Our tawc-egl wrapper may not be detected during Firefox's GPU capability probing
- Firefox may probe EGL before `LD_LIBRARY_PATH` takes effect (e.g., during early startup)
- The libhybris EGL may report capabilities that Firefox considers unsupported
- Firefox's `about:support` Graphics section would reveal the exact reason, but we can't access it without a working interactive session

Getting Firefox to use GPU rendering via our tawc-egl wrapper remains a goal -- SHM is a stepping stone.

### Firefox Wayland Proxy

Firefox 149 uses a built-in Wayland proxy (`libmozwayland.so`). During early testing, the proxy reported:
```
Wayland Proxy Error: Failed to connect to Wayland display '/data/data/me.phie.tawc/wayland-0' error: No such file or directory
```

This was likely caused by the compositor data dir bind mount not being set up yet, not by the absolute path itself. The error resolved when the chroot mounts were properly established. We currently use a symlink at `/tmp/wayland-0` for convenience, but absolute paths should work too as long as the socket is accessible inside the chroot.

### Mount table explosion (FIXED)

The `arch-chroot-run` script caused exponential mount accumulation (24K+ mounts observed) due to two issues:
1. `mountpoint -q` doesn't detect file bind mounts, causing re-mounting on every invocation
2. `mount --rbind /apex` with shared propagation creates feedback loops where new mounts on the host cascade through all bind copies

**Fix:**
- Replaced `mountpoint -q` with `grep -q ' $path ' /proc/mounts` for reliable detection
- Added `rslave` propagation to all bind mounts (`mount -o bind,rslave` / `mount -o rbind,rslave`) to prevent feedback loops. Uses toybox's `-o` syntax since Android's mount doesn't support `--make-rslave`.

## Launch Commands

### GTK3 apps (working, GPU-accelerated):
```bash
export WAYLAND_DISPLAY=wayland-0
export XDG_RUNTIME_DIR=/tmp
export LD_LIBRARY_PATH=/tmp/tawc-wsi:/usr/local/lib
export LD_PRELOAD=/tmp/memfd-selinux-shim/libmemfd-selinux-shim.so
export HYBRIS_PATCH_TLS=1
export HOME=/root
export GDK_GL=gles:always
gtk3-widget-factory
```

### Firefox (SHM rendering, partially working):
```bash
export WAYLAND_DISPLAY=wayland-0
export XDG_RUNTIME_DIR=/tmp
export LD_LIBRARY_PATH=/tmp/tawc-wsi:/usr/local/lib
export LD_PRELOAD=/tmp/memfd-selinux-shim/libmemfd-selinux-shim.so
export HYBRIS_PATCH_TLS=1
export MOZ_ENABLE_WAYLAND=1
export HOME=/root
export GDK_GL=gles
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1
export DISPLAY=
# Symlink must exist: ln -sf /data/data/me.phie.tawc/wayland-0 /tmp/wayland-0
firefox --no-remote
```

## Known Issues

### Keyboard support
`seat.add_keyboard()` crashes the compositor because Smithay uses xkbcommon which needs keymap data files. Possible fixes:
- Bundle minimal xkb data in the APK assets
- Create a custom keymap in code without relying on system files
- Use `XKB_DEFAULT_RULES=""` or similar env vars
- Cross-compile xkbcommon with embedded data

### Firefox sandbox
All Firefox sandbox must be disabled (`MOZ_DISABLE_CONTENT_SANDBOX=1` etc.). The chroot environment doesn't support the clone/namespace operations Firefox's sandbox requires.

## Next Steps (Priority Order)

1. **Fix SHM buffer import reliability** - The core blocker. The `commit()` callback intermittently fails to see `BufferAssignment::NewBuffer`. Investigate:
   - Add persistent logging (not just first N commits) to catch the buffer when it appears
   - Check if Firefox's proxy creates multiple Wayland connections and the buffer arrives on a non-toplevel client
   - Consider tracking ALL surfaces across ALL clients (not just toplevel surfaces) for SHM import
   - Look at how Smithay's built-in `RendererSurfaceState` handles buffer tracking -- it uses `attrs.buffer.take()` in a surface tree traversal

2. **Add keyboard support** - Needed for any interactive use of Firefox

3. **Investigate frame callback flow** - Firefox may need continuous frame callbacks to keep rendering. Verify that the frame callback path works correctly for both the toplevel surface and its subsurfaces.

4. **Consider GPU rendering** - Long-term, getting Firefox WebRender to use our tawc-egl wrapper for hardware-accelerated rendering would be ideal. This would require either:
   - Placing our libEGL.so where Firefox expects the system one (`/usr/lib/libEGL.so`)
   - Or using `LD_PRELOAD` with our wrapper
   - Firefox would need to detect a usable GLES driver (currently it falls back to software)
