# Firefox

Firefox 149 renders via the libhybris Wayland EGL platform against the Adreno
660 vendor driver. Window chrome goes through AHB; fragments of content can
fall back to SHM (no `zwp_linux_dmabuf_v1` support yet in this compositor).

**Known issue:** on this chroot Firefox reliably lands on its "Firefox closed
unexpectedly while starting" crash-recovery dialog at startup, so the visible
content is usually that dialog rather than a real browser. Both the
pre-migration `tawc-egl.c` WSI and the current libhybris WSI produce identical
behaviour here — it's a Firefox-side startup problem (see
`issues/firefox-startup-crash-dialog.md`), not a rendering bug.

## Launching

```bash
bash client/tawc-chroot-run 'GDK_GL=gles:always firefox --no-remote'
```

The only Firefox-specific env var we need is `GDK_GL=gles:always`, which
forces GTK to use GLES (not desktop GL) for rendering. Wayland backend
and hardware acceleration are auto-selected by Firefox 149 when
`WAYLAND_DISPLAY` is set and no `DISPLAY` socket is reachable; the older
`MOZ_ENABLE_WAYLAND=1` / `MOZ_ACCELERATED=1` / `DISPLAY=` opt-ins are no
longer required.

### Sandbox

We do **not** set any `MOZ_DISABLE_*_SANDBOX` vars. Firefox 149 sandboxes
content / RDD / socket processes via seccomp-bpf + the SandboxBroker file
ACL even though the OnePlus 9 stock kernel (5.4) lacks `CONFIG_USER_NS`,
`CONFIG_PID_NS`, and `CONFIG_IPC_NS`. The pieces that are present
(`UTS_NS`, `NET_NS`, `CGROUP_NS`, `SECCOMP_FILTER`, `BPF_SYSCALL`) plus
chroot are enough for Firefox to fall back gracefully — child processes
end up with `Seccomp: 2` (filter mode) and `NoNewPrivs: 1`. Disabling the
sandboxes also makes Firefox display an in-page "your configuration is
unsupported and less secure" warning banner, so leaving the sandbox on
is strictly better. SELinux is not a blocker — we run in the `magisk`
domain with full caps. (See `notes/android.md` for the SELinux setup.)

### Why GDK_GL=gles:always (not disabled)

With `gles:always`, GTK uses GLES via libhybris's vendor EGL, producing AHB
buffers for the window chrome. With `GDK_GL=disabled`, GTK renders everything
via SHM (CPU) — the magenta-tinted SHM path in the compositor.

This requires the libGLESv2 shim (see "GL Library Shims" below) to provide
GLX stubs. Without the stubs, libepoxy (GTK's GL dispatch) aborts when
probing for GLX symbols in a GLES-only library.

## GL Library Shims

Android has GLES only -- no desktop GL, no GLX. But Firefox and GTK have conflicting
GL library requirements:

- **Firefox (WebRender):** `dlsym(libGL_handle, "glBindTexture")` -- resolves GLES
  symbols from `libGL.so`. Without it in `LD_LIBRARY_PATH`, Firefox loads the system
  Mesa `/usr/lib/libGL.so` which doesn't work with the Android GPU.

- **GTK/libepoxy:** `dlsym(libGLESv2_handle, "glXGetCurrentContext")` -- probes for
  GLX to detect context type. Aborts if the probe produces an error (vs returning NULL).

**Solution:** Shim libraries in `/usr/local/lib/gl-shims/` that re-export libhybris GLES symbols
AND provide GLX stubs returning NULL (indicating "no GLX context, use EGL"):

```
libGL.so         -- shim: GLX stubs + DT_NEEDED libGL.so.1
libGL.so.1       -> libGLESv2.so.2 (symlink)
libGLESv2.so.2   -- shim: GLX stubs + DT_NEEDED libGLESv2_hybris.so
libGLESv2.so     -> libGLESv2.so.2 (symlink)
libGLESv2_hybris.so -- actual libhybris GLES (soname patched to break cycle)
```

The real libhybris library is renamed to `libGLESv2_hybris.so` (with `patchelf --set-soname`)
to prevent circular DT_NEEDED resolution.

Built host-side by `bash client/build-libhybris-aarch64` and bundled in the APK as
`assets/libhybris/<abi>.tar`; symlinked into the chroot at install time. Source:
`client/libgl-shim.c`, `client/libglesv2-shim.c`.

## libhybris fixes for Firefox

These live as patches in our libhybris fork (see `libhybris/TAWC_FORK.md`):

1. **`eglQueryDeviceStringEXT` / `eglQueryDisplayAttribEXT` stubs** —
   Firefox's `glxtest -w` null-checks these via `eglGetProcAddress` and
   bails ("EGL test failed" → `Feature::HW_COMPOSITING` ForceDisabled →
   software WebRender) when they're NULL. The stubs honestly return
   "no info" (NULL / EGL_FALSE) without advertising
   `EGL_EXT_device_query`.
2. **Attach + commit inside `queueBuffer`** — Adreno's WebRender path
   pushes frames through `queueBuffer` from a driver-internal thread
   that never calls `eglSwapBuffers`, so upstream libhybris's
   `finishSwap`-driven attach never fires. We drain the queue inline
   from `queueBuffer` so the submission path doesn't depend on
   `eglSwapBuffers`. Without this patch: confirmed black screen on
   Pixel 4a (Adreno 618).
3. **`NATIVE_WINDOW_BUFFER_AGE` returns `0`** — upstream libhybris
   returned a hardcoded `2`. WebRender believes that, layers partial-
   present damage on top of what was "2 frames ago", but the pool-slot
   rotation means the slot actually holds some other old frame's
   content. Result: the hamburger menu on Wikipedia flickers between
   old scrolled states for ~10 frames after a tap burst. Returning `0`
   ("content undefined") disables partial-present on the client and
   fixes it. See `notes/wsi-layer.md`.

## Killing and Restarting

```bash
adb shell "su -c 'killall firefox'"
```

The wayland flush shim (`libwayland-flush-shim.so`) is no longer needed.
