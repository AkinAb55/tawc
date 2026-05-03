# tawcroot: guest signal-state shadows are not thread-safe

`syscalls_control.c` keeps two pieces of guest-visible signal state
as plain process-global variables:

- `g_guest_sigsys_blocked` — shadow of whether the guest blocked
  SIGSYS via `rt_sigprocmask`.
- `g_guest_sigsys_action` — the guest's installed `sigaction` for
  SIGSYS.

Each kernel thread has its own real `sigprocmask`. When the guest
is multi-threaded, the shadows are wrong by construction:

- Two threads concurrently calling `rt_sigprocmask({SIGSYS})` race
  on the shared `int`. One write wins; readbacks return a value
  that doesn't match what either thread asked for.
- The plain-`int` writes are torn-load-safe on x86_64/aarch64, but
  the design doc commits to a stricter standard ("Threading and
  vfork invariants" prescribes lock-free snapshot tables).
- The handler reads/writes the action pointer from the SIGSYS path
  while the `rt_sigprocmask` handler may write it from another
  thread — no atomic, no fence.

`syscalls_control.c:56-58` notes "Single thread of control for now
(phase-1 smoke is single-threaded; multi-thread + per-thread mask
is a phase-2 follow-up)." That captures the intent but understates
the risk for guest workloads that legitimately use threads
(Firefox, Wayland clients, glib worker pools).

## Fix sketch

- Per-thread `__thread` storage for the sigprocmask shadow (or a
  seqlock around an array indexed by tid).
- Atomic copy of the sigaction struct via `__atomic_load_n` /
  `__atomic_store_n` on a pointer to an immutable record,
  mirroring the "snapshot pointer published with release
  semantics" pattern in the design doc.

## Test gap

There is no multi-thread regression test for these globals (or for
any handler global, e.g. `g_obs`). The existing handler tests are
all single-threaded. Suggested test (post-loader): run a
multi-threaded guest making concurrent path-bearing syscalls and
check that translation still succeeds. It won't deterministically
catch the race but will catch gross corruption.

## Severity / priority

Correctness, not feature gap. First thing to break the moment we
run a multi-threaded guest that touches signal state. Higher
priority than the deferred fd-provenance work because that's a
performance/observability gap; this is a real bug under any
real-world Wayland app.
