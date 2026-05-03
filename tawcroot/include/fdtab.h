/* Internal-fd reservation.
 *
 * tawcroot keeps a handful of long-lived fds open across guest
 * execution: the rootfs O_PATH fd, one O_PATH fd per `-b src:dst`
 * bind, and (eventually) the exec-state fd handed across re-exec.
 * If the guest can `close()` those, our handler's `*at` calls start
 * failing, *or worse* — the kernel could later assign that slot to a
 * guest-opened file, after which our path translator would route
 * guest opens through whatever inode the guest chose.
 *
 * Mitigation: dup every internal fd into a high-numbered "reserved"
 * range at init (`TAWCROOT_RESERVED_FD_BASE` and above) and trap the
 * fd-shape syscalls (`close*`, `dup*`, `fcntl(F_DUPFD*)`). The
 * trapping handlers make every reserved fd return `-EBADF` from the
 * guest's perspective, exactly as if the fd were never opened.
 *
 * The base is picked high enough that ordinary guests (which receive
 * sequential low fds from the kernel) never reach it before tawcroot
 * forks/loads them; if a guest does manage to push past, fd creation
 * will start failing with `-EMFILE` from the kernel — same outcome
 * as running close to the rlimit, no escape.
 */

#pragma once

#include <stddef.h>

#define TAWCROOT_RESERVED_FD_BASE 1000

/* Cap on simultaneously reserved fds: rootfs (1) + binds (TAWCROOT_MAX_BINDS).
 * Sized generously above the 8 we ship today. Only matters as the BPF
 * filter generator's array bound; runtime growth is the bind table's. */
#define TAWCROOT_MAX_RESERVED_FDS 64

/* The set of fds tawcroot has reserved (rootfs + bind sources, plus any
 * future long-lived capability fds). Append-only after init; the SIGSYS
 * handler reads it without locking. Defined in syscalls_fd.c. */
extern int    tawcroot_reserved_fds[TAWCROOT_MAX_RESERVED_FDS];
extern size_t tawcroot_n_reserved_fds;

/* True iff `fd` is one of the specific reserved slots. Earlier revisions
 * checked the entire `fd >= TAWCROOT_RESERVED_FD_BASE` half-space, but
 * that turned out to be a major performance pothole: pacman/gpgme's
 * fork-and-close-all-fds dance hammers `close(fd)` for fd in
 * [3, RLIMIT_NOFILE) — typically ~1M iterations on Android — and we
 * trapped each one in [1000, 1M). The BPF filter mirrors this exact
 * predicate so most close-loop iterations skip the handler entirely. */
static inline int tawcroot_fd_is_reserved(int fd)
{
	if (fd < TAWCROOT_RESERVED_FD_BASE) return 0;
	for (size_t i = 0; i < tawcroot_n_reserved_fds; i++) {
		if (tawcroot_reserved_fds[i] == fd) return 1;
	}
	return 0;
}

/* Move `fd` to the next free slot at or above TAWCROOT_RESERVED_FD_BASE
 * via `fcntl(F_DUPFD_CLOEXEC, base)` and close the original. Returns
 * the new fd on success, -errno on failure. Call BEFORE the seccomp
 * filter goes up (close/fcntl would otherwise trap into a not-yet-
 * registered handler). */
long tawcroot_fd_reserve(int fd);

/* Register the close/dup/fcntl handler set in the dispatch table.
 * Called from tawcroot_dispatch_init. */
void tawcroot_fd_register(void);
