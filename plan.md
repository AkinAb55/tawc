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

## Migrate to libhybris Wayland EGL Platform ✅ (2026-04-15)
- ✅ libhybris built with `--enable-wayland --disable-wayland_serverside_buffers` (no glvnd; pulling Mesa GLX in breaks Firefox).
- ✅ `android_wlegl` protocol dispatch in compositor (Rust + ~50-line C helper calling `AHardwareBuffer_createFromHandle`)
- ✅ libhybris fork: AHB gralloc backend (`AHardwareBuffer_*` via libnativewindow.so) to produce modern-format handles — without it the stock vendor gralloc1 path returns handles the Android-side mapper rejects on Android 12+ devices. Plus shared-queue and `queueBuffer`-attach patches needed by Firefox/Adreno. See `libhybris/TAWC_FORK.md`.
- ✅ Chroot env: `HYBRIS_EGLPLATFORM=wayland`, `LD_LIBRARY_PATH=/tmp/gl-shims:/usr/local/lib`.
- ✅ Dead code removed: `client/tawc-wsi/` directory (tawc-egl.c ~1500 lines), `server/compositor/protocols/tawc_buffer_v1.xml`, `src/ahb.rs`, the Unix-socket side-channel, the `surface_ahb` HashMap, the legacy `import_pending_ahbs` render path.
- ✅ Tiny GL shims (`client/libgl-shim.c`, `client/libglesv2-shim.c`, ~30 lines each) built as part of `bash client/build-libhybris`. Firefox/glxtest and GTK/libepoxy probe libGL.so/libGLESv2.so by name and need GLX symbols stubbed so Mesa GLX (broken in chroot) doesn't get reached. See `notes/wsi-layer.md` "Why GL shims still exist".
- ✅ Integration tests (text-input SHM, click-cursor AHB, firefox AHB) all pass.

## Vulkan WSI ✅ (2026-04-20)
libhybris has built-in Wayland Vulkan WSI (`vulkanplatform_wayland.so`). It intercepts
`vkCreateWaylandSurfaceKHR`, remaps to `vkCreateAndroidSurfaceKHR`, and uses the same
`android_wlegl` protocol for buffer sharing. No custom implicit layer needed — once the
Wayland platform migration is done and the compositor serves `android_wlegl`, Vulkan
clients should work via `HYBRIS_VULKANPLATFORM=wayland`.

- ✅ `vulkan` subdir built by `bash client/build-libhybris`; installs `libvulkan.so.1`
  (shadows `vulkan-icd-loader` via `LD_LIBRARY_PATH`) and `libhybris/vulkanplatform_wayland.so`
- ✅ `vulkan.c` compiles with vulkan-headers 1.4.341 (Cuda NV extension guard switched
  from `VK_HEADER_VERSION >= 269` to `#ifdef VK_NV_cuda_kernel_launch` — the NV Cuda
  symbols got pulled from the C core headers in recent vulkan-headers releases)
- ✅ `vulkaninfo --summary` passes: Adreno Vulkan driver loaded via `android_dlopen`,
  `VK_KHR_wayland_surface` advertised, integration test `test_vulkaninfo_loads_android_driver`
- ✅ `vkcube` renders correctly on OnePlus 9. Two libhybris fixes required:
  (a) `NATIVE_WINDOW_BUFFER_AGE=0` (committed as the Firefox flicker fix; Adreno's
  Vulkan WSI was treating the hardcoded age=2 as "preserved content"), and (b)
  spec-correct `currentExtent = {0xFFFFFFFF, 0xFFFFFFFF}` (undefined extent) +
  `vkCreateSwapchainKHR` interception to resize `WaylandNativeWindow` to match
  the app's `imageExtent`. The dispatch in `vulkan.c` intercepts `vkGetDeviceProcAddr`
  and `vkGetInstanceProcAddr` to hook swapchain creation and surface capabilities.
- GPU synchronization: Android Vulkan driver handles fences internally via `android_wlegl`
- Format negotiation: which VkFormats map to gralloc formats the compositor can import?
- Real apps: Firefox WebGPU, games

## Multi-Window
See [notes/multi-activity.md](notes/multi-activity.md) for the full design.

- Move compositor into a foreground `CompositorService`
- Refactor to `OutputHost` (vec of length 1, no behaviour change)
- `toplevel_to_host` assignment table, single host
- Policy + reverse-JNI to spawn Activities, gated off behind
  `single_activity_mode = true`
- `CompositorActivity` per-document (`activityId` UUID in `intent.data`)
- Flip the policy: spawn a new Activity per non-child toplevel
- Per-host input/focus; fixes `touch-focus-single-window-only`
- Lifecycle + suspend round-trip (Background hosts stop being fed
  frame callbacks; Foreground/Background transitions send the
  appropriate xdg-shell configures)
- Polish: task labels/icons, refused-close handling, settings UI for
  single-Activity mode, freeform story

## wl_keyboard (non-text keys)
Arrow keys, escape, tab, Ctrl+C/V/Z need wl_keyboard (no text-input-v3 equivalent).

- Solve xkbcommon on Android (XKB_CONFIG_ROOT -> chroot, or embed keymap)
- seat.add_keyboard() with US layout
- Map Android key events to wl_keyboard scancodes
- Modifier state tracking, Bluetooth keyboard support

## Polish & Protocols
- Server-side decorations (xdg-decoration)
- Cursor rendering
- Fractional scaling (wp-fractional-scale)
- Clipboard bridge (wl_data_device <-> Android ClipboardManager)
- Non-root socket sharing (Binder fd passing)
