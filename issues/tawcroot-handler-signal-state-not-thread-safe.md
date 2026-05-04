# tawcroot: residual TID-reuse race on guest "SIGSYS blocked" shadow

The thread-safety fixes have landed; this issue is reduced to a small
residual race inherent to the per-thread state model.

## What was fixed

`syscalls_control.c` previously kept two pieces of guest-visible
signal state as plain process-globals — a torn-write hazard the
moment a multi-threaded guest touched signal state. Both have moved
into `signal_shadow.c`:

- `g_guest_sigsys_blocked` → per-thread shadow in a TID-keyed
  open-address table (256 slots, linear probe). Slot updates use
  `__atomic_compare_exchange` to claim and `__atomic_store/load` for
  the bit. Per-thread isolation is enforced by lookup; concurrent
  setters on the same tid serialize via the bit's atomic store;
  concurrent setters on different tids that hash to the same slot
  resolve via the CAS-claim spin.

- `g_guest_sigsys_action` → process-wide seqlock. Even seq = stable,
  odd = writer in progress. Writers `CAS(seq, even→even+1)` to claim,
  publish bytes, then store `seq+=1`. Readers retry on inconsistent
  reads. Multi-writer-safe via the CAS-claim spin.

Helpers are pure (no syscalls), async-signal-safe (only
`__atomic_*` builtins + plain byte copy), and exercised under
hosted glibc by `tests/unit/test_signal_shadow.c` — single-thread
basics plus a 16-thread blocked-isolation stress and a
4×4-writer/reader seqlock stress (50K iterations each).

## Residual race: TID reuse

If thread A blocks SIGSYS (slot for tid=A_tid set to blocked=1), A
exits without unblocking, and the kernel later reuses A's tid for
thread B, B will read the stale 1 until it issues an
`rt_sigprocmask` with `SIG_SETMASK` (always rewrites the bit) or
`SIG_BLOCK`/`SIG_UNBLOCK` with SIGSYS in the set (only those touch
the bit — blocking an unrelated signal leaves the SIGSYS shadow
untouched). POSIX-wise B should inherit its creator's mask, not
A's stale state.

We have no way to tell "first appearance of a tid" from "tid
reuse" without one of:

- A clone hook to clear the new tid's slot when a thread is
  created. We don't trap clone(2) in handler dispatch (only
  clone3, which we currently fake-ENOSYS); even if we did, the
  parent-side trap doesn't have the child tid yet.
- A `tgkill(0, tid, 0)` probe on every read to detect a dead-tid
  slot. Async-signal-safe, but a syscall per probe per
  rt_sigprocmask is too expensive.
- An exit-side hook clearing the dying thread's slot. Would need
  the seccomp filter to RET_TRAP `exit`/`exit_group`, which we
  currently allow. Cost: one extra trap per thread teardown,
  probably acceptable but adds dispatch surface.

In practice:
- Realistic guests set-then-read; the bug only fires when a thread
  reads its mask without first writing it.
- The kernel doesn't reuse tids while any thread holds them;
  pid_max is typically ≥32K on Android.
- Behavior under the race: B reads back a wrong "old" mask once;
  no crash, no data corruption.

The current "documented residual" stance reflects a cost-vs-impact
trade — none of the mitigations are free, and none of the
realistic guest workloads we ship to (pacman, glibc init, Firefox
content procs) hit the read-without-write pattern that exposes the
bug.

## Test gap

Multi-thread end-to-end coverage of the rt_sigprocmask/rt_sigaction
handlers (running through the actual SIGSYS handler in a guest) is
still TODO — the existing testhost smoke is single-threaded. The
unit tests cover the lock-free primitives directly.

## Severity

Low. Reduced from "first thing to break under any multi-threaded
guest" to "edge case observable only on tid reuse after blocked-thread
exit". Keep the issue open as a marker until either the residual race
is fixed or the multi-thread phase-1 testhost lands.
