/* Fd-shape syscall handlers — phase 0.5 internal-fd protection.
 *
 * See include/fdtab.h for the rationale. Every handler here is the
 * minimum viable wrapper around the corresponding host syscall; the
 * only intercept is "if a guest argument names a reserved fd, lie".
 *
 * This is NOT a security boundary — see notes/tawcroot.md §"What it
 * explicitly is not". A guest that wants to corrupt our state has
 * other avenues (e.g. mmap over our text). The intercept is for
 * accidental damage from libraries that close-all-fds before exec
 * (libc init), test harnesses that close_range() everything, or
 * pacman-style tools that drop fd tables on fork. Those workloads
 * are common; defending against them is cheap.
 */

#include <stddef.h>
#include <stdint.h>

#include "dispatch.h"
#include "fdtab.h"
#include "raw_sys.h"
#include "sysnr.h"

#define EBADF_NEG  (-9)
#define EINVAL_NEG (-22)

#ifndef F_DUPFD
# define F_DUPFD          0
#endif
#ifndef F_DUPFD_CLOEXEC
# define F_DUPFD_CLOEXEC  1030
#endif

int    tawcroot_reserved_fds[TAWCROOT_MAX_RESERVED_FDS];
size_t tawcroot_n_reserved_fds;

long tawcroot_fd_reserve(int fd)
{
	if (fd < 0) return EBADF_NEG;
	long r = tawc_fcntl(fd, F_DUPFD_CLOEXEC, TAWCROOT_RESERVED_FD_BASE);
	if (r < 0) return r;
	tawc_close(fd);
	if (tawcroot_n_reserved_fds >= TAWCROOT_MAX_RESERVED_FDS) {
		/* No room in the BPF-filter array; the reservation is still
		 * valid (the fd lives at >= base) but close-loop fast-pathing
		 * loses precision — handler-side check still catches it. */
		return r;
	}
	tawcroot_reserved_fds[tawcroot_n_reserved_fds++] = (int)r;
	return r;
}

static long handle_close(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	(void)args;
	/* Reserved fds: lie. The BPF filter only routes close() here when
	 * the argument matches one of our reserved slots, so by definition
	 * we never want the kernel to actually close it — the guest sees
	 * success, our handler keeps the fd alive for downstream path
	 * translation. This makes our reserved fds un-killable from the
	 * guest (close_range, dup2/3, fcntl F_DUPFD all already reject or
	 * trim around the reserved range), which means handler-side state
	 * for path translation can stay immutable post-init. */
	return 0;
}

static long handle_close_range(const tawcroot_syscall_args *args,
			       ucontext_t *uc)
{
	(void)uc;
	unsigned int first = (unsigned int)args->a;
	unsigned int last  = (unsigned int)args->b;
	unsigned int flags = (unsigned int)args->c;

	/* Entire range is above the reserved boundary → success no-op
	 * (the guest sees no fds to close). */
	if (first >= TAWCROOT_RESERVED_FD_BASE) return 0;

	/* Trim the upper end so the kernel never sees our reserved slots. */
	if (last >= TAWCROOT_RESERVED_FD_BASE) {
		last = TAWCROOT_RESERVED_FD_BASE - 1;
	}
	return TAWC_RAW(TAWC_SYS_close_range, first, last, flags, 0, 0, 0);
}

static long handle_dup(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int oldfd = (int)args->a;
	if (tawcroot_fd_is_reserved(oldfd)) return EBADF_NEG;
	return TAWC_RAW(TAWC_SYS_dup, oldfd, 0, 0, 0, 0, 0);
}

#if defined(__x86_64__)
static long handle_dup2(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int oldfd = (int)args->a;
	int newfd = (int)args->b;
	if (tawcroot_fd_is_reserved(oldfd) ||
	    tawcroot_fd_is_reserved(newfd)) return EBADF_NEG;
	return TAWC_RAW(TAWC_SYS_dup2, oldfd, newfd, 0, 0, 0, 0);
}
#endif

static long handle_dup3(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int oldfd = (int)args->a;
	int newfd = (int)args->b;
	int flags = (int)args->c;
	if (tawcroot_fd_is_reserved(oldfd) ||
	    tawcroot_fd_is_reserved(newfd)) return EBADF_NEG;
	return TAWC_RAW(TAWC_SYS_dup3, oldfd, newfd, flags, 0, 0, 0);
}

static long handle_fcntl(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	int fd  = (int)args->a;
	int op  = (int)args->b;
	long a3 = args->c;
	if (tawcroot_fd_is_reserved(fd)) return EBADF_NEG;

	/* F_DUPFD/F_DUPFD_CLOEXEC: cap the requested minimum at base-1 so
	 * the kernel never lands the dup in our reserved range. The guest
	 * either gets a low fd or -EMFILE, both of which match what would
	 * happen on a process running near rlimit. */
	if (op == F_DUPFD || op == F_DUPFD_CLOEXEC) {
		if (a3 >= TAWCROOT_RESERVED_FD_BASE) {
			a3 = TAWCROOT_RESERVED_FD_BASE - 1;
		}
	}
	return TAWC_RAW(TAWC_SYS_fcntl, fd, op, a3, 0, 0, 0);
}

void tawcroot_fd_register(void)
{
	tawcroot_dispatch_install(TAWC_SYS_close,       handle_close);
	tawcroot_dispatch_install(TAWC_SYS_close_range, handle_close_range);
	tawcroot_dispatch_install(TAWC_SYS_dup,         handle_dup);
	tawcroot_dispatch_install(TAWC_SYS_dup3,        handle_dup3);
	tawcroot_dispatch_install(TAWC_SYS_fcntl,       handle_fcntl);
#if defined(__x86_64__)
	tawcroot_dispatch_install(TAWC_SYS_dup2,        handle_dup2);
#endif
}
