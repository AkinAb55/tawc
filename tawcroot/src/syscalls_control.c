/* Runtime-control syscall handlers — guest-side denials and shadow
 * virtualization for operations that would otherwise compromise
 * tawcroot's own invariants.
 *
 * Surface (notes/tawcroot.md §"Guest signal/seccomp control"):
 *   - `seccomp(2)` / `prctl(PR_SET_SECCOMP)`: refuse with -EPERM. A
 *     guest filter on top of ours would RET_KILL or RET_ERRNO before
 *     our trap, or RET_TRAP into a handler the guest owns — all of
 *     which break translation. Programs that probe with EPERM fall
 *     back to a no-filter path; pacman/glibc init don't depend on
 *     stacking.
 *   - `rt_sigaction(SIGSYS, ...)`: virtualize. The guest's intended
 *     disposition lives in a shadow buffer; reads/writes of SIGSYS
 *     hit the shadow and never the kernel. The real kernel disposition
 *     stays our SIGSYS handler.
 *   - `rt_sigprocmask`: pass through, but transparently strip SIGSYS
 *     from any new mask the guest installs and OR-in the shadow bit
 *     when reporting the previous mask. Guest reads back what it set;
 *     the kernel never actually blocks SIGSYS, so traps continue
 *     reaching our handler.
 *   - Other signals are unaffected.
 */

#include <stddef.h>
#include <stdint.h>
#include <ucontext.h>

#include "dispatch.h"
#include "raw_sys.h"
#include "sysnr.h"
#include "usercopy.h"

#define EPERM_NEG  (-1)
#define EINVAL_NEG (-22)
#define EFAULT_NEG (-14)

#ifndef PR_GET_SECCOMP
# define PR_GET_SECCOMP 21
#endif
#ifndef PR_SET_SECCOMP
# define PR_SET_SECCOMP 22
#endif

#ifndef SIGSYS
# define SIGSYS 31
#endif
/* sigset bit position is (signo - 1). */
#define SIGSYS_BIT (1ULL << (SIGSYS - 1))

#ifndef SIG_BLOCK
# define SIG_BLOCK   0
# define SIG_UNBLOCK 1
# define SIG_SETMASK 2
#endif

/* Shadow state for the guest's SIGSYS view. Single thread of control
 * for now (phase-1 smoke is single-threaded; multi-thread + per-thread
 * mask is a phase-2 follow-up).
 *
 * Size matches the kernel's `struct sigaction` for sigsetsize=8 on each
 * arch (review finding B2). Earlier revs oversized to 64 bytes "to fit
 * either arch", which over-read 32 bytes past the guest's actual struct
 * via tawc_copy_from_guest and over-wrote 32 bytes past oldact via
 * tawc_copy_to_guest. Both directions could EFAULT or trash adjacent
 * guest memory.
 *
 *   x86_64: handler ptr (8) + flags (8) + sa_restorer (8) + mask (8) = 32
 *   aarch64: handler ptr (8) + flags (8) + mask (8)              = 24
 *
 * Round-trip exactly that many bytes through the shadow. */
#if defined(__x86_64__)
# define TAWC_KERN_SIGACTION_SIZE 32
#elif defined(__aarch64__)
# define TAWC_KERN_SIGACTION_SIZE 24
#else
# error "unsupported arch"
#endif

static unsigned char g_guest_sigsys_action[TAWC_KERN_SIGACTION_SIZE];
static int           g_guest_sigsys_action_set = 0;
static int           g_guest_sigsys_blocked    = 0;

/* Lie about successful filter install. Returning -EPERM here works in
 * principle but breaks Firefox: Mozilla's content sandbox calls
 * `seccomp(SECCOMP_SET_MODE_FILTER, ..., &filter)` to install a per-
 * subprocess BPF filter, sees -EPERM, and falls into a "sandbox setup
 * failed, abort the child" code path that tears down a half-initialised
 * libhybris loader and aborts in `unregister_tls_module` (the Q linker
 * fork's `mod.soinfo_ptr == si` CHECK fails on a soinfo whose TLS slot
 * was already cleaned up).
 *
 * Pretending success is safe under tawcroot:
 *   - We can't install the guest's BPF filter (it'd stack on top of
 *     ours and could KILL_PROCESS our own raw_syscall stub).
 *   - Without the filter installed, the guest's SIGSYS handler never
 *     fires from the guest's filter — but it doesn't need to. Our
 *     filter still routes every trapped syscall through tawcroot's
 *     own handler, which is what actually enforces the rootfs view.
 *   - The whole tawcroot process tree already runs as the app uid in
 *     an Android per-app sandbox, plus our seccomp filter, plus the
 *     rootfs translation. Mozilla's content-process filter is a
 *     defense-in-depth layer that is fundamentally redundant under
 *     our setup.
 *
 * SECCOMP_GET_ACTION_AVAIL and other read-only ops pass through to
 * the kernel verbatim (they don't change state). Only SET_MODE_*
 * is faked. */
