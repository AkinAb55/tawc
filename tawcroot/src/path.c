/* Path translation — phase-1 MVP.
 *
 * Today's policy:
 *   - Absolute guest path:
 *       Tokenize on `/`, fold `.` and `..` (clamp at root if they would
 *       escape — proot-compatible behavior described in
 *       notes/tawcroot.md "..&symlink escapes are blocked"). The result
 *       is a rootfs-relative suffix used with the rootfs O_PATH fd via
 *       *at syscalls.
 *   - Relative guest path:
 *       Resolve against the kernel cwd via raw `getcwd`. Strip the host
 *       rootfs prefix (so a kernel cwd of `<rootfs>/foo` becomes guest
 *       `/foo`). Then concatenate the relative remainder and re-run the
 *       absolute translator on the joined path.
 *       If the kernel cwd is outside the rootfs, return -ENOENT (don't
 *       leak host paths through guest-visible errors).
 *   - Empty result ("/") → suffix = "", caller passes AT_EMPTY_PATH.
 *
 * No allocations, no libc, async-signal-safe. Path buffers live on the
 * caller's stack (4 KB PATH_MAX is the hard cap). The `..`-fold uses an
 * in-place stack of byte offsets into the suffix buffer — see
 * `fold_path` below. We do **not** resolve symlinks here yet; that's the
 * next commit, parameterized per syscall semantics (follow / no-follow /
 * parent-for-create / parent-for-remove — see notes/tawcroot.md
 * §"Translation rules").
 */

#include <stddef.h>
#include <stdint.h>

#include "fdtab.h"
#include "path.h"
#include "path_oracle.h"
#include "path_resolve.h"
#include "raw_sys.h"

#define TAWC_PATH_MAX 4096

int    tawcroot_rootfs_fd               = -1;
char   tawcroot_rootfs_host_path[4096]  = {0};
size_t tawcroot_rootfs_host_path_len    = 0;

char   tawcroot_guest_exe_path[4096]    = {0};
size_t tawcroot_guest_exe_path_len      = 0;

int    tawcroot_openat2_works           = 0;

void tawcroot_set_guest_exe_path(const char *path)
{
	if (!path) {
		tawcroot_guest_exe_path[0] = 0;
		tawcroot_guest_exe_path_len = 0;
		return;
	}
	size_t i = 0;
	while (path[i] && i + 1 < sizeof tawcroot_guest_exe_path) {
		tawcroot_guest_exe_path[i] = path[i];
		i++;
	}
	tawcroot_guest_exe_path[i]   = 0;
	tawcroot_guest_exe_path_len  = i;
}

struct tawcroot_bind tawcroot_binds[TAWCROOT_MAX_BINDS];
size_t               tawcroot_n_binds = 0;

void tawcroot_path_probe_openat2(void)
{
	struct tawc_open_how how;
	how.flags   = 0x200000 /*O_PATH*/ | 0x80000 /*O_CLOEXEC*/;
	how.mode    = 0;
	how.resolve = TAWC_RESOLVE_IN_ROOT;
	long fd = tawc_openat2(-100 /*AT_FDCWD*/, "/", &how, sizeof how);
	if (fd >= 0) {
		tawcroot_openat2_works = 1;
		(void)TAWC_RAW(TAWC_SYS_close, fd, 0, 0, 0, 0, 0);
	} else {
		tawcroot_openat2_works = 0;
	}
}

/* Forward declarations of internal helpers used both above and below
 * the memoization block. The bodies live further down. */
static size_t pstrlen(const char *s);
static int    pmemeq(const char *a, const char *b, size_t n);

/* Well-known-symlink memoization. After fold_absolute, we check each
 * memoized src against the path's first segment (component boundary
 * required). If matched, we replace the segment with the target form
 * and re-run the fold so any `..`/`.` in the target collapses. Targets
 * may be relative ("usr/lib") or absolute ("/usr/lib") — the absolute
 * case is handled by re-fold which strips a leading `/`.
 *
 * The hit set is the typical glibc-rootfs symlink layout. Bumps go
 * through this table, not run-time discovery — finder cost is paid
 * once at init. */
#define MEMO_MAX 16

struct symlink_memo {
	char   src[32];
	size_t src_len;
	char   target[256];
	size_t target_len;
	int    target_absolute;
};

static struct symlink_memo g_memo[MEMO_MAX];
static size_t              g_n_memo = 0;

