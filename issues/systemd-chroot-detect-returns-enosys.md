# systemd's chroot-detection returns ENOSYS in pacman post-transaction hooks

After the chroot fix, pacman post-transaction hooks that ask "is the
current root booted?" now return:

    Failed to check for chroot() environment: Function not implemented
      Skipped: Current root is not booted.

…in `systemd-hook` invocations for `systemctl daemon-reload`,
`udevadm`, `sysctl --system`, and similar. The hook handles the
ENOSYS gracefully and skips itself, so this isn't fatal — but it's
the wrong reason to skip. systemd should detect chroot via stat /
inode comparison and the hook should run (and then no-op because
systemd isn't PID 1 — that's a separate but legitimate skip).

## Likely root cause

systemd's `running_in_chroot_or_offline()` calls `read_one_line_file
("/proc/1/sched")` plus a stat compare of `/proc/1/root` and `/`. One
of these underlying syscalls is returning ENOSYS — most likely
`statx(2)` since glibc routes `stat` through it on aarch64 and
tawcroot doesn't have a statx handler installed (verify against the
trap list in `tawcroot/src/dispatch.c`).

## Repro

Same as
`issues/systemd-pacman-scriptlets-fail-with-non-absolute-path.md`:

    bash scripts/uninstall-distro.sh manjaro
    bash scripts/install-distro.sh manjaro tawcroot \
        mirrorProxy=http://127.0.0.1:8080/proxy/ distro=manjaro

Search the log for `chroot() environment: Function not implemented`.

## Severity

Low — the hooks gracefully skip and the install reports
`[stage:DONE] Installed`. But the error noise is misleading and
hides real bugs in hooks that *do* care.
