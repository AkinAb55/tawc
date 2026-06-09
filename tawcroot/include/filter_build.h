/* Pure cBPF-program builder for the tawcroot seccomp filter.
 *
 * Lives separately from `filter.c` so the build logic can compile into
 * the cleat unit-test orchestrator (PROD_C_FOR_TESTS) without dragging
 * in `raw_sys.h` / inline-asm globals. Tests call this directly,
 * inspect the resulting program with a hosted BPF interpreter, and
 * assert on the action for a battery of `(nr, ip, args[0])` inputs.
 *
 * Production's `tawcroot_install_filter` is now a thin wrapper that
 * builds the program here, then hands it to `seccomp(2)` via the raw
 * syscall stub.
 */

#pragma once

#include <stddef.h>
#include <stdint.h>

#include <linux/filter.h>

/* Build the trap-or-allow cBPF program into `prog` (capacity
 * `prog_cap` instructions). Returns program length on success;
 * -E2BIG when `prog_cap` is too small for the requested trap set,
 * when `n_traps` exceeds the kernel program budget, or when
 * `n_reserved` > 251 (the close block's u8 jump offsets can't encode
 * more); -EINVAL for a NULL / zero-capacity program buffer.
 *
 * Inputs:
 *   trap_nrs       — syscall numbers to TRAP. Order is preserved.
 *                    `close` (`TAWC_SYS_close`) is special-cased to
 *                    only TRAP when args[0] is in `reserved_fds`.
 *   n_traps        — number of entries in trap_nrs.
 *   stub_ret_addr  — address of the instruction immediately AFTER the
 *                    stub's syscall instruction (matches what the
 *                    kernel reports as `seccomp_data.instruction_pointer`
 *                    for a syscall issued through the stub). The IP
 *                    allowlist compares against this 64-bit value.
 *   reserved_fds   — fd values that, when passed to `close`, must
 *                    TRAP rather than ALLOW. NULL or zero count means
 *                    every close TRAPs (i.e. no fast path).
 *   n_reserved     — number of entries in reserved_fds.
 *   audit_arch     — AUDIT_ARCH_* constant for this build (the
 *                    prologue KILL_PROCESSes any other arch). */
long tawcroot_build_filter(struct sock_filter *prog, size_t prog_cap,
			   const int *trap_nrs, size_t n_traps,
			   uint64_t stub_ret_addr,
			   const int *reserved_fds, size_t n_reserved,
			   uint32_t audit_arch);
