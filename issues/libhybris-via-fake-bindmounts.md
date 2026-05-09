# Inject libhybris into the rootfs via fake bindmounts instead of symlinks

Replace `LibhybrisLinker`'s install-time symlink dance with per-entry
bind table additions on tawcroot/proot. The in-rootfs libhybris view
is then rebuilt fresh from the current APK's asset on every entry,
which removes libhybris from the cross-version contract surface
entirely — no `metadata.json` version field needed, no re-link
migration on upgrade, no soname-drift dangling.

## What's there today

`LibhybrisLinker.link` (`app/src/main/java/me/phie/tawc/install/LibhybrisLinker.kt`)
runs once during the CONFIGURING stage of a fresh install:

1. Walks `<filesDir>/libhybris/lib/` (extracted from
   `assets/libhybris/<abi>.tar` by
   `CompositorService.ensureLibhybrisExtracted`).
2. For each top-level entry (file or dir), creates a symlink in
   `<rootfs>/usr/local/lib/<name>` pointing at
   `/data/data/me.phie.tawc/files/libhybris/lib/<name>`.
3. Writes a real file at
   `<rootfs>/usr/share/glvnd/egl_vendor.d/00_libhybris.json`.

Symlinks are install-time snapshots: a future libhybris that adds a
new `.so`, removes one, or bumps a soname is invisible to existing
rootfs installs unless we explicitly re-run the linker. That's the
"libhybris version handshake" item in the audit.

## Proposed shape

For **tawcroot** and **proot** — both already do `-b SRC:DST`
path-translation binds — extend the bind table built each entry in
`startInside` so the libhybris tree appears synthetically inside the
rootfs view. Nothing written to disk, no install-time work, no
versioning needed.

For **chroot** (debug-only), keep `LibhybrisLinker`'s symlinks as
they are. Real bind mounts would require pre-creating empty target
inodes which fights the "no rootfs writes" goal; not worth the churn
for a debug-only path. Document the divergence.

### Paths to inject

Source tree shipped in the APK at `assets/libhybris/<abi>.tar` (built
with `--exclude=*.la --exclude=pkgconfig`, see
`app/build.gradle.kts:327-329`). After extraction lives at
`/data/data/me.phie.tawc/files/libhybris/`. The tar's contents are
pinned by libhybris's autotools build; the listing below reflects the
current `aarch64` build.

**Top-level shared libraries** under `lib/` (~17 sonames, ~47 files
counting all `.so` / `.so.N` / `.so.N.M.K` symlink chains):

| Source (host path)                                                                  | Target (rootfs path)                          |
|-------------------------------------------------------------------------------------|-----------------------------------------------|
| `/data/data/me.phie.tawc/files/libhybris/lib/libEGL.so{,.1,.1.0.0}`                  | `/usr/local/lib/libEGL.so{,.1,.1.0.0}`        |
| `/data/data/me.phie.tawc/files/libhybris/lib/libGLESv1_CM.so{,.1,.1.0.1}`            | `/usr/local/lib/libGLESv1_CM.so{,.1,.1.0.1}`  |
| `/data/data/me.phie.tawc/files/libhybris/lib/libGLESv2.so{,.2,.2.0.0}`               | `/usr/local/lib/libGLESv2.so{,.2,.2.0.0}`     |
| `/data/data/me.phie.tawc/files/libhybris/lib/libvulkan.so{,.1}`                      | `/usr/local/lib/libvulkan.so{,.1}`            |
| `/data/data/me.phie.tawc/files/libhybris/lib/libgralloc.so{,.1,.1.0.0}`              | `/usr/local/lib/libgralloc.so{,.1,.1.0.0}`    |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhardware.so{,.2,.2.0.0}`             | `/usr/local/lib/libhardware.so{,.2,.2.0.0}`   |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhwc2.so{,.1,.1.0.0}`                 | `/usr/local/lib/libhwc2.so{,.1,.1.0.0}`       |
| `/data/data/me.phie.tawc/files/libhybris/lib/libsync.so{,.2,.2.0.0}`                 | `/usr/local/lib/libsync.so{,.2,.2.0.0}`       |
| `/data/data/me.phie.tawc/files/libhybris/lib/libui.so{,.1,.1.0.0}`                   | `/usr/local/lib/libui.so{,.1,.1.0.0}`         |
| `/data/data/me.phie.tawc/files/libhybris/lib/libandroid-properties.so{,.1,.1.0.0}`   | `/usr/local/lib/libandroid-properties.so{,.1,.1.0.0}` |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhybris-common.so{,.1,.1.0.0}`        | `/usr/local/lib/libhybris-common.so{,.1,.1.0.0}` |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhybris-eglplatformcommon.so{,.1,.1.0.0}` | `/usr/local/lib/libhybris-eglplatformcommon.so{,.1,.1.0.0}` |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhybris-platformcommon.so{,.1,.1.0.0}` | `/usr/local/lib/libhybris-platformcommon.so{,.1,.1.0.0}` |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhybris-vulkanplatformcommon.so{,.1,.1.0.0}` | `/usr/local/lib/libhybris-vulkanplatformcommon.so{,.1,.1.0.0}` |
| `/data/data/me.phie.tawc/files/libhybris/lib/libhybris-hwcomposerwindow.so{,.1,.1.0.0}` | `/usr/local/lib/libhybris-hwcomposerwindow.so{,.1,.1.0.0}` |