static void memo_one(const char *prefix)
{
	if (g_n_memo >= MEMO_MAX) return;
	size_t plen = pstrlen(prefix);
	if (plen + 1 > sizeof g_memo[0].src) return;

	char buf[256];
	long n = TAWC_RAW(TAWC_SYS_readlinkat, tawcroot_rootfs_fd,
			  (long)prefix, (long)buf, (long)sizeof buf,
			  0, 0);
	if (n <= 0) return;
	if ((size_t)n == sizeof buf) return;  /* truncated — skip memo */
	buf[n] = 0;

	struct symlink_memo *m = &g_memo[g_n_memo++];
	for (size_t i = 0; i < plen; i++) m->src[i] = prefix[i];
	m->src[plen] = 0;
	m->src_len = plen;

	int abs = (buf[0] == '/');
	const char *t = abs ? buf + 1 : buf;
	while (*t == '/') t++;
	size_t tlen = pstrlen(t);
	if (tlen + 1 > sizeof m->target) {
		g_n_memo--;
		return;
	}
	for (size_t i = 0; i < tlen; i++) m->target[i] = t[i];
	m->target[tlen] = 0;
	m->target_len = tlen;
	m->target_absolute = abs;
}

void tawcroot_path_memoize_well_known(void)
{
	g_n_memo = 0;
	memo_one("lib");
	memo_one("lib64");
	memo_one("usr/lib64");
	memo_one("bin");
	memo_one("sbin");
	memo_one("usr/sbin");
	memo_one("var/run");
}

/* Apply memoization to a folded suffix in place. Returns 1 if the
 * suffix changed (caller may want to re-fold), 0 otherwise.
 *
 * Mode-aware: when the memoized symlink IS the final component of the
 * input (i.e. suf == src exactly, no trailing path), we only rewrite
 * for FOLLOW mode. Under NOFOLLOW / PARENT_CREATE / PARENT_REMOVE the
 * symlink itself is the operation target (lstat'ing it, readlink'ing
 * it, removing it, etc.) and rewriting would silently change which
 * inode the syscall hits. A skipped sole-component match `continue`s
 * (rather than returning) so a later memo entry whose `src` happens
 * to also be a path-prefix can still apply — `g_memo` does not
 * guarantee unique srcs across entries. */
static int apply_memo(char *suf, size_t cap, tawcroot_path_mode mode)
{
	size_t suf_len = pstrlen(suf);
	for (size_t i = 0; i < g_n_memo; i++) {
		const struct symlink_memo *m = &g_memo[i];
		if (m->src_len > suf_len) continue;
		if (!pmemeq(suf, m->src, m->src_len)) continue;
		if (suf_len > m->src_len && suf[m->src_len] != '/') continue;
		if (suf_len == m->src_len && mode != TAWCROOT_PATH_FOLLOW) {
			continue;
		}
		size_t after_src_len = suf_len - m->src_len;
		size_t need = m->target_len + after_src_len + 1;
		if (need > cap) return 0;

		/* Shift the trailing remainder (including the terminating
		 * NUL) into its new position. Direction of copy matters:
		 * when target_len > src_len we're growing the prefix and
		 * the destination range overlaps the source from the right
		 * — copy right-to-left. When target_len < src_len we're
		 * shrinking and the destination overlaps from the left —
		 * copy left-to-right. The previous unconditional
		 * right-to-left form would (in the shrink case) read from
		 * positions it had already overwritten, truncating the
		 * path at `target_len` — e.g. memoizing /usr/sbin (-> bin)
		 * over /usr/sbin/bash silently dropped "/bash". */
		if (m->target_len > m->src_len) {
			for (size_t k = 0; k <= after_src_len; k++) {
				suf[m->target_len + after_src_len - k] =
					suf[m->src_len   + after_src_len - k];
			}
		} else if (m->target_len < m->src_len) {
			for (size_t k = 0; k <= after_src_len; k++) {
				suf[m->target_len + k] = suf[m->src_len + k];
			}
		}
		for (size_t k = 0; k < m->target_len; k++) suf[k] = m->target[k];
		return 1;
	}
	return 0;
}

