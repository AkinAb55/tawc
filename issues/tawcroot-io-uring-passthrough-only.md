# tawcroot: `io_uring_setup` returns -ENOSYS; no SQE rewriter

We currently TRAP `io_uring_setup` and return `-ENOSYS`, forcing
guests to fall back to syscall-based I/O. The plan
(`notes/tawcroot.md` "Open questions" #1) sketches a full SQE
rewriter (~500-1000 lines as `src/uring.c`) that would let
io_uring submissions go through path translation the same way
the syscall path does.

Deny-and-fall-back is a correctness fix, not a feature gap —
guests that probe for io_uring just take the slower path. The
SQE rewriter is purely a performance win for workloads that
actually benefit from io_uring.

## When to revisit

When something we ship actually wants io_uring throughput. None
of the current targets do: bash, pacman, Firefox, libhybris all
fall back gracefully. Likely candidates if the target list grows:

- `fio`-style benchmarks
- Modern databases inside the chroot
- Newer container runtimes

## Fix sketch (when we get there)

`src/uring.c` that wraps the SQ ring buffer:

- Intercept `io_uring_setup`, allocate our own SQ/CQ, return a
  shadow fd to the guest.
- On `io_uring_enter`, walk the guest's SQ entries; for path-
  bearing opcodes (OPENAT, OPENAT2, STATX, RENAMEAT, …),
  translate the path before forwarding to the real ring.
- Mirror CQEs back, accounting for any path-translation errors
  as kernel-style completion errnos.

Non-trivial. Defer until needed.

## Severity

Performance only. No workload today is blocked.
