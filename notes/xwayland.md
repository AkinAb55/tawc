# Xwayland support

Goal: run X11 clients (xeyes, xterm, GIMP, … anything still X-only)
inside the chroot, displayed via tawc's Wayland compositor through an
Xwayland server. Smithay supports the compositor side of XWayland out
of the box.

## Architecture: bionic-build Xwayland into the APK

We **cross-compile Xwayland and its X11/font/render dependencies for
aarch64 bionic** and ship them in the APK as assets, extracted to
`/data/data/me.phie.tawc/files/xwayland/` on first run. The
compositor process exec's `Xwayland` from that directory — so the
binary is a direct child of the Android app process, with no
su / chroot crossing required.

Rejected alternative: launching glibc-Xwayland from inside the chroot
via `su -c chroot rootfs Xwayland`. Rejected because smithay's
`XWayland::spawn` hardcodes `Command::new("Xwayland")` and FD
inheritance through `su` is fragile. Bionic-build keeps everything on
the Android side: standard libc, standard `Command::new("Xwayland")`,
and the bind into the chroot is a one-way symlink of `/tmp/.X11-unix`
so X clients can find the socket.

## Build status (2026-04-28)

**Xwayland-24.1.11 binary builds cleanly for aarch64-linux-android29.**
1.8 MB ELF, DT_NEEDED on `libpixman-1`, `libXfont2`,
`libwayland-client`, `libxcvt`, `libxshmfence`, `libmd`, `libXau`,
`libm`, `libc`. All staged into `build/xwayland-aarch64/install/`.

Build script: `client/build-xwayland-aarch64`. Pinned upstream tags
cloned into `./xwayland-src/<lib>/`, patches in
`./xwayland-patches/<lib>/` applied via `clone_pinned →
apply_patches`, cross-compiled with the NDK toolchain
(`aarch64-linux-android29-clang`), staged into
`build/xwayland-aarch64/install/{bin,lib,include,share}` which doubles
as the pkg-config sysroot for downstream stages.

### Patches (vendored in xwayland-patches/, derived from termux-packages)

All patches are tiny (≤30 lines each) and cleanly forward-port from
termux's pinned versions to our slightly-newer upstream tags. The
`@TAWC_TMP_PREFIX@` placeholder is substituted at apply time to
`/data/data/me.phie.tawc/xtmp` (where the compositor mkdirs `.X11-unix/`,
`.X11-pipe/`, etc.).

| Lib | Patch | Reason |
| --- | --- | --- |
| `libx11` | inline `FIONREAD` constant | bionic's `<sys/ioctl.h>` doesn't transitively include `<asm-generic/ioctls.h>` |
| `libxau` | `link()` → `symlink()` | bionic + Android FS rejects cross-dir hard links to data dir |
| `libxcb` | socket dir prefix swap | Android can't write `/tmp` |
| `xorgproto` | Android `struct passwd` shape | bionic lacks `pw_class` |
| `xtrans` | socket dir prefix swap (×2) | Android can't write `/tmp` |
| `libxfont2` | `OPEN_MAX` fallback | bionic doesn't define `NOFILES_MAX` or `NOFILE` |
| `wayland` | XDG_RUNTIME_DIR fallback to `/tmp` prefix | defense-in-depth; libwayland-client fails ENOENT otherwise |

### Stages built (in dep order)