static long handle_seccomp(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	unsigned int op = (unsigned int)args->a;
	/* SECCOMP_SET_MODE_STRICT = 0, SECCOMP_SET_MODE_FILTER = 1. */
	if (op == 0 || op == 1) return 0;
	/* SECCOMP_GET_ACTION_AVAIL = 2, SECCOMP_GET_NOTIF_SIZES = 3, etc.
	 * Read-only — pass through. */
	return TAWC_RAW(TAWC_SYS_seccomp, args->a, args->b, args->c,
			args->d, args->e, 0);
}

/* Defense-in-depth denials. Trapped so the guest can't mutate kernel
 * state our path-translation layer assumes is fixed: chroot/pivot_root
 * would desync our root-relative bookkeeping (every translated absolute
 * path then resolves against the wrong kernel root); mount/umount2
 * would tear down our setup binds (/dev/shm, /proc, libhybris stage);
 * unshare/setns would hand the guest a namespace where our fd-relative
 * /proc walks no longer name what we think they name. None of today's
 * targets need them, and emulating any of them honestly is a much
 * bigger project (re-translating every cached path, tracking nested
 * roots, etc.). Lying with -EPERM is the same posture proot takes.
 * See issues/tawcroot-phase3-syscall-gaps.md §1. */
static long fake_eperm(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)args;
	(void)uc;
	return EPERM_NEG;
}

/* io_uring_setup: deny with -ENOSYS so guest libraries fall back to
 * syscall-based I/O which we can translate. The plan
 * (notes/tawcroot.md "Open questions" #1) classifies a passed-through
 * io_uring as a *correctness* hazard, not just a missing feature: the
 * kernel reads SQEs from app-shared memory, sees host-relative paths,
 * and silently opens host files — bypassing every translation rule.
 * Programs that probe with -ENOSYS fall back to non-uring paths
 * cleanly. (Review finding D4.) */
static long handle_io_uring_setup(const tawcroot_syscall_args *args,
				  ucontext_t *uc)
{
	(void)args;
	(void)uc;
	return -38;  /* ENOSYS */
}

/* clone3: deny with -ENOSYS so glibc's __clone falls back to the
 * older clone(2) syscall (NR 220 aarch64 / NR 56 x86_64). Android's
 * untrusted_app filter on Android 14 (and possibly later) RET_KILLs
 * clone3 — at the highest action precedence, that overrides any of
 * our filter actions. We can't intercept the kernel's KILL, but we
 * can make the GUEST not issue clone3 in the first place: trapping
 * via our own filter and returning -ENOSYS happens BEFORE the kernel
 * sees the syscall enter the dispatcher path that runs Android's
 * filter — actually no, all filters are evaluated at syscall entry
 * and the most restrictive action wins. So this only works if Android
 * RET_TRAPs (not RET_KILLs) clone3. Empirically on Android 14 the
 * trap fires via our handler — we get [sigsys] nr=435 — meaning
 * Android's policy is RET_TRAP-or-ALLOW, not RET_KILL. Returning
 * -ENOSYS from our handler causes glibc to set its "clone3 missing"
 * flag and use clone() going forward. */
static long handle_clone3(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)args;
	(void)uc;
	return -38;  /* ENOSYS */
}


static long handle_prctl(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int op = (int)args->a;
	/* PR_SET_SECCOMP — same lie-about-success rationale as
	 * handle_seccomp above (Firefox's sandbox is incompatible with
	 * tawcroot regardless; -EPERM trips a teardown path that aborts
	 * inside libhybris). */
	if (op == PR_SET_SECCOMP) return 0;
	return TAWC_RAW(TAWC_SYS_prctl, args->a, args->b, args->c,
			args->d, args->e, 0);
}