long tawcroot_path_add_bind(const char *src_host, const char *dst_guest)
{
	if (tawcroot_n_binds >= TAWCROOT_MAX_BINDS) return -28;  /* ENOSPC */
	struct tawcroot_bind *b = &tawcroot_binds[tawcroot_n_binds];

	/* Strip leading '/' from dst. Empty after stripping means root,
	 * which we don't currently allow as a bind target — that's just
	 * "the rootfs itself" and is handled by tawcroot_rootfs_fd. */
	const char *d = dst_guest;
	while (*d == '/') d++;
	size_t n = 0; while (d[n]) n++;
	if (n == 0) return -22;  /* EINVAL */
	if (n + 1 > sizeof b->dst) return -36; /* ENAMETOOLONG */

	/* O_PATH | O_DIRECTORY | O_CLOEXEC. The flag values agree across
	 * Linux arches we care about. */
	long fd = tawc_openat(-100 /*AT_FDCWD*/, src_host,
			      0x200000 | 0x10000 | 0x80000, 0);
	if (fd < 0) return fd;

	long resv = tawcroot_fd_reserve((int)fd);
	if (resv < 0) return resv;

	b->src_fd  = (int)resv;
	b->dst_len = n;
	for (size_t i = 0; i < n; i++) b->dst[i] = d[i];
	b->dst[n] = 0;

	/* Stash the host src path for lazy re-open if a guest fork-child's
	 * closefrom() closes our src_fd before reaching exec. */
	{
		size_t k = 0;
		while (src_host[k] && k + 1 < sizeof b->src) {
			b->src[k] = src_host[k];
			k++;
		}
		b->src[k] = 0;
	}
	tawcroot_n_binds++;
	return 0;
}

/* Lazy re-open of a reserved fd after a guest closefrom() killed it.
 * fcntl(F_GETFD) returns the FD_CLOEXEC flag (≥ 0) on a live fd and
 * -EBADF on a dead one — cheaper than a full statfs probe. On dead-fd,
 * open the stashed host path and reserve again (F_DUPFD_CLOEXEC into
 * the reserved range). Used from `*at`-issuing handlers and from
 * exec_handler. Pure-async-signal-safe — raw syscalls only.
 *
 * The new fd may land at a different reserved slot than the original
 * (the kernel's lowest-free search picks whatever is free at or above
 * the base), so we update *fd_p in place. The BPF close-trap predicate
 * still treats the original baked-in slot as reserved, so a guest
 * closing the new fd value would not be intercepted — but that's
 * benign: the next operation lazy-reopens again. */
long tawcroot_reopen_reserved_fd(int *fd_p, const char *host_path)
{
	if (!fd_p || !host_path || !host_path[0]) return -22; /* EINVAL */
	int fd = *fd_p;
	if (fd >= 0) {
		/* F_GETFD = 1. Returns the FD_CLOEXEC bit (0/1) or -EBADF.   */
		long r = TAWC_RAW(TAWC_SYS_fcntl, fd, 1 /*F_GETFD*/, 0, 0, 0, 0);
		if (r >= 0) return fd;
	}
	long nfd = tawc_openat(-100 /*AT_FDCWD*/, host_path,
	                       0x200000 | 0x10000 | 0x80000, 0);
	if (nfd < 0) return nfd;
	/* F_DUPFD_CLOEXEC into the reserved range; close the kernel-allocated
	 * low fd. Bypass tawcroot_fd_reserve() because the BPF reserved-fd
	 * array is install-time-baked and lives untouched by re-opens; the
	 * new slot may not be in it, but that just means future close() on
	 * the new fd won't trap (the kernel handles it; we lazy-reopen
	 * again on the next translation). */
	long resv = tawc_fcntl((int)nfd, 1030 /*F_DUPFD_CLOEXEC*/,
	                       (long)TAWCROOT_RESERVED_FD_BASE);
	tawc_close((int)nfd);
	if (resv < 0) return resv;
	*fd_p = (int)resv;
	return resv;
}

/* ---------------------------------------------------------------- */
/* Small libc-free helpers                                          */

static size_t pstrlen(const char *s)
{
	const char *p = s; while (*p) p++; return (size_t)(p - s);
}

static int pmemeq(const char *a, const char *b, size_t n)
{
	for (size_t i = 0; i < n; i++) if (a[i] != b[i]) return 0;
	return 1;
}

/* `tawcroot_path_fold_absolute` is implemented in `path_fold.c` (pure,
 * no syscalls — also linked into the cleat unit-test orchestrator). */

/* ---------------------------------------------------------------- */
/* Relative-path resolver: convert kernel-cwd into a guest-absolute
 * path, then prepend the relative remainder.
 *
 * If the kernel cwd is outside the configured rootfs host path, return
 * -ENOENT so we don't accidentally leak host paths (or, worse, route
 * a guest open into the host filesystem). */

