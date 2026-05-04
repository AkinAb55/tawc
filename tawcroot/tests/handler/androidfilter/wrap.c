/* Synthesized Android-`untrusted_app`-shape seccomp prefilter wrapper.
 *
 * Reproduces the parts of Android's stacked seccomp filter that have caused
 * production tawcroot bugs (see notes/tawcroot.md "Bugs found and fixed
 * during phase-5b" and similar). On plain host Linux this lets us run the
 * existing handler-layer test surface against the same filter shape we'd
 * see under run-as on a real device, without needing adb / a phone /
 * emulator.
 *
 * Usage:
 *
 *   wrap [--include-legacy-x86_64] -- <child> [args...]
 *
 * Installs PR_SET_NO_NEW_PRIVS + a seccomp BPF filter, then execve()s the
 * child with the original args. The filter inherits across exec, so the
 * child runs under it.
 *
 * Filter shape (default = ALLOW, RET_TRAP on the listed syscall numbers):
 *
 *   - openat2     (437)  // RET_TRAPed on Android 16; tawcroot probe must
 *                        // route to handler -> -ENOSYS fallback.
 *   - faccessat2  (439)  // Likewise; handlers must NOT issue NR 439
 *                        // recursively (the bug fixed in handle_access).
 *   - clone3      (435)  // tawcroot must explicitly trap and -ENOSYS so
 *                        // glibc 2.34+'s probe falls back to clone(2).
 *   - With --include-legacy-x86_64 (only meaningful on x86_64): also TRAP
 *     access (21), open (2), chmod (90), chown (92), mkdir (83),
 *     rmdir (84), unlink (87), symlink (88), link (86), rename (82),
 *     readlink (89), stat (4), lstat (6). These are the lp64 legacy
 *     syscalls Android RET_TRAPs because bionic's allowlist only grants
 *     them to lp32 — see notes/proot.md "Why upstream proot doesn't work
 *     on Android x86_64". tawcroot routes them all through *at variants
 *     in the handler. (No-op on aarch64; those NRs aren't allocated.)
 *
 * Deliberately NOT trapped:
 *
 *   - execve / execveat: the wrapper itself uses execve to launch the
 *     child. Trapping would SIGSYS the wrapper (no handler installed) and
 *     kill it before exec. tawcroot's handler internally uses execveat
 *     to re-exec self for the --exec-child handoff; trapping that would
 *     re-enter the SIGSYS handler, which the design explicitly avoids
 *     (see "faccessat2 recursive SIGSYS" in notes/tawcroot.md).
 *   - Any syscall the wrapper itself uses during init (read, write,
 *     mmap, prctl, seccomp). Same reason — kills the wrapper.
 *
 * The wrapper does NOT install a SIGSYS handler. SIGSYS for any trapped
 * syscall delivers default-action process termination — exactly Android's
 * behavior pre-tawcroot. Once the child execs and installs its own
 * SIGSYS handler, the inherited filter will route trapped syscalls
 * through that handler instead of killing.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

/* Match notes/tawcroot.md naming. */
#if defined(__aarch64__)
# define WRAP_AUDIT_ARCH      AUDIT_ARCH_AARCH64
# define WRAP_NR_openat2      437
# define WRAP_NR_faccessat2   439
# define WRAP_NR_clone3       435
#elif defined(__x86_64__)
# define WRAP_AUDIT_ARCH      AUDIT_ARCH_X86_64
# define WRAP_NR_openat2      437
# define WRAP_NR_faccessat2   439
# define WRAP_NR_clone3       435
/* Legacy lp64-x86_64 RET_TRAP set (Android allows these only for lp32). */
# define WRAP_NR_access       21
# define WRAP_NR_open         2
# define WRAP_NR_chmod        90
# define WRAP_NR_chown        92
# define WRAP_NR_mkdir        83
# define WRAP_NR_rmdir        84
# define WRAP_NR_unlink       87
# define WRAP_NR_symlink      88
# define WRAP_NR_link         86
# define WRAP_NR_rename       82
# define WRAP_NR_readlink     89
# define WRAP_NR_stat         4
# define WRAP_NR_lstat        6
#else
# error "unsupported arch"
#endif

#define BPF_S(_code, _k)            ((struct sock_filter){ (_code), 0, 0, (_k) })
#define BPF_J(_code, _k, _jt, _jf)  ((struct sock_filter){ (_code), (_jt), (_jf), (_k) })

static long sys_seccomp(unsigned int op, unsigned int flags, void *args)
{
	return syscall(SYS_seccomp, op, flags, args);
}

