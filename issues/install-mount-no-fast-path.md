# Chroot mount setup runs on every `ChrootRunner.run`

There's no fast path that skips mount setup when the chroot is already prepared, so every chroot entry pays a ~400ms cost.

## Background

Each `ChrootRunner.run` is a fresh `su` shell with a private mount namespace, so the full mount setup runs every chroot entry. Earlier iterations of this project had a fast path (`if /sys is mounted`) that skipped the mount setup on repeated invocations because mounts persisted in the global namespace.

The new design is deliberate: it avoids the zygote-fork crash that came from having live bind mounts under `/data/data/<pkg>` during package fork (see [notes/installation.md](../notes/installation.md)). But it does mean every shell command in the chroot pays the mount-setup cost.

This isn't a correctness regression, but it's a real perf hit for any workflow that runs many short commands — e.g. integration tests doing `chroot_run` per assertion.

## Possible mitigations

- Batch multiple commands into one `RUN`. This is already possible since `RUN` accepts arbitrary multi-line scripts.
- A long-lived helper shell inside the chroot, fed commands over a pipe, so we pay mount setup once per session.

## Status

Defer until benchmarks show it matters.