| Stage | Upstream | Tag |
| --- | --- | --- |
| `xorg-macros` | xorg/util/macros | util-macros-1.20.2 |
| `xorgproto` | xorg/proto/xorgproto | xorgproto-2024.1 |
| `xtrans` | xorg/lib/libxtrans | xtrans-1.6.0 |
| `wayland-protocols` | wayland/wayland-protocols | 1.45 |
| `libxcvt` | xorg/lib/libxcvt | libxcvt-0.1.3 |
| `pixman` | pixman/pixman | pixman-0.46.4 |
| `libffi` | libffi/libffi | v3.5.2 |
| `wayland` | wayland/wayland | 1.25.0 |
| `libxau` | xorg/lib/libxau | libXau-1.0.12 |
| `xcb-proto` | xorg/proto/xcbproto | xcb-proto-1.17.0 |
| `libxcb` | xorg/lib/libxcb | libxcb-1.17.0 |
| `libx11` | xorg/lib/libx11 | libX11-1.8.13 |
| `libxkbfile` | xorg/lib/libxkbfile | libxkbfile-1.1.3 |
| `freetype` | freetype/freetype | VER-2-13-3 |
| `font-util` | xorg/font/util | font-util-1.4.1 |
| `libfontenc` | xorg/lib/libfontenc | libfontenc-1.1.8 |
| `libxfont2` | xorg/lib/libxfont | libXfont2-2.0.7 |
| `libdrm` | mesa/drm | libdrm-2.4.125 |
| `libxshmfence` | xorg/lib/libxshmfence | libxshmfence-1.3.3 |
| `libmd` | hadrons.org tarball | 1.1.0 |
| `libepoxy` | anholt/libepoxy | 1.5.10 |
| `expat` | libexpat | R_2_6_4 |
| `libxext` | xorg/lib/libxext | libXext-1.3.6 |
| `libxfixes` | xorg/lib/libxfixes | libXfixes-6.0.1 |
| `libxxf86vm` | xorg/lib/libxxf86vm | libXxf86vm-1.1.6 |
| `libxrender` | xorg/lib/libxrender | libXrender-0.9.12 |
| `libxrandr` | xorg/lib/libxrandr | libXrandr-1.5.4 |
| `libxdamage` | xorg/lib/libxdamage | libXdamage-1.1.6 |
| `cutils-stub` | tawc-local | (header-only no-op for `<cutils/trace.h>`) |
| `mesa` | mesa/mesa | mesa-25.1.6 (Zink-only, Vulkan-backed) |
| `xwayland` | xorg/xserver | xwayland-24.1.11 |

### Out-of-scope features (disabled at configure time)

- **DRI3 / present-pixmap.** OFF. DRI3 wants buffer passing via real
  dmabuf fds; we route X11 GL through GLAMOR + zwp_linux_dmabuf
  instead.
- **MIT-MAGIC-COOKIE auth.** Single-user on-device server; any client
  can use `:0` without auth.
- **XDMCP, secure-rpc, xselinux, xinerama, xres, xv, xshmfence-as-futex,
  xf86bigfont, listen_tcp, ipv6, systemd_notify.** Not needed.

`libdrm` is still pulled in because `xwayland-window.h` includes
`<xf86drm.h>` unconditionally even with `-Ddrm=false`. We ship the
small generic `libdrm.so` (no vendor drivers) for the few struct
definitions Xwayland needs at the type level.

## X11 GL acceleration: chosen plan

**Plan (committed): glibc-Xwayland + libhybris, in two phases.**

- **Phase 1: GLAMOR-only** (no GLX, no client-side GL). Cross-compile
  Xwayland + its X11/font/render dep tree against **glibc-aarch64**
  using the same toolchain as `client/build-libhybris-aarch64`. Link
  Xwayland's GLAMOR against libhybris's libEGL + libGLESv2. Ship the
  binary alongside libhybris in the APK; compositor execs it as a
  child via the glibc dynamic loader. Output: server-side 2D / Render
  / glyph / Cairo acceleration. X clients without GL needs (xterm,
  xclock, xeyes, GIMP-2D, classic Render-heavy apps) get a real
  speedup; X clients with GL needs don't run yet.
- **Phase 2: EGL-on-X11.** Add an X11 platform plugin to
  libhybris's libEGL (~Mesa-reference-1500-lines, multi-day, isolated
  project). Solve the DRI3 render-node question. Output: X clients
  do `eglGetDisplay(EGL_PLATFORM_X11_KHR, …)` and get direct GLES
  rendering through libhybris+vendor blob, just like Wayland clients
  already do.

Project policy: **our devices do not support desktop GL, therefore
X clients do not get desktop GL.** GLES is the only client GL. This
is consistent with `gl-shims/libGL.so`'s existing chroot-side stance
(returns NULL for `glX*` so probes fall through to EGL). It removes
GLX, libGL-stub-extensions, indirect-rendering, and Mesa from the
plan entirely.

