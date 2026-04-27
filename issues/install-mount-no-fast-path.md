- Each `ChrootRunner.run` is a fresh `su` shell with a private mount
  namespace, so the full mount setup runs every chroot entry. Legacy
  `client/arch-chroot-run` had a fast path (`if /sys is mounted`)
  that skipped ~400ms of mount setup on repeated invocations because
  mounts persisted in the global namespace.
- The new design is deliberate (it avoids the zygote-fork crash from
  having live bind mounts under `/data/data/<pkg>` during package
  fork — see notes/installation.md), but it does mean every shell
  command in the chroot pays the mount-setup cost.
- Not a regression in correctness, but a real perf hit if any
  workflow runs many short commands (e.g. integration tests doing
  `chroot_run` per assertion).
- Possible mitigations if it bites:
  - Batch multiple commands into one `RUN` (already possible — RUN
    accepts arbitrary multi-line scripts).
  - Long-lived helper shell inside the chroot, fed commands over a
    pipe, so we pay mount setup once per session.
- Defer until benchmarks show it matters.
