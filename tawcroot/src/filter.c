/* Phase-0 seccomp filter.
 *
 * Goal: prove the IP allowlist contract before we wire up the rest. The
 * filter we install here is intentionally minimal:
 *
 *   1. arch != TAWCROOT_AUDIT_ARCH      → KILL_PROCESS  (defense in depth)
 *   2. instruction_pointer == &stub_insn → ALLOW         (our raw syscall)
 *   3. syscall_nr == trap_syscall_nr    → TRAP          (smoke-test target)
 *   4. default                          → ALLOW
 *
 * Phase 1 expands rule 3 into a generated jump table covering the full trap
 * set. The shape of rules 1, 2, and 4 is the contract that Approach A
 * (re-exec into ourselves) depends on — see notes/tawcroot.md "Why non-PIE".
 *
 * cBPF is 32-bit. Our stub address is 64-bit on both arches; we have to
 * compare it in two halves, low first then high. seccomp_data layout is:
 *
 *     offset 0  nr                  (s32)
 *     offset 4  arch                (u32)
 *     offset 8  instruction_pointer (u64)   <- low @8, high @12
 *     offset 16 args[0]             (u64)
 *     ...
 *
 * The `ip_lo`/`ip_hi` design-note comment in notes/tawcroot.md is off by 8
 * (claims IP is at 16); the kernel struct above is the source of truth.
 */

#include <stddef.h>
#include <stdint.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>

#include "arch.h"
#include "fdtab.h"
#include "filter.h"
#include "raw_sys.h"
#include "sysnr.h"

/* The label sitting *immediately after* the SYSCALL/SVC inside the stub.
 *
 * Empirically on Linux x86_64 and aarch64 the kernel populates both
 * `seccomp_data.instruction_pointer` and `siginfo_t::si_call_addr` from
 * pt_regs->ip / pt_regs->pc, which the syscall-entry asm sets to the
 * address AFTER the trapping SYSCALL/SVC (i.e. the instruction the
 * kernel will return to). On x86_64 SYSCALL is 2 bytes; on aarch64 SVC
 * is 4 bytes. So the address we compare against is the `_ret` label,
 * not the `_insn` label sitting on the syscall instruction itself.
 * See notes/tawcroot.md "Issuing host syscalls from the handler" for
 * the full breakdown of the three related addresses. */
extern char tawcroot_raw_syscall_ret[];

/* Compact constructors. Plain brace initializers (not compound literals
 * with an explicit cast — clang rejects those in array-initializer
 * position with `-Werror -Wmissing-braces`).
 *
 * sock_filter layout: { __u16 code, __u8 jt, __u8 jf, __u32 k }.
 *
 * <linux/filter.h> reuses the name `BPF_A` as an accumulator-source flag,
 * hence the TAWC_ prefix on these. */
#define TAWC_BPF_S(_code, _k)            { (_code), 0, 0, (_k) }
#define TAWC_BPF_J(_code, _k, _jt, _jf)  { (_code), (_jt), (_jf), (_k) }

long tawcroot_set_no_new_privs(void)
{
	return tawc_prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
}

long tawcroot_install_smoke_filter(int trap_syscall_nr)
{
	int one[1] = { trap_syscall_nr };
	return tawcroot_install_filter(one, 1);
}

