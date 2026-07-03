# tawcroot Future Work

This collects deferred tawcroot work that is not required by the current
release-supported path.

## io_uring

Current MVP behavior should deny `io_uring_setup` with `-ENOSYS` so guests fall
back to ordinary syscalls. Passing io_uring through would let path-bearing SQEs
(`OPENAT`, `STATX`, etc.) bypass tawcroot's path translation.

Full interception is a later, larger module:

- Trap `io_uring_setup`, `io_uring_register`, `io_uring_enter`, and ring `mmap`.
- Reject `IORING_SETUP_SQPOLL`; Android `untrusted_app` lacks `CAP_SYS_NICE`,
  and rejecting SQPOLL keeps `io_uring_enter` as the chokepoint.
- On enter, walk newly submitted SQEs, translate path-bearing operands, rewrite
  SQE path pointers to owned translated buffers, and free those buffers when
  matching CQEs complete.
- Treat unknown future opcodes as explicit allow/warn/deny decisions.

Expected size: roughly 500-1000 LOC in a self-contained `src/uring.c`.

## Additional `/proc` Shadows

Already implemented:

- `/proc/self/maps` and `/proc/<our-pid>/maps`
- `/proc/sys/kernel/overflow{uid,gid}`
- `/proc/bus/pci/devices`
- fd-relative forms such as `openat(proc_dir_fd, "self/maps", ...)`

Extend the same memfd-shadow pattern only when a workload needs it. Likely
future candidates:

- `/proc/<pid>/cmdline`
- `/proc/<pid>/auxv`
- `/proc/<pid>/task/<tid>/maps`

## Syscall User Dispatch

`PR_SET_SYSCALL_USER_DISPATCH` on kernel 5.11+ could replace seccomp-BPF
trapping on supported devices. Benefits:

- avoid BPF evaluation on every syscall;
- avoid the stacked-filter precedence problem;
- allow a per-thread selector byte for disabling interception while the handler
  issues host syscalls;
- potentially relax the non-PIE requirement on newer kernels.

The primary test device is kernel 5.4, so this remains future work. Probe at
init and fall back to the existing BPF IP-check approach on older kernels.

## Performance Ideas

These are throughput wins, not correctness fixes. Profile before implementing.

1. **Skip manual symlink resolution on kernel 5.6+ when safe.** On newer kernels
   `openat2(..., RESOLVE_IN_ROOT)` can do symlink resolution. Skip the manual
   resolver only when `tawcroot_openat2_works` and the bind table cannot be
   crossed by a symlink target. Estimated win on dlopen-heavy paths: 30-50%.
2. **Path-component negative cache.** Cache recent "not a symlink" prefix
   components so the resolver avoids repeated `readlinkat` calls. Use a bounded
   table and invalidate on root-view changes and relevant `symlinkat` calls.
3. **fd-provenance table.** Track `fd -> rootfs/bind/host provenance` at fd
   creation and duplication sites to avoid `/proc/self/fd/<n>` lookups for
   fd-relative path operations.
4. **io_uring SQE rewriter.** Promote the deny-and-fallback behavior to full
   interception once a real workload needs io_uring throughput.

(A former item — returning fake identity directly from BPF via
`SECCOMP_RET_ERRNO | 0` — was dropped: identity is now stateful
(identity.c virtual identity) and BPF cannot return dynamic values.)

Micro-optimizations such as BPF-chain reshaping or `gettid` caching should wait
for a profile that points at them.