The exact filename list should be **enumerated dynamically** from the
extracted asset rather than hardcoded — that's the whole point.
`File("$filesDir/libhybris/lib").listFiles()` already drives the
linker today; reuse it.

**Subdirectories** under `lib/` (bind as whole dirs — contents are
plugin lookup paths libhybris probes by name):

| Source                                                              | Target                              |
|---------------------------------------------------------------------|-------------------------------------|
| `/data/data/me.phie.tawc/files/libhybris/lib/libhybris/`            | `/usr/local/lib/libhybris/`         |
| `/data/data/me.phie.tawc/files/libhybris/lib/gl-shims/`             | `/usr/local/lib/gl-shims/`          |

Contents of `libhybris/`: `eglplatform_{fbdev,hwcomposer,null,wayland,x11}.so`,
`vulkanplatform_{null,wayland}.so`, `linker/` (bionic linker
components). Contents of `gl-shims/`: `libGL.so{,.1}`, `libGLESv2.so{,.2}`,
`libGLESv2_hybris.so`. These are accessed by libhybris itself via
path traversal through the directory bind, so individual files
don't need separate entries.

**glvnd vendor JSON** (real file content, not a binary):

| Source                                                                              | Target                                                |
|-------------------------------------------------------------------------------------|-------------------------------------------------------|
| `/data/data/me.phie.tawc/files/libhybris/glvnd/00_libhybris.json`                   | `/usr/share/glvnd/egl_vendor.d/00_libhybris.json`     |

The JSON content is constant per APK — it's currently generated
inline by `LibhybrisLinker`. Move that string into the build (drop
it as `glvnd/00_libhybris.json` inside the asset tar via
`scripts/build-libhybris.sh` or the `packLibhybris` gradle task) so
the bind has a real source file to point at. The `00_` prefix on the
filename is mandatory — glvnd loads vendors in lex order and Mesa
ships `50_mesa.json` in the same directory.

### Total

~30 bind table entries beyond the existing libhybris-side ones
(`/apex`, `/vendor`, `/system`, `/system_ext`, `/linkerconfig`,
`/data/data/me.phie.tawc`). Built in Kotlin from `listFiles()` output
plus a fixed set of subdir + JSON entries.

## Why it's better

- **Closes the libhybris version-handshake problem completely.**
  No `metadata.json` field, no install-time snapshot, no upgrade
  migration. The in-rootfs view just is the current APK's asset.
- File added between APK versions → automatically present.
- File removed → automatically gone (no dangling symlinks).
- Soname bump → automatically reflected.
- Asset reorganisation → automatically reflected (modulo bind
  source paths, which are still under `<filesDir>/libhybris/lib/`).
- **No on-disk artefacts in the rootfs.** Same principle as the
  rootfs-env-into-kotlin work. One fewer thing in the cross-version
  audit.
