# ando su flow unverified on the rooted phone

ando (notes/ando.md) is implemented and fully verified on the emulator
(`.tawctarget=emulator` at the time): spawn, PATH search, exit codes,
env hygiene, identity, signal forwarding, orphan reaping, cwd-fd
translation, socket-path override — all covered by
`tests/integration/tests/ando.rs`.

One plan verification item needs the physical target and a human:

- `ando su -c id` → uid 0 on the rooted phone. The spawned child is an
  ordinary app-uid process, so the normal Magisk su client/daemon flow
  should apply, but the Magisk app must grant me.phie.tawc — a manual
  tap on first use. Run with `TAWC_TARGET=physical` (or after flipping
  `.tawctarget`), from an in-app terminal session or
  `scripts/rootfs-run.sh 'ando su -c id'`.

Also worth a quick interactive sanity pass in the in-app terminal while
there: `ando sh` should read/write the pty (no job control — expected,
documented in notes/ando.md).