long tawcroot_install_filter(const int *trap_nrs, size_t n_traps)
{
	const uintptr_t stub = (uintptr_t)&tawcroot_raw_syscall_ret[0];
	const uint32_t stub_lo = (uint32_t)(stub & 0xffffffffu);
	const uint32_t stub_hi = (uint32_t)(stub >> 32);

	/* The cBPF jump-table covering the trap set. Two instructions per
	 * trapped syscall: one JEQ on nr, then a RET TRAP if matched. We
	 * emit a fall-through RET ALLOW at the end. The kernel limit is
	 * BPF_MAXINSNS = 4096 — plenty for our ~30 traps. */
	if (n_traps > 1900) {
		return -7;  /* E2BIG */
	}

	/* Build the program in a fixed-size local array. 11 instructions
	 * for the prologue (arch + IP allowlist) + 2 per trap + 1 default
	 * ALLOW. Cap at 4096 to stay under BPF_MAXINSNS. */
	struct sock_filter prog[4096];
	size_t i = 0;

	/* Prologue: arch check, IP allowlist (low/high 32). */
	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_LD | BPF_W | BPF_ABS, 4);
	prog[i++] = (struct sock_filter)TAWC_BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
		TAWCROOT_AUDIT_ARCH, 1, 0);
	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);

	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_LD | BPF_W | BPF_ABS, 8);
	prog[i++] = (struct sock_filter)TAWC_BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
		stub_lo, 0, 3);
	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_LD | BPF_W | BPF_ABS, 12);
	prog[i++] = (struct sock_filter)TAWC_BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
		stub_hi, 0, 1);
	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

	/* Load syscall_nr once, then linear JEQ + TRAP for each trap_nr.
	 *
	 * Exception: `close` is hot enough to special-case. Pacman's gpgme
	 * fork-and-close-all-fds dance fires close() up to RLIMIT_NOFILE
	 * (~1M on Android) per child — every one going through SIGSYS adds
	 * up to minutes of wall time. We only need to TRAP close(fd) when
	 * fd is in our reserved range; the kernel's own close handles
	 * everything else (returning -EBADF for the unopened majority).
	 * Filter inline: load args[0] low, branch on >= base. */
	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_LD | BPF_W | BPF_ABS, 0);

	for (size_t t = 0; t < n_traps; t++) {
		if (trap_nrs[t] == TAWC_SYS_close) {
			/* close-loop fast path. Pacman/gpgme's
			 * `for (fd=3; fd<RLIMIT_NOFILE; fd++) close(fd)` dance
			 * iterates ~1M close()s per child — and on Android the
			 * iteration HITS each of our reserved fds (1000..1009) by
			 * fd value, not by openness. Trapping each via SIGSYS adds
			 * ~10 µs apiece — ~20 s wall time per child, and with
			 * gpgme spawning a child per package signature, you get
			 * pacman spinning at 100% CPU for tens of minutes.
			 *
			 * Resolution: ALLOW close() inline for fds outside our
			 * reserved range; only TRAP into the handler when the fd
			 * is one of ours, where handle_close decides what to do
			 * (see comment below the BPF block). The common case
			 * (~99.999% of close()s in the closefrom loop) bypasses
			 * SIGSYS entirely.
			 *
			 * Generated BPF (per close):
			 *   if (nr != close) skip (4 + n_res) → next trap entry
			 *   load args[0].lo
			 *   for each reserved fd:
			 *     if eq → jump forward to the trailing RET TRAP
			 *   RET ALLOW
			 *   RET TRAP    (landing slot for reserved-fd matches)
			 *   reload nr   (for the following trap's JEQ)
			 *
			 * Each per-fd JEQ has jt set to (n_res - rj) so a match
			 * skips the remaining JEQs + the RET ALLOW and lands on
			 * the RET TRAP. */
			size_t n_res = tawcroot_n_reserved_fds;
			if (n_res > TAWCROOT_MAX_RESERVED_FDS)
				n_res = TAWCROOT_MAX_RESERVED_FDS;
			/* After JEQ-close, on no-match we skip past:
			 *   1 (LD args) + n_res (JEQs) + 1 (RET ALLOW)
			 *   + 1 (RET ERRNO) + 1 (LD nr) = n_res + 4
			 * landing at the next trap entry. */
			uint8_t jf_skip = (uint8_t)(4 + n_res);
			prog[i++] = (struct sock_filter)TAWC_BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
				(uint32_t)trap_nrs[t], 0, jf_skip);
			prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_LD | BPF_W | BPF_ABS, 16);
			for (size_t rj = 0; rj < n_res; rj++) {
				/* Match → jump to RET ERRNO at index
				 * (current + remaining_JEQs + RET_ALLOW + 1). */
				uint8_t jt = (uint8_t)(n_res - rj); /* lands one past RET ALLOW */
				prog[i++] = (struct sock_filter)TAWC_BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
					(uint32_t)tawcroot_reserved_fds[rj], jt, 0);
			}
			prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
			/* Reserved fd matched → TRAP into our handler. The handler
			 * (handle_close) lets the kernel close actually proceed and
			 * returns success — earlier revisions returned -EBADF here
			 * to "protect" the slot, but glibc's closefrom() (which
			 * enumerates /proc/self/fd via getdents64 in a loop until
			 * the list is empty) then spun forever: the fd stayed in
			 * /proc/self/fd because we never let close() reach the
			 * kernel. Letting close() succeed lets the loop terminate;
			 * a fork-child losing rootfs_fd is fine because the child
			 * is about to execve, and our exec-handler re-establishes
			 * the fds in --exec-child. The parent's reserved fds are
			 * unaffected (separate fd table after fork). */
			prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
			/* Reload nr for the next trap_nr comparison. */
			prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_LD | BPF_W | BPF_ABS, 0);
			continue;
		}
		/* If matched, jt=0 → fall through to TRAP; else jt skips TRAP. */
		prog[i++] = (struct sock_filter)TAWC_BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
			(uint32_t)trap_nrs[t], 0, 1);
		prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
	}

	/* Default ALLOW. */
	prog[i++] = (struct sock_filter)TAWC_BPF_S(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

	struct sock_fprog fprog = {
		.len = (unsigned short)i,
		.filter = prog,
	};
#ifdef TAWCROOT_TRACE
	{
		/* Dump the assembled BPF program to fd 2 in one write per line
		 * so it doesn't interleave with stdout-bound trace output. */
		for (size_t k = 0; k < i; k++) {
			char line[80];
			size_t li = 0;
			const char *p = "[F] ";
			while (*p) line[li++] = *p++;
			/* idx */
			char tmp[24]; int tn;
			unsigned long v;
			tn = 0; v = k; if (v == 0) tmp[tn++] = '0';
			while (v) { tmp[tn++] = (char)('0' + (v % 10)); v /= 10; }
			while (tn--) line[li++] = tmp[tn];
			line[li++] = ' ';
			p = "code=";
			while (*p) line[li++] = *p++;
			tn = 0; v = prog[k].code; if (v == 0) tmp[tn++] = '0';
			while (v) { tmp[tn++] = (char)('0' + (v % 10)); v /= 10; }
			while (tn--) line[li++] = tmp[tn];
			line[li++] = ' ';
			p = "jt=";
			while (*p) line[li++] = *p++;
			tn = 0; v = prog[k].jt; if (v == 0) tmp[tn++] = '0';
			while (v) { tmp[tn++] = (char)('0' + (v % 10)); v /= 10; }
			while (tn--) line[li++] = tmp[tn];
			line[li++] = ' ';
			p = "jf=";
			while (*p) line[li++] = *p++;
			tn = 0; v = prog[k].jf; if (v == 0) tmp[tn++] = '0';
			while (v) { tmp[tn++] = (char)('0' + (v % 10)); v /= 10; }
			while (tn--) line[li++] = tmp[tn];
			line[li++] = ' ';
			p = "k=";
			while (*p) line[li++] = *p++;
			tn = 0; v = prog[k].k; if (v == 0) tmp[tn++] = '0';
			while (v) { tmp[tn++] = (char)('0' + (v % 10)); v /= 10; }
			while (tn--) line[li++] = tmp[tn];
			line[li++] = '\n';
			TAWC_RAW(TAWC_SYS_write, 2, (long)line, (long)li, 0, 0, 0);
		}
	}
#endif
	return tawc_seccomp(SECCOMP_SET_MODE_FILTER, 0, &fprog);
}

