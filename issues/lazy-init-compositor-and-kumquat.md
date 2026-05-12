# Lazy-init compositor + kumquat so crashes happen on client connect, not app launch

Today `CompositorService.onCreate` does the full work up-front:
`bridge::spawn()` builds the kumquat server (which loads
`libgfxstream_backend.so` and runs `stream_renderer_init` — the gfxstream
host Vulkan/EGL emulation), and `nativeStartCompositor` builds the
smithay EGL context, GlesRenderer, AHB importer, Wayland Display and
event loop. Any abort in any of that kills the whole tawc process
before MainActivity is on screen, which means **the user can't reach
in-app Settings to change away from the backend that's crashing**.

The Pixel 10 Pro Fold udmabuf FATAL fixed in `d2c9683` is the
motivating case; deferring init would have meant that crash only fired
when a chroot client connected with the gfxstream backend selected,
leaving the rest of the UI usable.

## Design

Bind the AF_UNIX sockets eagerly on a thin accept thread that does
nothing but block in `accept(2)`. On first connection, kick off the
real init and hand the accepted fd over to the freshly-built server via
a channel. Two independent scopes — same pattern.

### Kumquat (gfxstream renderer)

`KumquatBuilder::build` bundles `KumquatGpu::new()` (heavy: gfxstream
renderer init) with `Listener::bind` (cheap), in that order
(`deps/rutabaga_gfx/kumquat/server/src/kumquat.rs:145-177`). Invert via
a rutabaga patch, `rutabaga-patches/05-lazy-gpu-init.patch` (≈40 LOC):

- Bind the listener immediately; leave `kumquat_gpu_opt: Option<KumquatGpu>`
  as `None`.
- In `Kumquat::run`, lazily build `KumquatGpu` the first time a client
  is accepted, before dispatching its first command.

The data model already has `Option<KumquatGpu>` — only the build
sequence changes. After the change, all non-gfxstream users (LIBHYBRIS,
CPU, LIBHYBRIS_ZINK) pay zero gfxstream init cost; the first client
that opens a gfxstream-vk Vulkan instance pays it once.

### Compositor (smithay + Wayland)

Trickier because EGL contexts are bound to the creating thread, and the
Wayland socket bind sits at the very end of `run_compositor` today
(intentionally — see the comment in `compositor/src/lib.rs:661-668`
about avoiding the bind→dispatch gap).

Shape:

- Bind `share/wayland-0` eagerly from a tiny accept thread spawned by
  `nativeStartCompositor`. Block in `accept(2)`.
- On first accept: send the fd over a channel to a freshly-spawned
  compositor thread, which then does its own EGL/GlesRenderer/calloop
  setup and plugs the accepted fd straight into its `WaylandListener`
  source (calloop accepts an externally-provided fd via
  `Generic::new`).
- The client sees one ~150-300 ms stall on its initial `wl_display`
  roundtrip; subsequent clients see no extra cost.

The kumquat thread (Q1) is independent and can be moved out from under
the compositor thread too — both lazy-bind, both share the same accept
shape. The kumquat thread doesn't need EGL ownership so its handoff
is simpler.

## Cost and trade-offs

- **First connect latency.** A client that connects before init
  completes blocks on its first wayland roundtrip until EGL +
  GlesRenderer + Display are ready. ~150-300 ms one-time. For the
  gfxstream path the additional renderer-init cost stacks on top.
- **Out-of-band clients.** A chroot daemon that connects to the
  wayland socket without an Activity running still triggers init —
  the socket existence and connection acceptance are decoupled from
  whether the app is in the foreground. Same behaviour as today.
- **EGL thread ownership.** The accept thread cannot pre-build any
  GPU state. The compositor thread must do its own EGL init *after*
  it receives the fd. The current `run_compositor` flow already does
  EGL init on the compositor thread, so the refactor is "split the
  socket bind out, deliver the fd via channel" rather than "rewrite
  EGL ownership".
- **Foreground service lifecycle.** `CompositorService` is
  `START_STICKY` foreground. Today `onCreate` does the heavy init;
  with lazy init, the heavy init moves later but the service still
  has to be created (notification + foreground type declaration)
  before any binding. This is fine — the foreground notification is
  cheap, and the service is what owns the accept thread.

## What this is *not*

Process isolation. The compositor + kumquat still run in the main
app process. If init crashes inside that process, the app dies — but
now only when a client connects, by which point Settings is reachable.
Full process separation (`:compositor` process via `android:process`)
remains a separate, bigger refactor.

## Out of scope for this issue, but adjacent

- The udmabuf FATAL fix (`d2c9683`) becomes redundant-but-still-correct
  defence-in-depth once lazy init lands.
- Gating `bridge::spawn` on `Settings.graphicsBackend == GFXSTREAM`
  (option (3) from the earlier discussion) is subsumed by lazy init —
  if no gfxstream-vk client ever connects, the renderer never builds.
