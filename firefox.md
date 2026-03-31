# Firefox on tawc - Progress Report

## Current Status

Firefox 149 (Arch Linux ARM aarch64) is installed in the chroot and **successfully connects to the compositor, creates a toplevel window, and binds the tawc_buffer AHB protocol**. However, no AHB buffers actually flow - Firefox's WebRender doesn't use our EGL wrapper because it dlopen's libEGL through its own code path (libmozwayland.so / WebRender), not through GTK's EGL layer which our wrapper intercepts.

**GTK3 GPU rendering is fully working** - confirmed with gtk3-widget-factory rendering via AHB buffers through our tawc-egl wrapper.

## What Works

1. **Firefox installs and launches** - `pacman -S firefox` in the chroot, runs with `MOZ_ENABLE_WAYLAND=1`
2. **Wayland connection** - Firefox connects, binds all required globals (wl_compositor, xdg_wm_base, wl_shm, wl_seat, wl_output, wl_data_device_manager, tawc_buffer_manager_v1)
3. **Window creation** - Creates xdg_toplevel, compositor sees 1 toplevel
4. **AHB channel creation** - Firefox (via GTK) creates 1 AHB channel through the tawc_buffer protocol
5. **Multi-process** - Firefox's content processes start correctly (multiple cursor pools created)
6. **Stability** - Firefox runs indefinitely without crashes (with all sandbox vars disabled)
7. **GTK3 GPU rendering** - gtk3-widget-factory renders correctly via AHB (confirmed working screenshot)

## What Doesn't Work Yet

**Firefox renders nothing visible.** The AHB channel exists but no buffers flow through it. Firefox's WebRender (which does the actual pixel rendering) doesn't pick up our tawc-egl wrapper. The `[tawc-egl]` log messages that appear for GTK3 apps are completely absent from Firefox's output.

**Root cause:** Firefox has its own EGL loading code in `libmozwayland.so` and WebRender. It doesn't go through GTK's EGL path (which is what our `LD_LIBRARY_PATH` wrapper intercepts). Firefox likely dlopen's `libEGL.so` or `libEGL.so.1` directly, possibly from a hardcoded path or via its own library search.

## Changes Made

### 1. ashmem-shim: Over-allocation for resize support (`client/ashmem-shim/ashmem-shim.c`)

**Problem:** Android's ashmem driver only allows `ASHMEM_SET_SIZE` once before the first mmap. Wayland cursor pools start small (2304 bytes) and grow (to 587520+) via `posix_fallocate`/`ftruncate`. The original shim called `ASHMEM_SET_SIZE` on every resize, which failed on all but the first call, causing an infinite retry loop that blocked Firefox startup.

**Fix:** On first `ASHMEM_SET_SIZE`, over-allocate to at least 4MB (`ASHMEM_MIN_ALLOC`). Track per-fd `allocated_size` and `logical_size`. Subsequent resize calls check if the requested size fits within the pre-allocated space and return success without calling the ioctl again. Also intercept `fstat` to report the logical size.

### 2. ashmem-shim: Selective memfd interception (`client/ashmem-shim/ashmem-shim.c`)

**Problem:** The original shim intercepted ALL `memfd_create` calls. Firefox uses memfds internally for IPC between its processes (`mozilla-ipc`, `mozilla-ipc-test`). Redirecting these to ashmem caused Firefox's IPC mechanism to crash with a SIGSEGV (null pointer dereference in the content process).

**Root cause:** ashmem has different semantics from memfd - it can't be resized after first mmap, sealing doesn't work, and behavior with shared mappings may differ. Firefox's IPC relies on memfd semantics that ashmem doesn't support.

**Fix:** Changed from intercepting everything to a denylist approach. Only `memfd_create` calls with names matching `mozilla-ipc*` are passed through to the real `memfd_create` syscall. All other memfds (including `wayland-cursor`, `wayland-shm`, `gdk-wayland`, `weston-shared`, etc.) are redirected to ashmem for cross-process SELinux compatibility.

### 3. libhybris execstack fix (re-applied)

**Problem:** The `libhybris-common.so.1` symlink was pointing to the `.bak` file instead of the main `.so`. Also, `patchelf --clear-execstack` needed to be re-applied after a rebuild.