static bool install_filter(bool include_legacy_x86_64)
{
	/* Cap mirrors the production trap_nrs[256] in main.c. The current
	 * trap set fits comfortably in 64 slots, but the array+guard pair
	 * is the cheap defense against silent truncation as the set
	 * grows — see the same pattern in main.c and phase1.c. */
	int trap_nrs[64];
	const size_t trap_cap = sizeof trap_nrs / sizeof trap_nrs[0];
	size_t n = 0;

#define WRAP_PUSH(_nr) do {                                              \
		if (n >= trap_cap) {                                     \
			fprintf(stderr,                                  \
				"wrap: trap_nrs[%zu] overflow at %s\n",  \
				trap_cap, #_nr);                         \
			return false;                                    \
		}                                                        \
		trap_nrs[n++] = (_nr);                                   \
	} while (0)

	WRAP_PUSH(WRAP_NR_openat2);
	WRAP_PUSH(WRAP_NR_faccessat2);
	WRAP_PUSH(WRAP_NR_clone3);

#if defined(__x86_64__)
	if (include_legacy_x86_64) {
		WRAP_PUSH(WRAP_NR_access);
		WRAP_PUSH(WRAP_NR_open);
		WRAP_PUSH(WRAP_NR_chmod);
		WRAP_PUSH(WRAP_NR_chown);
		WRAP_PUSH(WRAP_NR_mkdir);
		WRAP_PUSH(WRAP_NR_rmdir);
		WRAP_PUSH(WRAP_NR_unlink);
		WRAP_PUSH(WRAP_NR_symlink);
		WRAP_PUSH(WRAP_NR_link);
		WRAP_PUSH(WRAP_NR_rename);
		WRAP_PUSH(WRAP_NR_readlink);
		WRAP_PUSH(WRAP_NR_stat);
		WRAP_PUSH(WRAP_NR_lstat);
	}
#else
	(void)include_legacy_x86_64;
#endif
#undef WRAP_PUSH

	/* Prologue: arch != ours -> KILL_PROCESS. Then load nr at offset 0
	 * once and run a linear JEQ chain. This mimics Android's bionic
	 * allowlist generator (see SECCOMP_PRIVATE_ALLOWLIST_APP.TXT) but
	 * only for the syscalls we want to model.
	 *
	 *   load arch
	 *   jeq <ours>, +1, 0     ; if matches skip kill
	 *   ret KILL_PROCESS
	 *   load nr
	 *   for each trap_nr:
	 *     jeq nr, 0, +1
	 *     ret TRAP
	 *   ret ALLOW
	 *
	 * Kernel BPF instruction limit is 4096; we use ~5 + 2*N. With
	 * trap_cap=64 the worst case is ~133 instructions; size prog[]
	 * comfortably above that.
	 */
	struct sock_filter prog[256];
	size_t i = 0;
	prog[i++] = BPF_S(BPF_LD | BPF_W | BPF_ABS, 4);
	prog[i++] = BPF_J(BPF_JMP | BPF_JEQ | BPF_K, WRAP_AUDIT_ARCH, 1, 0);
	prog[i++] = BPF_S(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
	prog[i++] = BPF_S(BPF_LD | BPF_W | BPF_ABS, 0);
	for (size_t t = 0; t < n; t++) {
		prog[i++] = BPF_J(BPF_JMP | BPF_JEQ | BPF_K,
		                  (uint32_t)trap_nrs[t], 0, 1);
		prog[i++] = BPF_S(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
	}
	prog[i++] = BPF_S(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

	struct sock_fprog fprog = {
		.len = (unsigned short)i,
		.filter = prog,
	};
	if (sys_seccomp(SECCOMP_SET_MODE_FILTER, 0, &fprog) != 0) {
		fprintf(stderr, "wrap: seccomp(SET_MODE_FILTER) failed: %s\n",
		        strerror(errno));
		return false;
	}
	return true;
}

int main(int argc, char **argv)
{
	bool include_legacy = false;
	int i = 1;
	for (; i < argc; i++) {
		if (strcmp(argv[i], "--include-legacy-x86_64") == 0) {
			include_legacy = true;
		} else if (strcmp(argv[i], "--") == 0) {
			i++;
			break;
		} else {
			break;
		}
	}
	if (i >= argc) {
		fprintf(stderr,
		        "usage: wrap [--include-legacy-x86_64] [--] <child> [args...]\n");
		return 2;
	}

	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
		fprintf(stderr, "wrap: PR_SET_NO_NEW_PRIVS failed: %s\n",
		        strerror(errno));
		return 2;
	}
	if (!install_filter(include_legacy)) return 2;

	execv(argv[i], &argv[i]);
	/* execv only returns on failure. */
	fprintf(stderr, "wrap: execv(%s): %s\n", argv[i], strerror(errno));
	return 2;
}