- Lines up with the env-into-kotlin issue — both edit `bindSpecs()` /
  `prootArgv` to add per-entry binds, both rebuild fresh per entry.
  Reasonable to land them together.

## Open questions

- **glvnd readdir merge.** glvnd finds vendors by scanning
  `/usr/share/glvnd/egl_vendor.d/` and matching `*.json`. We need
  Mesa's `50_mesa.json` (real file in the rootfs, shipped by the
  distro) **and** our bound `00_libhybris.json` to both appear in
  the listing. Verify proot's and tawcroot's behaviour: when `-b
  SRC:DST` binds a file inside a directory whose parent isn't bound,
  does `readdir` of the parent yield host children + bound entry?
  proot generally does this; tawcroot's `getdents` handler needs
  inspection. If either doesn't, fall back to dropping the JSON as
  a real file in the rootfs at install time (the JSON content is
  per-APK-stable so it's not part of the version-drift problem
  even when persisted).

- **Bind table lookup cost.** ~30 extra entries on the existing ~10.
  proot/tawcroot consult the bind table on every path-bearing
  syscall — usually negligible, but Firefox under proot is a
  syscall-storm workload. Microbenchmark a Firefox cold-start before
  and after to confirm no regression. If it does regress, consider
  binding `<filesDir>/libhybris/lib` as a single dir overlay onto
  `/usr/local/lib` — fewer entries but overlays anything the user
  has installed there (rare but possible). Open call.

- **Chroot uniformity.** Above proposes keeping `LibhybrisLinker`
  symlinks for chroot since real bind mounts need on-disk targets.
  Alternative: do real bind mounts in `ChrootMounter.mountScript`,
  pre-creating targets per entry. More code symmetry across methods
  but writes nodes on every chroot entry. Pick whichever looks
  cleaner during implementation.

- **`/usr/share/vulkan/icd.d/` for Vulkan ICD registration.** Today
  we deliberately don't register a Vulkan ICD because libhybris's
  `libvulkan.so.1` is a *loader*, not an ICD (recursive load). If a
  future libhybris ships an actual ICD JSON, the same bind pattern
  applies — just listed here as a place future binds would go.

## Migration

- **Pre-beta:** no users. New installs after the change just don't
  get the old symlinks. Existing dev installs retain their
  `LibhybrisLinker`-written symlinks; they become harmless duplicates
  of the bound paths (same target file, same content). Symlinks
  pointing at file paths the bind also covers don't conflict —
  whichever resolution wins lands at the same `.so`.
- **Cleanup of legacy symlinks** is optional. Could add a one-shot
  `rm -f /usr/local/lib/libEGL.so.1 ...` step in a `Reconfigurer`
  scaffold (see audit notes), but not necessary for correctness.

## Files affected

- `app/src/main/java/me/phie/tawc/install/LibhybrisLinker.kt` —
  drop the file (or stub for chroot-only path).
- `app/src/main/java/me/phie/tawc/install/Installer.kt:241-251` —
  drop the `LibhybrisLinker.link` call (or gate it on
  `method.key == ChrootMethod.KEY`).
- `app/src/main/java/me/phie/tawc/install/TawcrootMethod.kt` —
  extend `bindSpecs()` to enumerate `<filesDir>/libhybris/lib/` and
  add the glvnd JSON bind. Pre-create bind target dirs in
  `startInside` (already does this for `LIBHYBRIS_BIND_DIRS`).
- `app/src/main/java/me/phie/tawc/install/ProotMethod.kt` — same
  for `prootArgv()`.
- `scripts/build-libhybris.sh` and/or `app/build.gradle.kts` — add
  `glvnd/00_libhybris.json` to the asset tar so there's a real
  source file for the bind. (Or handle it Kotlin-side by extracting
  + writing once into `<filesDir>/libhybris/glvnd/`.)
- `notes/gpu-strategy.md` — update the libhybris install description.

## Out of scope

- Vulkan ICD registration (see open questions).
- Removing the `00_libhybris.json` content from `LibhybrisLinker`'s
  generated script if we're keeping `LibhybrisLinker` for chroot —
  the linker's whole job there is to write that file plus the
  symlinks.