**Fix:** Fixed symlink: `ln -sf libhybris-common.so.1.0.0 libhybris-common.so.1`, then `patchelf --clear-execstack libhybris-common.so.1.0.0`.

## Confirmed Findings

### SHM buffer data is all zeros for real (non-ashmem) memfds

When a client creates a real memfd (not intercepted by the ashmem shim), the compositor can mmap the fd but reads all zeros. Confirmed by logging buffer contents:
- **ashmem-backed (weston-shared):** `total_nonzero=244352` out of 250000 bytes - WORKS
- **real memfd (gdk-wayland):** `total_nonzero=0` - ALL ZEROS even with `setenforce 0`

This is NOT an SELinux type-enforcement issue (permissive mode doesn't help). It appears to be an Android kernel MLS/category restriction on tmpfs. The ashmem path works because ashmem fds have the `mlstrustedobject` SELinux label which bypasses MLS checks.

**Conclusion:** The ashmem shim is REQUIRED for all cross-process shared memory buffers. The denylist approach (intercept everything except `mozilla-ipc*`) is the correct design.

### Firefox doesn't use our EGL wrapper

Firefox's rendering pipeline:
1. GTK3 layer: Uses our tawc-egl wrapper (creates AHB channel) - but GTK3 only handles the window chrome
2. WebRender (content): Has its own EGL loading code, does NOT go through our `LD_LIBRARY_PATH` wrapper
3. Result: AHB channel created but empty, no buffers flow

Evidence: Zero `[tawc-egl]` log messages in Firefox's output, while GTK3 apps produce many.

## Known Issues

### Keyboard support
`seat.add_keyboard()` crashes the compositor because Smithay uses xkbcommon which needs keymap data files. Possible fixes:
- Bundle minimal xkb data in the APK assets
- Create a custom keymap in code without relying on system files
- Use `XKB_DEFAULT_RULES=""` or similar env vars
- Cross-compile xkbcommon with embedded data

### Firefox sandbox
All Firefox sandbox must be disabled (`MOZ_DISABLE_CONTENT_SANDBOX=1` etc.). The chroot environment doesn't support the clone/namespace operations Firefox's sandbox requires.

## Launch Commands

### GTK3 apps (working, GPU-accelerated):
```bash
export WAYLAND_DISPLAY=/data/data/me.phie.tawc/wayland-0
export LD_LIBRARY_PATH=/tmp/tawc-wsi:/usr/local/lib
export LD_PRELOAD=/tmp/ashmem-shim/libashmem-shim.so
export HYBRIS_PATCH_TLS=1
export HOME=/root
export XDG_RUNTIME_DIR=/tmp
export GDK_GL=gles:always
gtk3-widget-factory
```

### Firefox (launches, creates window, but no visible rendering yet):
```bash
export WAYLAND_DISPLAY=/data/data/me.phie.tawc/wayland-0
export LD_LIBRARY_PATH=/tmp/tawc-wsi:/usr/local/lib
export LD_PRELOAD=/tmp/ashmem-shim/libashmem-shim.so
export HYBRIS_PATCH_TLS=1
export MOZ_ENABLE_WAYLAND=1
export HOME=/root
export XDG_RUNTIME_DIR=/tmp
export GDK_GL=gles
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1
firefox --no-remote
```

## Next Steps (Priority Order)

1. **Make Firefox use our EGL wrapper** - The key blocker. Options:
   - Investigate how Firefox/WebRender loads libEGL (dlopen path, RPATH, etc.)
   - Possibly need to place our libEGL.so where Firefox looks for it (e.g., `/usr/lib/firefox/` or system lib path)
   - Or intercept Firefox's EGL loading via `LD_PRELOAD` with a more targeted approach
   - Check if `MOZ_GFX_BACKEND` or other env vars can force Firefox to use a specific EGL path
   - Look at Firefox's `about:support` (Graphics section) to see what rendering backend it's using

2. **Add keyboard support** - Needed for any interactive use of Firefox

3. **Consider SHM fallback for Firefox** - As a temporary measure, if we can make the SHM path work (all ashmem), Firefox could render via software while we work on GPU support
