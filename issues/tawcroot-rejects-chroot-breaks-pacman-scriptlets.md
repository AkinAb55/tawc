# tawcroot rejects `chroot()`, breaking every pacman scriptlet/hook on Manjaro install

`tawcroot/src/syscalls_control.c` denies `chroot(2)` with `-EPERM`
(`fake_eperm`). On Manjaro install, the bootstrap ships pacman
**6.0.2**, which calls `chroot(handle->root)` unconditionally inside
`_alpm_run_chroot()` (libalpm/util.c) — even when `RootDir == "/"`.
The chroot fails with EPERM and pacman prints:

    error: could not change the root directory (Operation not permitted)
    error: command failed to execute correctly

…once for every package post_install / post_upgrade scriptlet **and**
every post-transaction hook (~39 pairs in a Manjaro `pacman -Syyu`).
The transaction otherwise completes (`[stage:DONE] Installed`), but
the rootfs is left with the install-side cache files un-generated.

Pacman 7.0.0 added the `if (strcmp(handle->root, "/") != 0 &&
chroot(handle->root) != 0)` guard. Once the in-progress transaction
upgrades pacman to 7.0.0.r10, *future* invocations work — but the
running transaction still uses the bootstrap binary, so its scriptlets
and hooks have already failed by then.

## Verified consequences in the rootfs

After a clean `bash scripts/install-distro.sh manjaro tawcroot
mirrorProxy=…`, **all of these are missing**:

- `usr/share/glib-2.0/schemas/gschemas.compiled` — GSettings broken;
  many GTK apps fail or fall back to defaults
- `usr/share/icons/Adwaita/icon-theme.cache`, `…/hicolor/…` — slow
  icon lookup, missing icons
- `usr/share/mime/mime.cache` — file-type detection broken
- `usr/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache` — GTK can't load
  most image formats

Re-running each tool by hand inside the chroot succeeds:

    bash scripts/tawc-rootfs-run.sh 'gtk-update-icon-cache /usr/share/icons/Adwaita'
    # -> "Cache file created successfully"
    bash scripts/tawc-rootfs-run.sh 'glib-compile-schemas /usr/share/glib-2.0/schemas/'
    # -> silent success

…confirming the hook tools work fine; only pacman's `chroot()` wrapper
is the blocker.

## Repro

    bash scripts/uninstall-distro.sh manjaro
    bash scripts/install-distro.sh manjaro tawcroot \
        mirrorProxy=http://127.0.0.1:8080/proxy/ distro=manjaro
    # ~39× "could not change the root directory (Operation not permitted)"
    # ~39× "error: command failed to execute correctly"
    # final stage still: [stage:DONE] Installed

Then verify the missing caches with the `ls` paths above.

## Fix options

1. **Loosen tawcroot's `fake_eperm` for `chroot`** — return 0 (no-op)
   when the target path resolves to the current rootfs root (or `"/"`
   in the guest namespace), keep `-EPERM` for everything else. The
   defensive comment ("would desync our root-relative bookkeeping")
   doesn't apply when the kernel chroot isn't actually called.
   Surgical, helps any tool that does the same pattern, not just
   pacman 6.x.

2. **Pre-upgrade pacman in its own transaction** — `pacman -S
   --noconfirm pacman` first, then proceed with the full `-Syyu`
   using the new (7.0+) binary. Avoids touching tawcroot but adds a
   step to `ArchPacmanCommon.installBasePackages` and inherits any
   "partial-upgrade not supported" warnings from the in-between
   state.

3. **Run the hooks manually post-install** — explicit
   `glib-compile-schemas`, `gtk-update-icon-cache`,
   `update-mime-database`, `gdk-pixbuf-query-loaders` step in
   `installBasePackages` *after* pacman exits. Workaround flavour
   — works but is brittle to upstream hook additions.

Recommendation: (1). The other "permission" / "warning" noise in the
install log (DisableSandbox unrecognized, `Cannot restore extended
attributes: security.capability`, `directory permissions differ`,
`pacman.conf.pacnew`, dep cycle warnings) is genuinely cosmetic and
not worth touching.

## Related

`issues/tawcroot-arch-pacman-intermittent-sig-fail.md` — same install
pipeline, different intermittent failure (gpgme signature check).
Manjaro hits that one too on a minority of runs (the cancelled run
during this investigation hung on the SIGSYS-handler-loop variant
described there).
