# tawcroot: no fd-provenance table; `fchdir`/path-relative ops can't shortcut

The plan's "Threading and `vfork` invariants" section calls out an
fd-provenance table as future work. Today we have:

- A reserved fd range (`TAWCROOT_RESERVED_FD_BASE = 1000`) that
  the guest can't see. Sufficient for protecting tawcroot-internal
  fds from `dup2` collisions.
- **No tracking of where a guest fd was opened from.** When the
  guest calls `fchdir(fd)` or `openat(fd, "...", ...)`, we don't
  know whether `fd` originally pointed inside the rootfs, into a
  bind src, or at a host path the guest somehow obtained.

The fallback we use is `/proc/self/fd/<fd>` resolution at
syscall time, which works correctly but pays a syscall round-trip
on every path-relative op.

## Why this matters

Today: nothing. Path-relative ops aren't hot enough for the
`/proc/self/fd` fallback to show up in profiles, and the
correctness of translation is unaffected.

Becomes relevant when:

- A workload does many path-relative ops (some build systems,
  certain language runtimes).
- We add per-thread state for the SIGSYS path (tracked in
  `tawcroot-handler-signal-state-not-thread-safe.md`); fd
  provenance also wants per-process invariants under `vfork`.

## Fix sketch

A small process-wide table mapping `fd → provenance` (rootfs
relative path / bind id / host path), updated on the syscalls
that produce fds (`open`, `openat`, `dup`, `dup2`, `dup3`,
`fcntl(F_DUPFD)`, `fcntl(F_DUPFD_CLOEXEC)`, exec) and consulted
on path-relative ops.

The table needs the same lock-free snapshot discipline as the
bind table for vfork/multithread safety. Cross-process
inheritance is already handled by exec_state passing the fd map
through the re-exec dance.

## Severity

Deferred. No correctness impact, no current workload exercises
the cost.
