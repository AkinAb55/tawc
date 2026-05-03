# tawcroot: cBPF filter has no unit-level correctness sweep

The seccomp-bpf filter that decides which syscalls trap is built by
hand and only exercised end-to-end via the smoke tests. There's no
test that:

1. Builds the cBPF program with a known trap set + stub stub-IP.
2. Walks the bytecode as if we were the kernel.
3. Asserts the action for a battery of `(nr, ip)` inputs against
   what the trap-list logic says it should be.

Without that, a regression in the BPF builder (off-by-one in the
trap-list iteration, wrong arch check, IP allowlist comparison)
only fails end-to-end — usually in the form of a syscall slipping
through unhandled, which manifests as a confusing guest-side
error.

## Suggested approach

The plan called for cross-checking against `libseccomp`'s
`seccomp_export_pfc`: build our filter, hand it to libseccomp,
read back its human-readable PFC dump, diff against a golden
file. This catches both correctness regressions and unintended
filter-shape drift.

Alternative: write a minimal BPF interpreter in the test harness
and table-test inputs. Less external dep, more code.

## Priority

Medium. The BPF program is small enough today that hand-review
catches most bugs; this becomes more important as we add more
trapped syscalls and the trap-list iteration gets more complex.
