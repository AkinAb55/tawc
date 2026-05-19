# Start Xwayland only when an X11 client connects

`compositor/src/event_loop.rs` currently calls `xwayland::start_xwayland`
unconditionally during compositor startup. That means every Wayland-only
session pays the Xwayland process/startup cost and gets noisy Xwayland
log lines, even when no X11 client ever connects.

This also makes debugging confusing: a normal Firefox Wayland run shows
Xwayland startup logs, which makes it easy to chase the wrong rendering
path.

## Desired shape

- Do not spawn Xwayland during compositor startup.
- Still expose the X11 socket path clients expect.
- Spawn Xwayland lazily when an X11 client connects to that socket.
- Keep existing `X11Wm` setup after `XWaylandEvent::Ready`.
- Preserve current Xwayland integration tests; they should trigger the
  lazy spawn by connecting/running an X11 client.

## Notes

Smithay's Xwayland helper currently owns socket creation/spawn setup, so
this may need either a small tawc-side pre-listener in front of Smithay or
a patch/extension in the smithay fork to split "create X sockets" from
"exec Xwayland".
