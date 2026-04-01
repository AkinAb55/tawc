# Firefox

Firefox 149 WebRender renders via GPU: WebRender -> tawc-egl -> libhybris -> Adreno 660.
Main content uses AHB at 972x1040; a small SHM subsurface exists for GTK decorations.

## Launching

```bash
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run \
  'GDK_GL=disabled MOZ_ENABLE_WAYLAND=1 MOZ_ACCELERATED=1 \
   MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
   MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 \
   DISPLAY= firefox --no-remote'"
```

Firefox-specific env vars (not in the chroot profile because they're app-specific):
- `GDK_GL=disabled` -- prevents GTK from creating a competing EGL surface
- `MOZ_ENABLE_WAYLAND=1` -- use Wayland backend (not X11)
- `MOZ_ACCELERATED=1` -- force hardware acceleration
- `MOZ_DISABLE_*_SANDBOX=1` -- chroot lacks namespace support for sandboxing
- `DISPLAY=` -- clear X11 display so Firefox doesn't try X11

## tawc-egl Fixes for Firefox

1. **glxtest probe** -- Firefox's `glxtest -w` hard-requires `eglQueryDeviceStringEXT`
   (from `EGL_EXT_device_query`), which Android drivers don't implement. Added a stub.

2. **Context attributes** -- Firefox requests robustness extensions that Adreno rejects.
   Stripped in the attribute filter alongside desktop-GL attribute stripping.

3. **GL symbol resolution** -- Firefox resolves GL functions via `dlsym` on `libGL.so.1`,
   NOT `eglGetProcAddress`. Fixed by placing symlinks in `/tmp/tawc-wsi/` pointing
   `libGL.so.1` and `libGLESv2.so.2` to libhybris's GLES.

**Other changes:**
- Removed `-lGLESv2` link dependency. GL functions resolved lazily via `eglGetProcAddress`.
- `eglTerminate` is a no-op (Firefox terminate+reinitialize cycles invalidated the stock display).
- `eglChooseConfig` ensures `EGL_RENDERABLE_TYPE` includes `EGL_OPENGL_ES2_BIT`.
- Added `eglSetDamageRegionKHR` stub.

## Known Issues

- All Firefox sandboxing disabled. The chroot doesn't support clone/namespace operations.
- `setenforce 0` required (GDK's memfds bypass the LD_PRELOAD SELinux shim).
- If Firefox connects but shows a black screen, check SELinux: `adb shell su -c getenforce`
  (resets to Enforcing on every device reboot).

## Killing and Restarting

```bash
adb shell su -c "killall firefox"
```

The wayland flush shim (`libwayland-flush-shim.so`) is no longer needed.
