# tawcroot: BPF close fast-path doesn't protect fds reserved after filter install

The seccomp filter's `close` fast-path (only TRAP `close(fd)` when
`args[0]` is in the reserved set) bakes the reserved-fd list into the
BPF program **once**, at `prod_rootfs_init` time (`filter.c:76`,
`filter_build.c`). The filter is immutable kernel state and is
deliberately never reinstalled (`--exec-child` must not stack filters).

But fds are reserved at **runtime** too:

- guest `shm_open` → `tawcroot_shm_open` → `dup_to_reserved_inheritable`
  + `add_to_reserved_list` (`shm.c`)
- guest `chroot` → `tawcroot_fd_reserve` for the new root fd
  (`chroot.c:128`)

Those fds are in `tawcroot_reserved_fds[]` (so `tawcroot_fd_is_reserved`
recognises them) but NOT in the baked BPF set — a guest `close(fd)` on
them is `RET_ALLOW`ed and the kernel really closes them. The handler
never runs. The exact workload the fast path was built for (gpgme/
pacman fork-child `close()` loop over `[3, RLIMIT_NOFILE)`) therefore
kills any live shm memfd or post-chroot root fd in that child; later
kernel fd reuse can alias a guest file into the shm table or root slot
— the "or worse" scenario `fdtab.h` warns about.

`fdtab.h` and `shm.c` comments now document the gap and point here.

## Fix directions

- Reserve a fixed *pool* of fd slots at init (e.g. dup /dev/null into
  N high slots), bake the whole pool into the BPF set, and have
  runtime reservations take over pool slots (`dup3` onto a pool fd)
  instead of `F_DUPFD`-ing fresh ones.
- Or: trap ALL `close(fd >= TAWCROOT_RESERVED_FD_BASE)` in BPF
  (range compare instead of per-fd JEQ) — one extra SIGSYS per
  high-fd close, which the closefrom loop only hits 64 times max.
  Probably the simplest correct fix; the per-fd JEQ list only exists
  because an early revision trapped the whole `[1000, ∞)` range for
  EVERY close, which is not what a range compare does.

## Repro sketch

Guest: `shm_open("/x", O_CREAT|O_RDWR, 0600)` then
`close_range(3, ~0u, 0)`-style manual loop `close(1000..1064)`, then
`shm_open("/x", 0, 0)` — second open should succeed (segment alive);
post-bug it ENOENTs or, worse, returns a recycled fd.

## Severity

Medium-high: silent state corruption with a common workload pattern
(pre-exec fd hygiene loops), but requires guest shm/chroot use first.
