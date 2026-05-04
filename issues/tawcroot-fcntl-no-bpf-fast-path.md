# tawcroot: every guest `fcntl` traps; no BPF fast-path like `close`

`handle_fcntl` (`tawcroot/src/syscalls_fd.c:118-136`) is the only fd-shape handler
that has to do real work on most calls — `F_DUPFD`/`F_DUPFD_CLOEXEC` cap their
target at `TAWCROOT_RESERVED_FD_BASE - 1`, every other op passes straight through
to the kernel. But `fcntl` is unconditionally in the trap set, so the BPF filter
unconditionally TRAPs every guest `fcntl(...)` regardless of fd.

`close` has a special-cased fast-path in the BPF that loads `args[0]` and
JEQ-ladders against `tawcroot_reserved_fds[]`, only TRAPing when the fd is one
of ours (`tawcroot/src/filter_build.c:72-94`). That mattered because pacman/gpgme
hammer `close(fd)` for `fd in [3, RLIMIT_NOFILE)` — without the fast-path that
was a million handler invocations per fork-exec dance. Same shape (although
maybe not the same volume) applies to `fcntl`: glibc's TLS / locking primitives,
GnuTLS' fd-flag scrubbing, and several stdlib hot paths issue `fcntl(fd, F_GETFD)`
or `fcntl(fd, F_SETFD, FD_CLOEXEC)` against fds that aren't in our reserved set.

## What the fast-path would look like

Same shape as the close special case:

- For `F_DUPFD` / `F_DUPFD_CLOEXEC`: TRAP only when arg2 (the minimum target
  fd) `>= TAWCROOT_RESERVED_FD_BASE`. Below that, the kernel's own search for
  a free fd is guaranteed to land below our range and the cap logic is a no-op.
- For all other `op` values: TRAP only when arg0 (fd) is in
  `tawcroot_reserved_fds[]` — same JEQ ladder as close.

Today's handler code already encodes this discrimination correctly; the BPF
just doesn't know about it.

## Why this hasn't bitten yet

We don't have profiling data. Firefox + the integration suite work, so the
overhead isn't pathological. But the same reasoning that justified the close
fast-path applies, and the fix is mechanical: extend `tawcroot_build_filter`
with a second special-case branch for `TAWC_SYS_fcntl`.

## When to do this

When we have a workload that's measurably handler-bound on `fcntl` traps. The
diagnostic is straightforward: count `tawcroot_dispatch_*` invocations by
syscall number under a stress run.
