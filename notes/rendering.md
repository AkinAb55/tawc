# Rendering, Window Management, and Coordinate System

## Window Management

All toplevels are configured as maximized at the full logical output size (physical pixels /
scale factor). The output scale is 2x, so on a 1080x2400 display, apps see a 540x1200
logical surface. The xdg-decoration protocol is implemented, always requesting server-side
decorations (which we don't draw, so clients get no decorations). Surfaces are rendered at
(0,0) instead of centered.

## Popup and Subsurface Positioning

Popup surfaces (xdg_popup) are tracked via Smithay's `PopupManager`. On `new_popup`, we
compute constrained geometry using `PositionerState::get_unconstrained_geometry()` and send
a configure. The PopupManager handles the popup tree hierarchy and provides popup positions
relative to their toplevel root.

**Note:** Firefox uses wl_subsurface (not xdg_popup) for its dropdown menus. Both paths go
through the same rendering code in `draw_shm_surfaces`.

## Coordinate System

**This is subtle. Do not "fix" without understanding.**

1. **Logical vs physical:** Subsurface positions (from `wl_subsurface.set_position`) and
   popup positions (from xdg_positioner) are in logical (surface-local) coordinates.
   The renderer works in physical pixels. Multiply positions by `output_scale`.

2. **Y-axis flip:** Smithay's GlesRenderer uses a GL projection where Y=0 is at the
   **bottom** of the screen, not the top. For SHM surfaces at non-origin positions:
   `physical_y = screen_h - logical_y * scale - texture_h`. AHB surfaces skip this
   because they're always fullscreen at the origin (the flip is a no-op for them).

The canonical scale factor lives in `TawcState::output_scale`. Do not hardcode `2` elsewhere.

## SHM Buffer Support

SHM buffers (`wl_shm`) are supported alongside the AHB path. SHM matters even for
GPU-accelerated clients because cursor themes, toolkit subsurfaces/popups (GTK3/4), and
EGL fallback paths all use `wl_shm`.

**Magenta tint:** SHM surfaces are rendered with a distinct magenta tint via a custom
`GlesTexProgram` shader. This is intentional -- it makes it visually obvious when a client
falls back to SHM instead of using hardware-accelerated AHB buffers.

The SHM path is tracked separately from AHB: `surface_shm` HashMap holds `SurfaceShmState`
per surface. Surfaces using the AHB channel protocol are never checked for SHM buffers.

### SELinux and Memfd Sharing

When root processes in the chroot create memfds, they get label `u:object_r:tmpfs:s0`.
The `untrusted_app` domain lacks permission on `tmpfs`, so the compositor can't mmap them.
The failure causes the Wayland protocol parser to get out of sync.

**Solution (with root):** An LD_PRELOAD shim (`client/memfd-selinux-shim/`) intercepts
`memfd_create()` and calls `fsetxattr` to relabel the memfd as `appdomain_tmpfs`.

**Shim limitation:** GDK/GLib calls `syscall(SYS_memfd_create, ...)` directly, bypassing
the LD_PRELOAD. Workaround: `setenforce 0`. Proper fix: intercept `syscall()` itself, or
run clients as the compositor's UID.

**Without root:** Run client processes as the same app/UID. Their memfds natively get
`appdomain_tmpfs` label.
