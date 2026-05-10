# Sysroot pull from a live device is fragile

`scripts/build-mesa-gfxstream.sh` (and the new x86_64 sibling) link
`libvulkan_gfxstream.so` against a small set of distro `.so` files
plus their headers — `libwayland-{client,server}`, `libdrm`, `libudev`,
`libffi`, plus a few headers. Today the canonical way to get those is
`scripts/pull-sysroot.sh`, which `adb`s into the device, tars a
curated subset out of the **installed** rootfs at
`/data/data/me.phie.tawc/distros/<id>/rootfs/`, and untars into
`build/<arch>-sysroot/`.

## Why it's bad

1. **Requires a live device with a finished install.** First-time
   bootstrap is now: install distro → run a chroot once → only then
   can you build Mesa. Bootstrap on a fresh machine is non-obvious.
2. **No ABI/version pinning.** Pull from a stale rootfs, build Mesa,
   pull from a fresh one tomorrow — same script, different headers,
   silently different binary. Symbol drift won't show up until
   runtime.
3. **Two ABIs = two devices needed.** Building both aarch64 and
   x86_64 cross-libs from one host now requires both a phone (or a
   build of the aarch64 rootfs in some chroot you maintain) **and**
   the AVD running, with a finished install on each.
4. **Asymmetric with every other dep we have.** Every other cross-
   build dep in `deps/deps.list` is reproducible offline: clone-by-
   commit + apply patches + build. Sysroot pull is the lone "go look
   at the universe" step.
5. **Pacman rolls.** Arch rolls the wayland soname or libdrm minor
   version, our pulled sysroot suddenly DT_NEEDEDs the new soname,
   chroot still has the old one, runtime breakage.

## Options to replace it

(in rough order of cost)

### Vendor the .so + headers
Ship a tar of the curated set inside the repo under
`deps/sysroots/<arch>/`, regenerate on demand. Reproducible offline.
Costs: a few MB of binaries committed; periodic refresh discipline so
they don't drift too far from what the chroot actually has at runtime.
Probably the right answer.

### Bootstrap a minimal rootfs locally and pull from that
Run `pacstrap`/`debootstrap` against a host-cached mirror, then run
the same tar curation against the bootstrap tree. Reproducible if the
mirror is pinned. Costs: bootstrap step in the host workflow, mirror
pinning is its own can of worms.

### Build the deps from source as part of the cross-build
Add wayland/libdrm/libudev/libffi to `deps/deps.list`, cross-compile
them just like we cross-compile xkbcommon. Cleanest in spirit, but
the dep tree (libdrm pulls in pciaccess, …) gets wide.

### Use the NDK
Tempting because the NDK is already a host dep. Wrong fit — Mesa-in-
chroot is a **glibc** binary, the NDK is bionic. Different libc means
different `pthread_*` ABI, different TLS model, different exception
unwinding. Doesn't compose.

## Status

`scripts/pull-sysroot.sh` is the supported way today. This issue
exists so we don't forget that it shouldn't be.
