# GTK4 apps on Manjaro/tawcroot show no text

Observed: launching a GTK4 app inside the Manjaro/tawcroot chroot on the
physical device renders the window chrome and widgets fine, but text
(labels, buttons, menu items) is invisible. Suspected font-loading
problem — fontconfig cache, missing base font package, or some
GTK4/Pango rendering path that doesn't fall back the same way GTK3
does.

## Constraints from the one observation

(Reporter notes that not all of these may be load-bearing — they're
the case where the bug was seen, not the minimum repro.)

- Toolkit: **GTK4**. GTK3 in the same chroot may or may not be
  affected; not yet checked.
- Distro: **Manjaro ARM** (`arm-testing` channel — see
  notes/distro-options.md and `ManjaroArm.kt`). Not yet checked
  against Arch ARM, Void, or Arch x86_64.
- Method: **tawcroot**. Not yet checked against proot or chroot
  methods, which run with the same rootfs but different syscall
  emulation.
- Target: **physical device** (aarch64). Not checked on the emulator.

## Likely places to look when this gets picked up

- `fc-cache -fv` inside the chroot — does fontconfig actually see
  any fonts? Manjaro ARM's bootstrap is slim (notes/distro-options.md);
  a base font package may be missing entirely. `pacman -Qqs '^ttf-'`
  / `'^otf-'` to inventory.
- GTK4 vs GTK3 path: GTK4 dropped the cairo font backend in favour of
  Pango+harfbuzz everywhere. If we're missing harfbuzz or it's mis-built,
  GTK4 fails silently while GTK3 falls back.
- Pango shaping: `LANG=C.UTF-8` is set by enter.sh; check
  `pango-list` output and `PANGO_LANGUAGE` if the chroot's locale
  layer is misconfigured.
- libhybris / GLES path is unlikely to be involved (text is software-
  rendered into a texture before GPU upload), but worth ruling out
  by trying `GDK_RENDERING=cairo` or `GDK_DEBUG=no-vulkan` to switch
  the renderer.
- Compare to `gtk4-debug-app` (`testing/build-debug-app.sh`) — if it
  also has no text, the bug isn't app-specific.

## Reproducer (rough)

```bash
# proxy running, Manjaro/tawcroot installed (CLAUDE.md "Cache proxy")
bash scripts/install-test-deps.sh
bash scripts/tawc-chroot-run.sh 'gtk4-demo'
# launch any GTK4 demo; observe: chrome OK, labels missing
```
