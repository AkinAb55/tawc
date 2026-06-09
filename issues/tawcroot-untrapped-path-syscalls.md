# tawcroot: path-bearing syscalls missing from the dispatch table fall through to the host view

The BPF default is RET_ALLOW, so a path syscall that isn't in the
dispatch table resolves against HOST paths — a silent view escape (or
spurious ENOENT), not a loud ENOSYS. Currently untrapped:

- **`openat2` (NR 437)** — already in `sysnr.h` but never installed.
  Used by systemd, runc, newer glibc fallbacks. With `AT_FDCWD` + an
  absolute path it's a complete translation bypass. Either emulate
  (decompose `struct open_how` into openat-equivalent flags, reject
  RESOLVE_* we can't honour) or trap → `-ENOSYS` so glibc falls back
  to openat. ENOSYS is the cheap correct move.
- **`inotify_add_watch`** — GLib/GIO file monitors call it constantly;
  watches silently target host paths (usually ENOENT, sometimes a
  real host file). Needs path translation (it's a plain
  (fd, path, mask) shape — easy handler).
- **`fchmodat2`** (kernel 6.6+; glibc 2.39 uses it for
  AT_SYMLINK_NOFOLLOW). Trap → ENOSYS (fallback exists) or translate.
- **x86_64 legacy set is incomplete**: `creat`, `utime`, `utimes`,
  `futimesat` are unregistered while `open`/`stat`/`unlink` etc. are.
  Emulator-only exposure.
- **`fstat` is undecorated**: `fstat(fd)` returns the real app uid
  while `stat`/`fstatat`/`statx` fake uid/gid 0. Programs comparing
  the two (tar/cpio ownership checks) see an inconsistent fake-root
  world. Add an fstat handler that forwards and zeroes uid/gid.
- **`fchdir` untrapped**: `fchdir(reserved_fd)` works, violating
  fdtab.h's "reserved fds behave as EBADF" contract (also see
  tawcroot-minor-syscall-divergences.md for other reserved-fd
  contract gaps).

Cheap detector for the future: a `TAWCROOT_TRACE`-style audit mode
that logs path-bearing NRs seen via RET_ALLOW would catch new kernel
additions before users do.

## Severity

Medium overall; openat2 + inotify_add_watch are the field-relevant
ones (systemd, GTK apps).