static long resolve_relative(const char *rel, char *abs, size_t abs_cap)
{
	if (tawcroot_rootfs_host_path_len == 0) return -2;

	char cwd[TAWC_PATH_MAX];
	long r = TAWC_RAW(TAWC_SYS_getcwd, (long)cwd, (long)sizeof cwd,
			  0, 0, 0, 0);
	if (r < 0) return r;
	if ((size_t)r == 0) return -2;

	/* getcwd returns the length INCLUDING the NUL on aarch64+x86_64
	 * Linux. Older kernels may return without NUL — the manpage is
	 * fuzzy; we treat trailing NULs defensively. */
	size_t cwd_len = (size_t)r;
	while (cwd_len > 0 && cwd[cwd_len - 1] == 0) cwd_len--;

	/* Match the rootfs host prefix. The prefix match is by bytes, but a
	 * sibling whose path *starts* with the rootfs name must NOT count as
	 * inside (e.g. rootfs="/tmp/rfs" must not absorb "/tmp/rfs-evil/x"
	 * just because "/tmp/rfs" is a byte-prefix of "/tmp/rfs-evil"). After
	 * matching, require the next byte to be either end-of-string (cwd
	 * IS the rootfs) or a `/` (cwd is a child of the rootfs). */
	if (cwd_len < tawcroot_rootfs_host_path_len ||
	    !pmemeq(cwd, tawcroot_rootfs_host_path,
		    tawcroot_rootfs_host_path_len)) {
		return -2;   /* ENOENT — cwd is outside the rootfs view */
	}
	if (cwd_len > tawcroot_rootfs_host_path_len &&
	    cwd[tawcroot_rootfs_host_path_len] != '/') {
		return -2;   /* sibling whose name shares the rootfs prefix */
	}

	/* Build the joined absolute guest path:
	 *   "/" + (cwd - prefix) + "/" + rel
	 * We drop the prefix (which includes no trailing slash). */
	size_t off = 0;
	if (off >= abs_cap) return -36;
	abs[off++] = '/';

	for (size_t i = tawcroot_rootfs_host_path_len; i < cwd_len; i++) {
		if (off >= abs_cap) return -36;
		abs[off++] = cwd[i];
	}
	if (off >= abs_cap) return -36;
	if (off > 1 && abs[off - 1] != '/') {
		if (off >= abs_cap) return -36;
		abs[off++] = '/';
	}

	size_t rel_len = pstrlen(rel);
	if (off + rel_len + 1 > abs_cap) return -36;
	for (size_t i = 0; i < rel_len; i++) abs[off++] = rel[i];
	abs[off] = 0;
	return 0;
}

/* ---------------------------------------------------------------- */
/* Public API                                                        */

/* Re-route the folded suffix through a bind if it matches the longest
 * `dst` prefix. The folded suffix has NO leading '/'. The bind dst is
 * stored the same way. Match condition:
 *   suffix == dst                    (exact)        OR
 *   suffix starts with dst + '/'    (prefix component boundary)
 * Without the boundary check, "/system_ext" would be matched by a
 * bind with dst "system" (would be misrouted). */
static void route_through_binds(tawcroot_path_result *r, char *suf)
{
	size_t suf_len = pstrlen(suf);
	const struct tawcroot_bind *best = 0;
	for (size_t i = 0; i < tawcroot_n_binds; i++) {
		const struct tawcroot_bind *b = &tawcroot_binds[i];
		if (b->dst_len > suf_len) continue;
		if (!pmemeq(suf, b->dst, b->dst_len)) continue;
		if (suf_len > b->dst_len && suf[b->dst_len] != '/') continue;
		if (!best || b->dst_len > best->dst_len) best = b;
	}
	if (!best) return;

	/* Rewrite: base_fd = best->src_fd, suffix = bytes after best->dst
	 * (skipping any leading '/'). */
	r->base_fd = best->src_fd;
	size_t k = best->dst_len;
	while (suf[k] == '/') k++;
	size_t j = 0;
	while (suf[k]) suf[j++] = suf[k++];
	suf[j] = 0;
}

/* Production oracle: readlink against tawcroot_rootfs_fd via raw
 * syscall. The empty-suffix case (rootfs root itself) is handled
 * locally — readlinkat would return -EINVAL too, but skipping the
 * syscall is cheaper and clearer. */
static long prod_readlink(void *ctx, const char *suffix,
			  char *out, size_t out_cap)
{
	(void)ctx;
	if (!suffix || suffix[0] == 0) return -22;  /* -EINVAL */
	if (tawcroot_rootfs_fd < 0)    return -9;   /* -EBADF */
	long n = TAWC_RAW(TAWC_SYS_readlinkat, tawcroot_rootfs_fd,
			  (long)suffix, (long)out, (long)out_cap, 0, 0);
	return n;
}

static const struct tawcroot_path_oracle prod_oracle = {
	.ctx      = 0,
	.readlink = prod_readlink,
};

