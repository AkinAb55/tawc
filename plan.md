# Implementation Plan

## Build Toolchain & EGL Proof ✅ (2026-03-28)
- ✅ Android app scaffold with SurfaceView
- ✅ Cross-compile toolchain: cargo-ndk, NDK, libxkbcommon
- ✅ Rust JNI library: ANativeWindow -> EGL context via Smithay
- ✅ Render solid color via GlesRenderer + `eglSwapBuffers`

## AHB Buffer Sharing ✅ (2026-03-31)
- ✅ libhybris in chroot loading stock EGL/GLES (solved bionic TLS conflict)
- ✅ AHB allocate, CPU-fill, send over Unix socket
- ✅ Compositor receives AHB, imports as GL texture, displays
- ✅ Cross-process buffer round-trip confirmed

## Wayland Server ✅ (2026-03-31)
- ✅ Smithay patched (`libEGL.so.1` -> `libEGL.so`)
- ✅ `tawc_buffer_v1` custom protocol for AHB lifecycle
- ✅ Wayland display, xdg_shell, wl_output, listening socket
- ✅ Client connects from chroot, AHB -> GL texture -> composite -> present

## Robust EGL WSI Layer ✅ (2026-03-31)
- ✅ All 44 EGL 1.5 functions exported, universal forwarding
- ✅ Thread safety, TLS tracking, extension handling
- ✅ Buffer age, resize, surface slot reuse
- ✅ GTK3 compatibility (libepoxy, data_device_manager stub)
- ✅ Tested: weston-simple-egl, gtk3-widget-factory (SHM + GL)

## Touch Input ✅ (2026-04-01)
- ✅ Android MotionEvent -> JNI -> calloop channel -> wl_touch
- ✅ Multi-touch, coordinate transform (physical / scale -> logical)
- ✅ Immersive fullscreen (no dead zones)

## Text Input
Bridges Android InputConnection (soft keyboard) to `zwp_text_input_v3`.
See [notes/text-input.md](notes/text-input.md) for design.

**Custom text-input-v3 handler** ✅
- ✅ Implement `zwp_text_input_manager_v3` / `zwp_text_input_v3` directly
    (Smithay's built-in requires input-method Wayland client; ours is Android IME)
- ✅ Track instances per client, manage focus (enter/leave)
- ✅ Handle client requests: enable, disable, surrounding_text, content_type, commit

**Android InputConnection** ✅
- ✅ `TawcInputConnection` extending `BaseInputConnection`
- ✅ commitText -> commit_string + done
- ✅ setComposingText -> preedit_string + done
- ✅ deleteSurroundingText / sendKeyEvent(backspace) -> delete_surrounding_text + done
- ✅ SurfaceView focusable, returns TawcInputConnection from onCreateInputConnection

**Keyboard show/hide** ✅
- ✅ Reverse JNI channel (compositor -> Android via cached JavaVM + GlobalRef)
- ✅ Client enable -> showSoftInput; disable -> hideSoftInputFromWindow
- Map set_content_type -> EditorInfo.inputType (deferred: basic type works)
- Feed set_surrounding_text back to InputConnection (deferred: basic input works without it)

**Polish**
- Edge cases: focus changes during composition, multiple instances
- Test with Firefox, foot, GTK apps

## Testing Infrastructure ✅
- ✅ GTK3 debug app (`testing/gtk3-debug-app/`) -- C, built in chroot
- ✅ Integration test harness (`testing/integration/`) -- Rust, runs on host
- ✅ Broadcast-based input injection (reliable, bypasses IME)
- ✅ Text input tests (basic text, backspace, multi-word)
See [notes/testing.md](notes/testing.md) for details.

## libhybris CFI Workaround ✅ (2026-04-11)
- ✅ `hybris/common/q/cfi_bypass.c` finds `__cfi_slowpath` via libdl.so's dynsym and patches it to `ret`
- ✅ Called from `android_linker_init()` (early) and `link_image()` (after each lib loads, where libdl is finally mapped); idempotent via static flag
- ✅ 16K-page safe (`sysconf(_SC_PAGESIZE)`), no W+X window (RW → write → RX mprotect sequence)
- ✅ Removed `patch_bionic_cfi()` and `<sys/mman.h>` include from `client/tawc-wsi/tawc-egl.c`
- ✅ Integration tests pass (text-input, click-cursor, firefox); documented in `libhybris/TAWC_FORK.md`

## Migrate to libhybris Wayland EGL Platform
Replace our custom `tawc-egl.c` WSI layer and `tawc_buffer_v1` protocol with libhybris's built-in
Wayland EGL platform (`eglplatform_wayland.so`) and `android_wlegl` buffer sharing protocol.
Eliminates ~1500 lines of custom EGL wrapping, the GL shim libraries, `LD_LIBRARY_PATH` overrides,
and our custom Wayland protocol. Gains triple buffering, vsync, damage forwarding, proper resize.
See [issues/migrate-to-libhybris-wayland-platform.md](issues/migrate-to-libhybris-wayland-platform.md) for full plan.

- Build libhybris with `--enable-wayland --enable-glvnd`
- Implement `android_wlegl` protocol server-side in compositor (Rust)
- C helper wraps libhybris gralloc + `RemoteWindowBuffer` for buffer import (native_handle_t → EGLClientBuffer → EGLImage)
- Update chroot environment (`HYBRIS_EGLPLATFORM=wayland`, remove `/tmp/tawc-wsi`)
- Delete tawc-egl.c, GL shims, tawc_buffer_v1 protocol, AHB side-channel socket code

## Vulkan WSI
libhybris has built-in Wayland Vulkan WSI (`vulkanplatform_wayland.so`). It intercepts
`vkCreateWaylandSurfaceKHR`, remaps to `vkCreateAndroidSurfaceKHR`, and uses the same
`android_wlegl` protocol for buffer sharing. No custom implicit layer needed — once the
Wayland platform migration is done and the compositor serves `android_wlegl`, Vulkan
clients should work via `HYBRIS_VULKANPLATFORM=wayland`.

- Verify `vulkanplatform_wayland.so` is built by `--enable-wayland`
- Test with vkcube, vkmark
- GPU synchronization: Android Vulkan driver handles fences internally via `android_wlegl`
- Format negotiation: which VkFormats map to gralloc formats the compositor can import?
- Real apps: Firefox WebGPU, games

## wl_keyboard (non-text keys)
Arrow keys, escape, tab, Ctrl+C/V/Z need wl_keyboard (no text-input-v3 equivalent).

- Solve xkbcommon on Android (XKB_CONFIG_ROOT -> chroot, or embed keymap)
- seat.add_keyboard() with US layout
- Map Android key events to wl_keyboard scancodes
- Modifier state tracking, Bluetooth keyboard support

## Multi-Window
- JNI callback for new xdg_toplevels -> spawn Activities
- One SurfaceView/EGLSurface per Activity
- Window lifecycle (map, unmap, close, resize)
- Popups composited onto parent (not separate Activities)

## Polish & Protocols
- Server-side decorations (xdg-decoration)
- Cursor rendering
- Fractional scaling (wp-fractional-scale)
- Clipboard bridge (wl_data_device <-> Android ClipboardManager)
- Non-root socket sharing (Binder fd passing)