### Why GLAMOR without GLX

GLX server-side would force a `xserver/glx/` link against a libGL
exporting ~80 desktop-GL-only entry points (`glAccum`, `glBegin`,
`glRasterPos`, `glCallList`, …) that libhybris's GLESv2 doesn't have.
The workaround is a libGL-stub with no-op desktop-GL entry points
plus DT_NEEDED on libGLESv2 — buildable, but it only earns its keep
if real apps actually use indirect GLX. **They don't.** Modern X
clients that need GL either:
- use GLES via EGL-on-X11 (the path phase 2 unlocks), or
- use the GLES-overlapping subset of GLX, which would run identically
  through EGL-on-X11 anyway.

The set of apps that genuinely need desktop-GL-only features over
indirect GLX is tiny, shrinking, and slow when it works. Not worth
shipping a parallel server-side dispatch path for. Configure with
`-Dglamor=true -Dglx=false`.

### Why glibc, not bionic — the actual blocker is Wayland-EGL, not GLX

Bionic Xwayland (V1) is what's shipped today. Bionic + Mesa-Zink (V2)
got built but is dmabuf-blocked. Bionic + libhybris (V3) was the
attractive shortcut. The real reason bionic isn't viable for phase
1 isn't GLX / Mesa — it's that **Android's native libEGL has
`EGL_PLATFORM_ANDROID`, not `EGL_PLATFORM_WAYLAND`**, and Xwayland's
GLAMOR is hard-wired as a Wayland client (`xwayland-glamor.c` /
`xwayland-glamor-gbm.c` expect `eglGetDisplay(EGL_PLATFORM_WAYLAND_KHR,
wl_display)`, create `wl_egl_window`s on `wl_surface`s, call
`eglSwapBuffers`).

The bridge that takes a `wl_egl_window` and gets a buffer onto a
`wl_surface` via `android_wlegl` is **libhybris's libwayland-egl**,
not anything Android provides. Bionic Xwayland would need *one of*:

1. libhybris's wayland-egl bridge consumed in a bionic process.
   libhybris is partly bionic-loader-in-glibc (irrelevant in bionic)
   and partly the wayland-egl-to-`android_wlegl` bridge (still needed).
   Splitting them works in principle, but libhybris's wayland-egl is
   built to integrate with libhybris's *loaded* libEGL, not the system
   libEGL. **Untried** in a bionic host. Multi-day exploratory debug.
2. A bionic-native wayland-egl shim we write from scratch. Allocate
   AHBs, send via `android_wlegl`, integrate with Android EGL via
   `EGL_PLATFORM_ANDROID` + ANativeWindow semantics. Few hundred lines
   of new code we own.
3. Patch Xwayland's GLAMOR to use `EGL_PLATFORM_ANDROID`. Big upstream
   delta, hard to maintain across Xwayland releases.

glibc + libhybris sidesteps all three: libhybris-on-glibc is the
*proven* path for `wayland-egl → android_wlegl`. Every Wayland client
in the chroot uses it daily. Zero new bridge code; ship Xwayland into
the same lane.

Phase 2 also wants libhybris-glibc anyway — the EGL-on-X11 platform
addition lives inside the chroot-side libhybris (where the X clients
run). A bionic phase 1 would mean **two libhybris variants on disk**
(bionic for Xwayland, glibc for chroot clients), with phase-2 work
landing on only one. glibc phase 1 keeps it to one variant, used in
two places.

Bionic might be revisited later if a libhybris-in-bionic configuration
gets validated elsewhere, or if shipping the glibc sysroot turns out
to be painful for a reason we don't yet see. For now: not phase 1.

### What gets built (phase 1)

Server side — runs as a child of the compositor, no chroot crossing:

- Xwayland binary, glibc-aarch64, configured with `-Dglamor=true
  -Dglx=false -Ddri3=true -Dxwayland_eglstream=false`.
  - `-Ddri3=true` is harmless for phase 1 (no clients use it yet) and
    is the wire we'll need for phase 2 EGL-on-X11.