/* After fold + memo, run the manual symlink resolver. See the banner
 * at the top of `include/path_resolve.h` for why this exists and how
 * to drop it on a 5.6+-only target.
 *
 * LEGACY-5.4: drop this helper and its two call sites below to delete
 * the kernel-version-portability code path. */
static long apply_resolver(char *out_suffix, size_t out_cap,
			   tawcroot_path_mode mode)
{
	return tawcroot_path_resolve_symlinks(out_suffix, out_cap,
					      mode, &prod_oracle);
}

tawcroot_path_result tawcroot_path_translate(const char *guest_path,
					     char *out_suffix, size_t out_cap,
					     tawcroot_path_mode mode)
{
	tawcroot_path_result r;
	r.err     = 0;

	if (!guest_path || out_cap == 0) {
		r.err = -14;  /* EFAULT */
		return r;
	}
	if (tawcroot_rootfs_fd < 0) {
		r.err = -2;
		return r;
	}

	/* Lazy re-open of reserved fds. A guest fork-child running glibc's
	 * `closefrom()` (gpgme/curl/python pre-exec hygiene) can wipe our
	 * rootfs fd and bind src fds before the execve handler kicks in.
	 * Validate-then-reopen here so any syscall handler downstream has
	 * the right fd. Cheap when the fd is alive (one F_GETFD). */
	long rr = tawcroot_reopen_reserved_fd(&tawcroot_rootfs_fd,
	                                      tawcroot_rootfs_host_path);
	if (rr < 0) { r.err = (int)rr; return r; }
	for (size_t i = 0; i < tawcroot_n_binds; i++) {
		long br = tawcroot_reopen_reserved_fd(&tawcroot_binds[i].src_fd,
		                                      tawcroot_binds[i].src);
		if (br < 0) { r.err = (int)br; return r; }
	}

	r.base_fd = tawcroot_rootfs_fd;

	/* Order of operations after fold (review finding B5+D3):
	 *
	 *   fold → bind → (if no bind) memo → resolver → bind
	 *
	 * Why bind comes first: the user-supplied bind table is the
	 * authoritative replacement for a subtree of the guest view. A
	 * rootfs-side symlink memo (e.g. /lib → usr/lib) must not silently
	 * defeat a bind on /lib. If the early bind matches, we skip memo
	 * and the rootfs-fd-based resolver entirely — both are properties
	 * of the rootfs view, not the bind src.
	 *
	 * Why bind comes again at the end: a memo may surface a bind that
	 * didn't match the literal input. Example: bind on /usr/lib + memo
	 * /lib → usr/lib. Input /lib/x doesn't match the bind directly, but
	 * after memo it becomes usr/lib/x and the bind on /usr/lib then
	 * applies.
	 *
	 * The resolver still runs against the rootfs only when no bind
	 * matched the literal input — it uses prod_oracle which readlinks
	 * via tawcroot_rootfs_fd, so it is only correct for in-rootfs paths. */

	if (guest_path[0] != '/') {
		char joined[TAWC_PATH_MAX];
		long jr = resolve_relative(guest_path, joined, sizeof joined);
		if (jr < 0) { r.err = jr; return r; }
		long fr = tawcroot_path_fold_absolute(joined, out_suffix, out_cap);
		if (fr < 0) { r.err = fr; return r; }
	} else {
		long fr = tawcroot_path_fold_absolute(guest_path, out_suffix, out_cap);
		if (fr < 0) { r.err = fr; return r; }
	}

	/* Bind first. If matched, the bind src takes over -- skip memo
	 * and resolver, both of which are rootfs-view-only. */
	route_through_binds(&r, out_suffix);
	if (r.base_fd != tawcroot_rootfs_fd) return r;

	/* Well-known-symlink rewrite. If the rewrite kicks in, the suffix
	 * may now contain `..`/`.` from the target, so re-fold. Bound
	 * iterations to avoid pathological loops. */
	for (int hop = 0; hop < 8; hop++) {
		if (!apply_memo(out_suffix, out_cap, mode)) break;
		char tmp[TAWC_PATH_MAX];
		size_t i = 0;
		tmp[i++] = '/';
		size_t j = 0;
		while (out_suffix[j] && i + 1 < sizeof tmp) tmp[i++] = out_suffix[j++];
		tmp[i] = 0;
		long rf = tawcroot_path_fold_absolute(tmp, out_suffix, out_cap);
		if (rf < 0) { r.err = rf; return r; }
	}

	/* LEGACY-5.4 */
	long er = apply_resolver(out_suffix, out_cap, mode);
	if (er < 0) { r.err = er; return r; }

	/* Final bind pass — memo may have surfaced a match. */
	route_through_binds(&r, out_suffix);
	return r;
}
