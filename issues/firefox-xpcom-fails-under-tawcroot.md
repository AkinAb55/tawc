# Firefox under tawcroot fails with "Couldn't load XPCOM"

Regression — `notes/firefox.md` claims firefox works under tawcroot
as of 2026-05-02 ("`apps::test_firefox_launches_with_hardware_buffers`
integration test passes on the OnePlus 9"), but on main today firefox
prints `Couldn't load XPCOM` and exits before opening a window. All
other integration tests under tawcroot pass.

## Repro

```bash
adb shell am start -n me.phie.tawc/.install.InstallActivity \
    --es autoStart true --es id void --es method tawcroot --es distro void
# wait for install to finish (couple of minutes with Fastly mirror)
TAWC_INSTALL_ID=void bash scripts/install-test-deps.sh
TAWC_INSTALL_ID=void bash scripts/tawc-chroot-run.sh firefox --no-remote
# -> Couldn't load XPCOM.
```

`apps::test_firefox_launches_with_hardware_buffers` reproduces it via
the integration suite (`Failed to read PID/PGID from pidfile … within
10s` because firefox crashes before its tracker process can fork).

## What we know

`strace` shows openat / readlinkat / fstatat calls from inside firefox
returning `-1 ENETDOWN (Network is down)` for absolute paths under
`/etc/`, `/usr/lib/`, `/usr/local/lib/gl-shims/`, and `/proc/self/`:

```
openat(AT_FDCWD, "/etc/ld.so.cache", O_RDONLY|O_CLOEXEC) = -1 ENETDOWN
openat(AT_FDCWD, "/usr/lib64/libstdc++.so.6", O_RDONLY|O_CLOEXEC) = -1 ENETDOWN
openat(AT_FDCWD, "/usr/local/lib/gl-shims/libm.so.6", O_RDONLY|O_CLOEXEC) = -1 ENETDOWN
readlinkat(AT_FDCWD, "/proc/self/exe", …, 4095) = -1 ENETDOWN
```

ENETDOWN is errno 100. None of tawcroot's handlers return -100; the
TAWCROOT_TRACE-built `[t] pid=… nr=… rv=…` lines for the firefox run
all show `rv=0`, `rv=-2`, `rv=3`, `rv=-38` — no -100. So the handler
is NOT issuing the ENETDOWN; something is short-circuiting it.

The same syscalls from a static aarch64 test binary in the same
chroot return ENOENT correctly (handler runs, returns -2). Firefox is
dynamically linked — the failing calls might be coming through a path
that bypasses our seccomp-IP allowlist incorrectly, or Android's
stacked filter is RET_ERRNO'ing them. We haven't pinned which.

The same firefox binary works under chroot mode in the same rootfs.

## Hypotheses to narrow next

1. Bisect tawcroot commits since 2026-05-02 (when firefox was last
   verified working) to find the regression. Suspects on this branch:
   - `2da7554 Catch fd-relative and per-TID /proc/self synthesis`
   - `c9d0d2d Filter reserved fds out of /proc/self/fd getdents64 output`
   - `8d7c2df Reverse-translate /proc/self/maps so guests don't see host paths`
   - `1bb6eab Mechanical cleanups: errno_neg.h, …, phase1 split`
2. Check whether the failing IP (`si_call_addr` from SIGSYS event)
   happens to land inside the `tawcroot_raw_syscall_ret` allowlist
   range — would mean the filter is skipping the trap and the kernel
   really is returning ENETDOWN, which would point at SELinux or
   Android's stacked filter.
3. Confirm Android's `untrusted_app` stacked filter doesn't have a
   RET_ERRNO clause that produces 100 for these openats. (Unusual,
   but worth ruling out.)

## Workarounds for now

- Install with `--es method chroot` for firefox testing on real
  devices. Tawcroot still works for everything else.
