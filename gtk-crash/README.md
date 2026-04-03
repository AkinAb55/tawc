# Intermittent glGenTextures SIGSEGV when GTK3 is loaded via libhybris

## Summary

Hardware-rendered GTK3 Wayland apps intermittently (~15-20%) segfault at `glGenTextures` during the first GL call after EGL initialization. The crash requires:

1. GTK3 loaded (dozens of transitive library deps)
2. EGL init through the tawc-egl WSI wrapper with a live Wayland connection

Without GTK: 0%. Without the Wayland connection (EGL_DEFAULT_DISPLAY): also 0%.

## The crash

Inside `alloc_buffer()` in `client/tawc-wsi/tawc-egl.c` (~line 603):

```
ahb_allocate(...)             ← succeeds
pfn_getClientBuffer(...)      ← succeeds
pfn_createImage(...)          ← succeeds
glGenTextures(1, &buf->tex)   ← SIGSEGV
```

`eglMakeCurrent` returns success, `eglGetCurrentContext` returns a valid handle. The GL context is nominally current but the first GL dispatch crashes inside the Adreno driver.

## Reproducer

`repro.c` — ~80 line C program. Links against the tawc-egl WSI wrapper's libEGL. Uses `tawc_create_test_surface()` (added to the WSI wrapper) to trigger `alloc_buffer` without needing compositor protocol exchange.

**Requires the tawc compositor running** for the Wayland socket, but no protocol interaction occurs.

```bash
bash gtk-crash/build-and-test.sh      # automated
```

Or manually in chroot:
```bash
gcc -o repro repro.c -L/tmp/tawc-wsi -lEGL -lGLESv2 -lwayland-client -ldl -Wall -g
./repro          # 0% crash
./repro gtk      # ~15-20% crash
```

## What we've ruled out

| Condition | Crashes? |
|-----------|----------|
| Direct libhybris EGL (no WSI wrapper) + GTK | No |
| WSI wrapper + EGL_DEFAULT_DISPLAY (no Wayland) + GTK | No |
| WSI wrapper + Wayland + no GTK | No |
| WSI wrapper + Wayland + GTK | **Yes ~15%** |
| WSI wrapper + dummy Wayland server + GTK | No |
| WSI wrapper + real compositor + GTK | **Yes ~15%** |
| Just linking `-lwayland-client -lwayland-egl` (unused) + GTK | No |
| Buffer size (64×64 vs 1080×2400) | No effect |
| GL resolution method (eglGetProcAddress vs dlsym) | No effect |
| Delay between process restarts | No effect |

The crash needs all of: GTK loaded + WSI wrapper active + a Wayland connection to the real compositor. A dummy server with globals doesn't trigger it. Something about the real compositor's Wayland response processing affects the crash.

## Hypothesis

The WSI wrapper's `eglGetDisplay(wayland_display)` does `wl_display_get_registry` + `wl_display_dispatch_pending` before calling `do_init()` (which loads libhybris and runs the TLS patcher). With the real compositor, `dispatch_pending` processes actual protocol events (global announcements), which may trigger code paths in libwayland-client that affect the glibc TLS block layout. Combined with GTK's ~40 library deps that also consume TLS slots, this pushes the TLS layout into a state where the libhybris TLS patcher's computed offset is wrong.

## Files

- `repro.c` — reproducer
- `build-and-test.sh` — automated build + crash rate test
- `client/tawc-wsi/tawc-egl.c` — WSI wrapper with crash site (`alloc_buffer`) and `tawc_create_test_surface`
