# tawcroot: io_uring is denied, not translated

`io_uring_setup`, `io_uring_register`, and `io_uring_enter` are all trapped in `tawcroot/src/syscalls_control.c` and return `-ENOSYS`. That is safe for current workloads because libraries that probe io_uring fall back to syscall-based I/O.

## Remaining gap

tawcroot does not implement real io_uring translation. If a future workload requires io_uring, tawcroot would need to trap setup/register/enter plus the ring `mmap`, then rewrite path-bearing SQEs before submit.

Path-bearing ops include `OPENAT`, `OPENAT2`, `STATX`, `RENAMEAT`, `UNLINKAT`, `LINKAT`, `SYMLINKAT`, `MKDIRAT`, and newer kernel additions. Unknown opcodes should be explicit allow/warn/deny decisions rather than blindly passed through.

## Severity

Low. No shipped workload currently needs io_uring throughput, and the deny path is intentionally fail-closed. Revisit if a guest treats `io_uring_setup -ENOSYS` as fatal or performance work needs async I/O.
