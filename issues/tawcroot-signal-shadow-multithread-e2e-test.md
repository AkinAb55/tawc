# tawcroot: SIGSYS-blocked shadow — multi-thread end-to-end test still TODO

The torn-write hazard, slot leak, and TID-reuse race the issue
originally tracked are all closed (lock-free per-thread table with
tombstones; exit-side hook clears the dying thread's slot via
`handle_exit` → `tawc_sigshadow_blocked_clear`). Full design + the
one accepted residual (involuntary thread death — SIGKILL of one
thread, fatal signals that bypass `exit(2)`) live in the
`signal_shadow.c` header comment.

What's still missing is **end-to-end multi-thread coverage** of the
`rt_sigprocmask` / `rt_sigaction` handlers running through a real
SIGSYS handler in a guest. The existing testhost smoke is
single-threaded; the unit tests in `tests/unit/test_signal_shadow.c`
exercise the lock-free primitives directly (16-thread blocked
isolation, 4×4 writer/reader seqlock, tombstone probe-chain
preservation, slot reclamation) but not the full handler pipeline.

A phase-1 testhost case that:
- spawns N pthreads
- each blocks/unblocks SIGSYS through the guest's normal `sigprocmask`
- each issues several syscalls that trap (so the handler runs on
  that thread with our shadow updates in flight)
- on join, asserts every thread's read-back mask matches what it set

…would close out this issue.

## Severity

Low. Slim follow-up — the actual race is fixed and tested at the
helper layer; this is just the missing integration-level
verification.
