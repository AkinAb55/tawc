# Firefox

Firefox 149 WebRender renders via GPU: WebRender -> tawc-egl -> libhybris -> Adreno 660.
All surfaces (main content + GTK decorations) use AHB hardware buffers.

## Launching

```bash
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run \
  'GDK_GL=gles:always MOZ_ENABLE_WAYLAND=1 MOZ_ACCELERATED=1 \
   MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
   MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 \
   DISPLAY= firefox --no-remote'"
```

Firefox-specific env vars (not in the chroot profile because they're app-specific):
- `GDK_GL=gles:always` -- forces GTK to use GLES (not desktop GL) for rendering
- `MOZ_ENABLE_WAYLAND=1` -- use Wayland backend (not X11)
- `MOZ_ACCELERATED=1` -- force hardware acceleration
- `MOZ_DISABLE_*_SANDBOX=1` -- chroot lacks namespace support for sandboxing
- `DISPLAY=` -- clear X11 display so Firefox doesn't try X11

### Why GDK_GL=gles:always (not disabled)

Previously `GDK_GL=disabled` was used, which made GTK render decorations/popups via
SHM (CPU). With `gles:always`, GTK uses GLES via our EGL wrapper, producing AHB buffers
for everything -- zero SHM fallback.

This requires the libGLESv2 shim (see "GL Library Shims" below) to provide GLX stubs.
Without the stubs, libepoxy (GTK's GL dispatch) aborts when probing for GLX symbols in
a GLES-only library.

## GL Library Shims

Android has GLES only -- no desktop GL, no GLX. But Firefox and GTK have conflicting
GL library requirements:

- **Firefox (WebRender):** `dlsym(libGL_handle, "glBindTexture")` -- resolves GLES
  symbols from `libGL.so`. Without it in `LD_LIBRARY_PATH`, Firefox loads the system
  Mesa `/usr/lib/libGL.so` which doesn't work with the Android GPU.

- **GTK/libepoxy:** `dlsym(libGLESv2_handle, "glXGetCurrentContext")` -- probes for
  GLX to detect context type. Aborts if the probe produces an error (vs returning NULL).

**Solution:** Shim libraries in `/tmp/tawc-wsi/` that re-export libhybris GLES symbols
AND provide GLX stubs returning NULL (indicating "no GLX context, use EGL"):

```
libGL.so         -- shim: GLX stubs + DT_NEEDED libGL.so.1
libGL.so.1       -> libGLESv2_real.so (symlink)
libGLESv2.so.2   -- shim: GLX stubs + DT_NEEDED libGLESv2_real.so
libGLESv2.so     -> libGLESv2.so.2 (symlink)
libGLESv2_real.so -- actual libhybris GLES (soname patched to break cycle)
```

The real libhybris library is renamed to `libGLESv2_real.so` (with `patchelf --set-soname`)
to prevent circular DT_NEEDED resolution.

Built by `client/tawc-wsi/build`. Source: `libgl-shim.c`, `libglesv2-shim.c`.

## tawc-egl Fixes for Firefox

1. **glxtest probe** -- Firefox's `glxtest -w` hard-requires `eglQueryDeviceStringEXT`
   (from `EGL_EXT_device_query`), which Android drivers don't implement. Added a stub.

2. **Context attributes** -- Firefox requests robustness extensions that Adreno rejects.
   Stripped in the attribute filter alongside desktop-GL attribute stripping.

3. **GL symbol resolution** -- Firefox resolves GL functions via `dlsym` on `libGL.so`,
   NOT `eglGetProcAddress`. Handled by the libGL.so shim above.

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
