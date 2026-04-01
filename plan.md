# Implementation Plan

## Phase 1: Build Toolchain & EGL Proof ✅ (2026-03-28)
1. ✅ Android app scaffold with SurfaceView
2. ✅ Cross-compile toolchain: cargo-ndk, NDK, libxkbcommon
3. ✅ Rust JNI library: ANativeWindow -> EGL context via Smithay
4. ✅ Render solid color via GlesRenderer + `eglSwapBuffers`

## Phase 2: AHB Buffer Sharing ✅ (2026-03-31)
5. ✅ libhybris in chroot loading stock EGL/GLES (solved bionic TLS conflict)
6. ✅ AHB allocate, CPU-fill, send over Unix socket
7. ✅ Compositor receives AHB, imports as GL texture, displays
8. ✅ Cross-process buffer round-trip confirmed

## Phase 3: Wayland Server ✅ (2026-03-31)
9. ✅ Smithay patched (`libEGL.so.1` -> `libEGL.so`)
10. ✅ `tawc_buffer_v1` custom protocol for AHB lifecycle
11. ✅ Wayland display, xdg_shell, wl_output, listening socket
12. ✅ Client connects from chroot, AHB -> GL texture -> composite -> present

## Phase 4: Robust EGL WSI Layer ✅ (2026-03-31)
13. ✅ All 44 EGL 1.5 functions exported, universal forwarding
14. ✅ Thread safety, TLS tracking, extension handling
15. ✅ Buffer age, resize, surface slot reuse
16. ✅ GTK3 compatibility (libepoxy, data_device_manager stub)
17. ✅ Tested: weston-simple-egl, gtk3-widget-factory (SHM + GL)

## Phase 5: Touch Input ✅ (2026-04-01)
18. ✅ Android MotionEvent -> JNI -> calloop channel -> wl_touch
19. ✅ Multi-touch, coordinate transform (physical / scale -> logical)
20. ✅ Immersive fullscreen (no dead zones)

## Phase 6: Text Input
Bridges Android InputConnection (soft keyboard) to `zwp_text_input_v3`.
See [notes/text-input.md](notes/text-input.md) for design.

**6a. Custom text-input-v3 handler**
21. Implement `zwp_text_input_manager_v3` / `zwp_text_input_v3` directly
    (Smithay's built-in requires input-method Wayland client; ours is Android IME)
22. Track instances per client, manage focus (enter/leave)
23. Handle client requests: enable, disable, surrounding_text, content_type, commit

**6b. Android InputConnection**
24. `TawcInputConnection` extending `BaseInputConnection`
25. commitText -> commit_string + done
26. setComposingText -> preedit_string + done
27. deleteSurroundingText / sendKeyEvent(backspace) -> delete_surrounding_text + done
28. SurfaceView focusable, returns TawcInputConnection from onCreateInputConnection

**6c. Keyboard show/hide**
29. Reverse JNI channel (compositor -> Android)
30. Client enable -> showSoftInput; disable -> hideSoftInputFromWindow
31. Map set_content_type -> EditorInfo.inputType
32. Feed set_surrounding_text back to InputConnection

**6d. Polish**
33. Edge cases: focus changes during composition, multiple instances
34. Test with Firefox, foot, GTK apps

## Phase 7: wl_keyboard (non-text keys)
Arrow keys, escape, tab, Ctrl+C/V/Z need wl_keyboard (no text-input-v3 equivalent).

35. Solve xkbcommon on Android (XKB_CONFIG_ROOT -> chroot, or embed keymap)
36. seat.add_keyboard() with US layout
37. Map Android key events to wl_keyboard scancodes
38. Modifier state tracking, Bluetooth keyboard support

## Phase 8: Multi-Window
39. JNI callback for new xdg_toplevels -> spawn Activities
40. One SurfaceView/EGLSurface per Activity
41. Window lifecycle (map, unmap, close, resize)
42. Popups composited onto parent (not separate Activities)

## Phase 9: Polish & Protocols
43. Server-side decorations (xdg-decoration)
44. Cursor rendering
45. Fractional scaling (wp-fractional-scale)
46. Clipboard bridge (wl_data_device <-> Android ClipboardManager)
47. Non-root socket sharing (Binder fd passing)

## Phase 10: Vulkan WSI (stretch goal)
48. Verify libhybris Vulkan with stock driver
49. Vulkan implicit layer using VK_ANDROID_external_memory_android_hardware_buffer
50. Test with vkcube, vkmark
