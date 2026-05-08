# systemd binaries called from pacman scriptlets abort: `'path_is_absolute(p)' failed`

After the chroot fix (commit 4244bbb + the build-script + Gradle fix
that actually got it onto the device), pacman scriptlets and hooks now
run, but several systemd commands abort during a Manjaro install with
tawcroot:

    Assertion 'path_is_absolute(p)' failed at src/basic/chase.c:648, function chase(). Aborting.

Affected commands include `systemd-sysusers`, `systemd-hwdb`,
`systemd-tmpfiles --create`, `systemd-tmpfiles --remove`,
`journalctl --update-catalog`. Each aborts with SIGABRT and pacman
prints `error: command failed to execute correctly`.

This means the post-install state is still incomplete after the
install — system users aren't created, tmpfiles aren't laid down,
hwdb isn't compiled. Less catastrophic than the original chroot
problem (the GTK/icon/MIME caches DO get rebuilt — verified those
exist post-install), but still wrong.

## Repro

    bash scripts/uninstall-distro.sh manjaro
    bash scripts/install-distro.sh manjaro tawcroot \
        mirrorProxy=http://127.0.0.1:8080/proxy/ distro=manjaro

Look for `Assertion 'path_is_absolute(p)' failed` in the install log.

## Likely root cause

systemd's `chase()` (src/basic/chase.c) is called with a relative or
empty path. It's asserting `path_is_absolute(p)` on its first
argument. This points at tawcroot returning something non-absolute
to a `getcwd`/`readlink`/`stat`-style call that systemd then
forwards verbatim. Worth straceing one of the failing commands under
tawcroot to identify which syscall is misbehaving.

## Related

`Failed to check for chroot() environment: Function not implemented`
appears alongside this in the install log — different scriptlets,
filed separately under
`issues/systemd-chroot-detect-returns-enosys.md`.
