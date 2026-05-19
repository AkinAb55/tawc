# Firefox WaylandProxy spins after host surface is gone

Observed on `tawc-rootless` emulator after Firefox was launched under
tawcroot and the Android host Activity/surface disappeared.

Symptoms:

- Host CPU showed `qemu-system-x86_64` as the hot process.
- In-emulator top showed `tawcroot --exec-child 3` PID 25040 using
  ~80-95% of the emulator CPU budget.
- Inspecting as the app uid showed PID 25040 was Firefox: Mozilla
  profile DBs, `org.mozilla.ipc` memfds, `js-gc-heap`, `gdk-wayland`,
  and children named `Web Content`, `Socket Process`, `RDD Process`.
- Hot thread was Firefox `WaylandProxy`.
- Compositor state stayed at:

  `clients=7 toplevels=2 surfaces_shm=3 frames=36 hosts=2 bound_hosts=0`

Meaning: Firefox still had live Wayland clients/toplevels, but no
Android host surface was bound to present them.

Likely cause:

- `SurfaceDestroyed` retains the host record for possible rebind.
- `ActivityDestroyed` only sends polite `xdg_toplevel.close`; clients
  that keep the Wayland connection alive are not forced down.
- The rootfs process tree is owned by the launch process/session, not
  by the host Activity/toplevel lifecycle, so an app can outlive every
  presentation surface and spin.

Fix direction:

1. Add lifecycle ownership for launched GUI sessions: keep the root
   process group/session id or root PID associated with the spawned
   toplevel/host.
2. On Activity destroy, send `xdg_toplevel.close`, then force-kill the
   associated rootfs process tree after a short grace if the client does
   not disconnect.
3. For `SurfaceDestroyed` without `ActivityDestroyed`, do not kill
   immediately; set a timer. If the host does not rebind and there are
   no bound hosts for that toplevel after the grace, apply the same
   close/kill path.
4. As a compositor-only fallback, if `bound_hosts=0` and toplevels remain
   for longer than the grace window, disconnect or close those clients.
   Prefer process-tree kill where available so Firefox child processes
   do not survive the Wayland disconnect.

Useful guardrail: log a warning when `bound_hosts=0 && toplevels>0`
persists for more than a few seconds, including client count and assigned
host ids.
