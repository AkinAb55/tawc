# tawcroot adb-shell device cleat suite fails on the emulator (84 tests)

`tawcroot/test.sh --device` against the rooted x86_64 emulator fails
84 of 1946 tests (2026-07-06, commit 80f8040 — reproduced on a
pristine tree, so not caused by the prod-env test work that found it).
Since `tests/integration/tests/tawcroot.rs` wraps this suite,
a full `scripts/run-integration-tests.sh` run on the emulator
currently fails too.

Failure clusters (all in the testhost smoke / handler layer):

- `/etc/probe` fixture goes missing mid-run: "open /etc/probe
  (regular file) for ioctl test" and later steps fail with
  `-errno = 2`, "maps test: open /etc/probe" skipped. Smells like the
  shared-fixture dangle class documented in notes/tawcroot/testing.md
  ("Device-environment sensitivities": v1 linkat fallback leaves a
  back-symlink at the OLD name) — but on the rooted emulator link(2)
  should pass through, so the trigger may be different (SELinux
  enforcement state? emulator image change?).
- "path syscall after close_range / seccomp denial / SIGSYS-block"
  steps fail.
- 8x "mt: thread mask round-trip consistent across iters".
- chroot family: "after chroot(NULL) /etc/probe still openable",
  `chroot("/etc/probe") -> -ENOTDIR`, "rootfs view intact" steps.

Next steps: rerun with a cleat filter on one failing module, get the
kv-context lines, and check `getenforce` / emulator image version.
Note the tawcroot **prod-env** suite (`tawcroot_prodenv::`, broker /
`untrusted_app`) passes 12/12 on the same emulator at the same
commit, so the production-sandbox path is healthy; this is specific
to the adb-shell (`su`-domain) environment the cleat orchestrator
runs in.