static long handle_rt_sigaction(const tawcroot_syscall_args *args,
				ucontext_t *uc)
{
	(void)uc;
	int    sig         = (int)args->a;
	const void *act    = (const void *)(uintptr_t)args->b;
	void  *oldact      = (void *)(uintptr_t)args->c;
	size_t sigsetsize  = (size_t)args->d;

	if (sig != SIGSYS) {
		return TAWC_RAW(TAWC_SYS_rt_sigaction, args->a, args->b,
				args->c, args->d, 0, 0);
	}

	if (sigsetsize != 8) return EINVAL_NEG;

	/* Read the guest's new action into a stack-local buffer FIRST so
	 * we know whether the call would have succeeded before exposing
	 * stale shadow contents to a faulting oldact write. */
	unsigned char incoming[sizeof g_guest_sigsys_action];
	int have_incoming = 0;
	if (act) {
		long e = tawc_copy_from_guest(incoming, sizeof incoming, act);
		if (e < 0) return EFAULT_NEG;
		have_incoming = 1;
	}

	if (oldact) {
		static const unsigned char zero[sizeof g_guest_sigsys_action];
		const unsigned char *src = g_guest_sigsys_action_set
			? g_guest_sigsys_action : zero;
		long e = tawc_copy_to_guest(oldact, src,
					    sizeof g_guest_sigsys_action);
		if (e < 0) return EFAULT_NEG;
	}

	if (have_incoming) {
		for (size_t i = 0; i < sizeof g_guest_sigsys_action; i++)
			g_guest_sigsys_action[i] = incoming[i];
		g_guest_sigsys_action_set = 1;
	}
	return 0;
}

/* Locate the 8-byte kernel sigset embedded in ucontext_t->uc_sigmask.
 * Bionic's <ucontext.h> exposes it as `sigset64_t` whose first member
 * is a `__bionic_sigset_t __bits` of 8 bytes; we treat it as a u64.
 * Modifying *here* is what makes the change persist across sigreturn —
 * a kernel-level rt_sigprocmask issued during a SIGSYS handler is
 * *undone* by sigreturn restoring task->blocked from this field. */
static uint64_t *uc_sigmask_word(ucontext_t *uc)
{
	return (uint64_t *)&uc->uc_sigmask;
}

static long handle_rt_sigprocmask(const tawcroot_syscall_args *args,
				  ucontext_t *uc)
{
	int    how         = (int)args->a;
	const void *guest_set    = (const void *)(uintptr_t)args->b;
	void  *guest_oldset      = (void *)(uintptr_t)args->c;
	size_t sigsetsize  = (size_t)args->d;

	if (sigsetsize != 8) return EINVAL_NEG;

	uint64_t set_val = 0;
	int have_set = 0;
	if (guest_set) {
		long e = tawc_copy_from_guest(&set_val, 8, guest_set);
		if (e < 0) return EFAULT_NEG;
		have_set = 1;
	}

	uint64_t *kmask = uc_sigmask_word(uc);
	uint64_t  cur_kmask = *kmask;
	int prev_blocked = g_guest_sigsys_blocked;

	if (have_set) {
		int sigsys_in_set = (set_val & SIGSYS_BIT) != 0;
		uint64_t kernel_set = set_val & ~SIGSYS_BIT;

		switch (how) {
		case SIG_BLOCK:
			*kmask = cur_kmask | kernel_set;
			if (sigsys_in_set) g_guest_sigsys_blocked = 1;
			break;
		case SIG_UNBLOCK:
			*kmask = cur_kmask & ~kernel_set;
			if (sigsys_in_set) g_guest_sigsys_blocked = 0;
			break;
		case SIG_SETMASK:
			*kmask = kernel_set;
			g_guest_sigsys_blocked = sigsys_in_set;
			break;
		default:
			return EINVAL_NEG;
		}
	}

	if (guest_oldset) {
		uint64_t old = cur_kmask;
		if (prev_blocked) old |= SIGSYS_BIT;
		long e = tawc_copy_to_guest(guest_oldset, &old, 8);
		if (e < 0) {
			/* Roll back the mask if we'd already updated it. */
			if (have_set) {
				*kmask = cur_kmask;
				g_guest_sigsys_blocked = prev_blocked;
			}
			return EFAULT_NEG;
		}
	}
	return 0;
}

void tawcroot_control_register(void)
{
	tawcroot_dispatch_install(TAWC_SYS_seccomp,         handle_seccomp);
	tawcroot_dispatch_install(TAWC_SYS_prctl,           handle_prctl);
	tawcroot_dispatch_install(TAWC_SYS_rt_sigaction,    handle_rt_sigaction);
	tawcroot_dispatch_install(TAWC_SYS_rt_sigprocmask,  handle_rt_sigprocmask);
	tawcroot_dispatch_install(TAWC_SYS_io_uring_setup,  handle_io_uring_setup);
	tawcroot_dispatch_install(TAWC_SYS_clone3,          handle_clone3);
	tawcroot_dispatch_install(TAWC_SYS_chroot,          fake_eperm);
	tawcroot_dispatch_install(TAWC_SYS_pivot_root,      fake_eperm);
	tawcroot_dispatch_install(TAWC_SYS_mount,           fake_eperm);
	tawcroot_dispatch_install(TAWC_SYS_umount2,         fake_eperm);
	tawcroot_dispatch_install(TAWC_SYS_unshare,         fake_eperm);
	tawcroot_dispatch_install(TAWC_SYS_setns,           fake_eperm);
}