- X11 / font / Render dep tree, glibc-aarch64. Re-stage of the V2
  bionic stages onto the glibc toolchain. The patches in
  `xwayland-patches/` are mostly forward-portable; some bionic-only
  patches (e.g. `libxfont2`'s `OPEN_MAX` fallback) will be irrelevant.
- libepoxy, glibc-aarch64. New stage; GLAMOR uses it for GL symbol
  dispatch.
- Minimal glibc-aarch64 sysroot extracted alongside the binary:
  ld-linux-aarch64.so.1, libc.so.6, libdl, libpthread, libm, libresolv,
  librt. Few MB, bounded.

Client side — chroot-side, X clients reaching the GLAMOR-accelerated
2D server:

- No new code in phase 1. X clients without GL needs already work
  through the existing bionic-V1 server; glibc-V4 is a drop-in
  replacement that adds 2D acceleration.
- `gl-shims/libGL.so` keeps its current stance (returns NULL for
  `glX*`, falls through to libhybris EGL+GLES for the rest).

### What gets built (phase 2)

- **X11 platform plugin in libhybris's libEGL**. Implements
  `eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR, Display*, …)` and the
  X11-specific `eglCreateWindowSurface` / `eglSwapBuffers` bits.
  Allocates AHBs for surfaces, hands them to the X server via
  DRI3+Present so they end up in `xwayland-window-buffers.c`'s
  pipeline and back out via `android_wlegl` to the compositor.
- **DRI3 render-node decision.** DRI3 wants a render node fd that
  libhybris doesn't expose. Options to be explored when phase 2 lands:
  (a) a stub fd that the libhybris EGL-X11 platform recognizes and
  routes appropriately; (b) opening `/dev/kgsl-3d0` or `/dev/mali0`
  directly as the protocol-visible render node, while real GPU access
  still goes via libhybris; (c) bypass DRI3 entirely with XShm or a
  simpler buffer-passing path. Not designed yet.

### Phase-1 build/work breakdown

1. New build script `client/build-xwayland-aarch64-glibc` (or fold
   into `build-libhybris-aarch64`) using the libhybris cross toolchain
   to build Xwayland + epoxy + the X11 dep tree against glibc.
2. New stage building libepoxy.
3. New stage building Xwayland with `-Dglamor=true -Dglx=false
   -Ddri3=true` linking against libhybris's libEGL+libGLESv2.
4. Compositor-side: rewire `compositor/src/xwayland.rs` to invoke the
   glibc dynamic loader on the Xwayland binary; ensure
   `LD_LIBRARY_PATH` points at the staged glibc tree + libhybris.
5. Pack the glibc-Xwayland tree (binary + sysroot + xkb data) into
   the APK; extend `packXwayland`.
6. Delete the V2 bionic-NDK stages (`expat`, all bionic X11 libs,
   `cutils-stub`, `mesa`) and their patches. Useful as git-history
   reference; not needed on disk.

Realistic estimate: a few days of focused work, dominated by
cross-compile / dynamic-loader / `DT_NEEDED` debugging.

### Things to verify on the way

- **Sysroot extraction.** Today libhybris is consumed from inside the
  chroot. Phase-1 Xwayland needs it from outside any chroot. The
  glibc sysroot has to be extracted to the app's data dir and loaded
  via the explicit `ld-linux-aarch64.so.1` invocation.
- **GLAMOR-on-GLES quirks.** libhybris-driven Adreno is well-trodden
  for Wayland clients; Xwayland-server calling the same stack is less
  so. Plan for some empirical vendor-blob debugging.
- **xkb.** Phase-1 Xwayland's `share/X11` and `share/xkeyboard-config`
  data carry over from the bionic build (data, not code). Pathing
  must match the new prefix.
- **`-ac` + SO_PEERCRED.** Server runs as the app uid; chroot X
  clients run as root. Same situation as bionic-V1; the smithay
  tawc-patches `-ac` argv addition still applies.

### Existing pieces that compose with phase 1

- **libhybris fork patches in `libhybris/TAWC_FORK.md`** —
  `__cfi_slowpath` hooking, TLS-slot reservation, etc. Inherited.
- **`android_wlegl` Wayland protocol** — already implemented in
  `server/compositor/src/wlegl.rs`. Phase 1 piggybacks.
- **Compositor-side AHB import** (`server/compositor/src/render.rs`'s
  `wlegl: imported ANativeWindowBuffer` path). Phase 1 piggybacks.
- **`xwayland-patches/`** — most patches forward-port to the glibc
  build with no change; a few bionic-only ones drop out.

### Alternatives considered and rejected

- **V1 (bionic, no GLAMOR).** Status quo. Functional baseline; X
  clients with no GL needs work, anything wanting 2D acceleration
  doesn't. Phase 1 supersedes this.
- **V2 (bionic + Mesa-Zink).** Built, blocked on dmabuf import:
  Xwayland's GLAMOR requires `wl_drm` or `zwp_linux_dmabuf_v1` v4
  from the compositor; bionic Android EGL has no
  `EGL_EXT_image_dma_buf_import` to import what Mesa-Zink would emit.
  Mesa is also ~17 MB unstripped (Zink = full GL state tracker on
  Vulkan; not a thin shim). Build wiring will be deleted as part of
  phase 1.
- **V3 (bionic + libhybris).** The "shortcut" that turns out to need
  custom wayland-egl integration in a bionic host. Untried, larger
  unknowns than V4. Revisit only if V4 ever proves painful.
- **V5 (chroot Xwayland).** Rejected at the original Xwayland landing.
  Xwayland is logically compositor-side, not chroot-side; would break
  multi-/zero-chroot scenarios.
- **AHB-backed libgbm shim** (commit `200980a`). Wrote a tiny
  `libgbm.so` that allocates AHBs to flip Mesa-less GLAMOR on.
  Rejected because Xwayland's GLX server-side then wants Mesa libGL
  anyway; the libgbm shim didn't close the symbol gap. Removed in
  `b6dafd1` in favor of Mesa-Zink, which itself is now superseded.
- **chroot-Mesa-as-X-client-libEGL.** Vanilla Arch Mesa expects
  `/dev/dri/renderDxxx` + an upstream DRM kernel driver matching the
  chip. Stock Android phones have neither (Adreno via `/dev/kgsl-3d0`,
  Mali via `/dev/mali0`, both closed). Mesa probe falls through to
  llvmpipe (software-only) without `gl-shims/` masking. Phase 2's
  X11-platform-in-libhybris is the actual answer to the same question.
- **libhybris-as-Mesa-DRI-driver.** A `libhybris_dri.so` satisfying
  Mesa's DRI loader interface but routing to libhybris GLES. Lindroid
  has explored. Substantial new code on a dense interface; nothing in
  it for our plan. Parked indefinitely.
- **Indirect GLX with libGL-stub** (the V4 + libGL-stub idea from an
  earlier iteration of this doc). Buys nothing the GLES-overlapping
  subset of EGL-on-X11 doesn't get faster, and the apps that genuinely
  need desktop-GL-only features can't be served by stubs anyway.
  Dropped as part of the no-desktop-GL policy above.

## Compositor-side wiring (done; on-disk in this branch)

- `compositor/src/xwayland.rs` — spawns Xwayland on event-loop start,
  wires `X11Wm` on `XWaylandEvent::Ready`, implements `XwmHandler` +
  `XWaylandShellHandler` on `TawcState` plus a forwarding impl on
  `LoopData` (calloop's data-type bound on `X11Wm::start_wm`).
- `xwayland` feature added to the smithay dep in
  `server/compositor/Cargo.toml`.
- `[patch.crates-io] smithay = { path = "../../smithay" }` so we
  pick up the local fork's pending patches without a github push.
  Switch back to `{ git = ..., branch = "tawc-patches" }` once the
  fork is pushed.
- `TawcState` gained `xwayland_shell_state`, `xwm`,
  `x11_surfaces: Vec<X11Surface>`, `x11_to_host: HashMap<…>`, and
  `xdisplay`. `CompositorHandler::client_compositor_state` now
  handles `XWaylandClientData` clients (anvil pattern).
- `render::collect_surface_draws`, `import_shm_buffers`, and
  `send_frame_callbacks` all walk `state.x11_surfaces` alongside
  `state.toplevels` so X11 windows render and tick alongside Wayland
  ones. AHB path doesn't touch them — Xwayland is software-only here.
- `ChrootMounter` bind-mounts `<tawc-data>/xtmp/.X11-unix` into the
  chroot at `/tmp/.X11-unix` so X clients inside the chroot find `:0`
  at the standard path. `01-tawc.sh` now also exports `DISPLAY=:0`
  and `SDL_VIDEODRIVER=wayland,x11` — without the SDL hint, SDL2
  apps (supertuxkart, anything Irrlicht-based) silently pick X11
  whenever `DISPLAY` is set, hit the GLAMOR-disabled X server, and
  die in `createWindow`. Wayland-first with X11 fallback keeps SDL
  apps on the libhybris/EGL path while leaving X11 reachable for
  the X-only clients that genuinely need it.

## Smithay fork patches

Committed to `tawc-patches` as `5842fc8d Add XWayland support for the
TAWC compositor` (separate commit from the older Android support one):

1. `src/xwayland/x11_sockets.rs` — opt-in
   `TAWC_XWL_RUNTIME_DIR` env var lets the compositor move
   `/tmp/.X{n}-lock` and `/tmp/.X11-unix/X{n}` off `/tmp` (which
   doesn't exist on Android). Defaults to `/tmp` so upstream
   behaviour is unchanged.
2. `src/xwayland/xserver.rs` — append `-ac` to the `Xwayland` argv.
   The compositor and Xwayland both run as the Android app uid, but
   X clients launched from the chroot run as root, and SO_PEERCRED
   inside Xwayland would otherwise reject those connections. There's
   no real privilege boundary either way — only the app's own
   clients can reach the abstract socket and the filesystem socket
   lives in the app's private data dir.

## In-app extraction

Gradle's `packXwayland` task tars the cross-compiled
`build/xwayland-aarch64/install/{bin/Xwayland,bin/xkbcomp,lib,share/X11,share/xkeyboard-config-2}`
into `assets/xwayland/arm64-v8a.tar` (~12 MB). On first
`CompositorService.onCreate` after install / app upgrade,
`ensureXwaylandExtracted` extracts that tarball into
`<filesDir>/xwayland/` preserving symlinks (matching the existing
`ensureLibhybrisExtracted` pattern: stage to `xwayland.new`, rename,
write `.version` stamp keyed on `longVersionCode`). The compositor's
`xwayland::start_xwayland` then sets `PATH` and `LD_LIBRARY_PATH` so
the smithay `Command::new("Xwayland")` lookup picks up our copy.

Aarch64-only — matching libhybris, since there's no point shipping
software-only Xwayland on the emulator without the GPU stack.

## Build script usage

```sh
# Build everything from a fresh clone (idempotent re-runs)
bash client/build-xwayland-aarch64

# Rebuild a single stage (after editing a stage_<name>() function)
bash client/build-xwayland-aarch64 --only=libx11

# Wipe install + builddirs (forces fresh)
bash client/build-xwayland-aarch64 --clean
```

Add new stages by appending a `stage_<name>()` function and listing
it at the bottom of the script. Each stage clones into
`./xwayland-src/<name>/`, optionally applies patches from
`./xwayland-patches/<name>/`, and installs into the shared `$PREFIX`.

## Refactor history

- **2026-04-28** — initial scaffolding through libxcb (clean), then
  hit libX11's bionic-`FIONREAD` issue. Adopted termux-packages' patch
  series (vendored to `xwayland-patches/`) and forward-ported to our
  pinned versions. All 21 stages incl. Xwayland-24.1.11 binary
  cross-compile cleanly. Shipped to phone, hooked into compositor
  via `xwayland.rs` + smithay tawc-patches additions; xclock renders
  end-to-end (verified with `apps::test_xwayland_xclock_renders_via_shm`).
  Setuid-in-Popen seccomp issue worked around with a tawc patch
  (`xwayland-patches/xwayland/01-bionic-no-setuid.patch`).
