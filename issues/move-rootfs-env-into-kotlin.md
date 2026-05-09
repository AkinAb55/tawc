# Move rootfs env setup out of profile.d into Kotlin

Drop `/etc/profile.d/00-path.sh` and `/etc/profile.d/01-tawc.sh` from
the rootfs. Set env vars on the spawned process from Kotlin, and
replace the X11 symlink with a fake bindmount on the methods that
support it. Removes the only on-disk artefacts inside the rootfs that
the app version currently rewrites on every entry.

## What's there today

- **`/etc/profile.d/00-path.sh`** — install-time-only file pinning
  `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`,
  `TMPDIR=/tmp`, `HOME=/root`. Two near-identical writers:
  `ArchPacmanCommon.kt:418-431` and `VoidCommon.kt:168-173`. Exists
  because `bash -l` inherits Android's env from our launcher chain
  (`PATH=/system/bin:...`, `TMPDIR=/data/local/tmp`, etc.) and we need
  Linux defaults instead.
- **`/etc/profile.d/01-tawc.sh`** — refreshed on every entry, body
  built by `RootfsProfile.build()`. Sets Wayland/GL/X11 env, creates
  `/tmp/wayland-0` and `/tmp/.X11-unix` symlinks (skipped on chroot
  for X11 since `ChrootMounter` real-bindmounts that path).
- **`/tmp/wayland-0` symlink** — pointed at
  `/data/data/me.phie.tawc/wayland-0`. Not actually needed:
  `WAYLAND_DISPLAY` is already exported as the absolute path and
  wayland clients honour absolute values directly.

Three call sites do the per-entry profile.d rewrite:
`TawcrootMethod.kt:106`, `ProotMethod.kt:142-143`, and
`ChrootMounter.kt:145-153`.

## Proposed shape

1. **Env via `ProcessBuilder.environment()`**, with the map cleared
   first — start from `env -i` (or Java equivalent) to drop everything
   Android injects, then set only what we want. Keep `bash -l` so
   distro-shipped profile.d (locale, package PATH additions) still
   runs.
2. **`RootfsProfile`** becomes `RootfsEnv` (or similar) returning
   `Map<String, String>` instead of a shell snippet. Per-method
   tweaks (Firefox `MOZ_DISABLE_*_SANDBOX` for proot only) stay
   structured.
3. **`/tmp/.X11-unix` → fake bindmount** for tawcroot/proot:
   `bindSpecs` / `prootArgv` already handle SRC:DST asymmetric binds.
   Add `/data/data/me.phie.tawc/xtmp/.X11-unix → /tmp/.X11-unix`. No
   filesystem state inside the rootfs. Chroot stays as it is — already
   a real bindmount.
4. **Drop `/tmp/wayland-0` symlink entirely.** `WAYLAND_DISPLAY` is
   absolute. Clients that hardcode `/tmp/wayland-0` are vanishingly
   rare and broken-by-design anyway.
5. **Drop `00-path.sh` writers** from `ArchPacmanCommon` and
   `VoidCommon`. With env-cleared spawn, there's nothing to override.
6. **Drop `01-tawc.sh` writers** from all three methods. Drop the
   refresh-on-every-entry comments.

## Why it's better

- No rootfs files written by app code → one fewer thing the cross-
  version compat surface has to think about.
- Single source of truth for env (a `Map`) instead of three call sites
  formatting the same shell.
- `env -i` style isolation eliminates a class of bugs where a stray
  Android var (e.g. `LD_LIBRARY_PATH=/system/lib64`) confuses something
  inside the rootfs.
- Removes the symlink-poisoning failure mode entirely (the deleted
  `x11-symlink-traps-root-ownership` issue can't recur — there's no
  symlink to poison).

## Open questions

- **X11 lock files** (`.X0-lock`, etc.) currently sit in
  `xtmp/` and the profile.d body symlinks them into `/tmp/`. Per-file
  bindmounts work for files that exist at spawn time but not for ones
  Xwayland creates later in the session. Options: bind the parent
  `xtmp/` dir as a single overlay (but `/tmp` has user content too),
  point Xwayland at a path inside the rootfs, or keep a single
  Kotlin-side `Os.symlink` step in `startInside` for these specific
  files. Prefer the symlink option only as fallback — rest of the
  design avoids in-rootfs symlinks for a reason.
- **Distro `/etc/profile` behaviour with PATH set.** Most distros'
  `/etc/profile` only set PATH if unset, so a pre-set PATH passes
  through. Verify on Arch and Void before committing — if either
  appends Android-leaked entries, we'd need a different strategy.
- **PROOT_TMP_DIR / PROOT_LOADER** are set on the proot subprocess
  today via env in `startInside`. They should NOT be propagated into
  the in-rootfs bash — they're proot-host concerns. Make sure the
  env split (host-side proot vs. in-rootfs bash) is clear.

## Files affected

- `app/src/main/java/me/phie/tawc/install/RootfsProfile.kt` — replace
  string builder with env Map.
- `app/src/main/java/me/phie/tawc/install/TawcrootMethod.kt` — drop
  the `01-tawc.sh` writeText, set env on the spawned process, add
  `xtmp/.X11-unix` bind to `bindSpecs`.
- `app/src/main/java/me/phie/tawc/install/ProotMethod.kt` — same.
- `app/src/main/java/me/phie/tawc/install/ChrootMounter.kt` — drop
  the `01-tawc.sh` write block; pass env to the chroot exec instead.
- `app/src/main/java/me/phie/tawc/install/distro/arch/ArchPacmanCommon.kt`
  — drop `00-path.sh` writer.
- `app/src/main/java/me/phie/tawc/install/distro/voidlinux/VoidCommon.kt`
  — drop `00-path.sh` writer.
- `notes/installation.md`, `notes/android.md` — update profile.d
  references.

## Migration

Pre-beta, no users. Existing dev installs will retain their
`00-path.sh` and `01-tawc.sh` files; both become harmless no-ops once
Kotlin sets the same vars (env from spawn beats env from `/etc/profile`
because variables already exported aren't overwritten by `export VAR=`
with the same value). No need to clean them up. New installs after
the change just don't write them.
