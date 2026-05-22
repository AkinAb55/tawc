# Xwayland Server-Side GL Plan

This is a parked plan for X server-side GL acceleration. It is probably not
needed unless a real X11 workload shows a bottleneck in server-rendered
pixmaps.

The implemented Xwayland path already handles modern client GL through
libhybris EGL-on-X11 and AHB transport. Server-side GL would add acceleration
for old X11 drawing paths such as RENDER glyphs, cursors, and Cairo operations.
The likely beneficiaries are small and shrinking: xterm, old GIMP paths, and
older desktop stacks.

If this becomes necessary, avoid GBM. Give Xwayland its existing Wayland
connection as the EGL display, use `EGL_PLATFORM_WAYLAND_KHR` through
libhybris loaded in the server, render to FBOs backed by AHB EGLImages, and
route those AHBs through `xwayland-tawc.c`.

Expected touch points:

- `xwayland-tawc.c` for AHB-backed pixmaps and buffer shipping.
- A vtable path in `xwayland-glamor.c` for server-side rendering.
- No `/dev/dri/render*`, GBM, dmabuf, `wl_drm`, or
  `zwp_linux_dmabuf_v1`.

Do not prioritize this unless Phase 2's EGL-on-X11 client path leaves a
measured bottleneck in a workload we care about.
