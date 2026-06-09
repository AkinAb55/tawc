# tawcroot: exec path stages through static buffers — concurrent execs can interleave

`syscalls_exec.c` (`argv_strings[64K]`, `envp_strings[256K]`,
`path_buf`, `guest_path`, `resolved`, the `ptrs` arrays) and
`exec_handler.c` (`state_buf[64K]`, `shm_name_buf`) stage the guest's
exec arguments in `static` storage. SIGSYS handlers run concurrently on
multiple guest threads (sa_mask only masks signals on the trapping
thread), so:

- two threads calling `execve` simultaneously, or
- a `CLONE_VM` / `posix_spawn` (glibc uses CLONE_VM|CLONE_VFORK —
  shared address space) child exec'ing while a sibling thread in the
  parent execs

interleave writes and can exec with corrupted path/argv/env. The
common fork-then-exec case is safe (COW copies the statics), which is
why this hasn't bitten yet.

This violates the documented handler invariant (notes/tawcroot.md
"Threading and `vfork` invariants": all per-call state stack-local).
The buffers are static because ~400 KB doesn't fit the handler stack
budget.

## Fix directions

- A small spinlock around the whole collect→write→execveat sequence
  (exec is a terminal operation for the thread; blocking a concurrent
  exec briefly is fine and vfork-safe since the winner never returns).
- Or per-thread buffers from a small pool, same shape as
  `path_scratch.c`.

`exec_handler.h` carries a NOTE pointing here.

## Severity

Medium: real race, low frequency (needs concurrent exec in one
address space).
