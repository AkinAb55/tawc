/* Path translation.
 *
 * Phase-1 surface, intentionally narrow: a single translator that takes
 * a guest-absolute path and yields a (base_fd, suffix) pair we can use
 * with `*at` syscalls. Bind-mount lookup, `..`/symlink clamping, and
 * fd-relative resolution all hang off the same return shape — see
 * notes/tawcroot.md "Path translation".
 *
 * No allocations. The caller passes a stack buffer for the suffix; we
 * write the (NUL-terminated) result into it. That keeps the API
 * handler-safe.
 */

#pragma once

#include <stddef.h>

/* The rootfs fd kept O_PATH | O_DIRECTORY by init. Phase-0.5 will move
 * this into the reserved internal-fd range; for now it's just a global. */
extern int tawcroot_rootfs_fd;

/* Result of translating a guest path:
 *   base_fd  — caller passes this as `dirfd` to *at syscalls
 *   suffix   — caller passes this as `pathname` (relative to base_fd).
 *              Empty string means "the directory referred to by base_fd
 *              itself" (use AT_EMPTY_PATH).
 * The translator writes `suffix` into the caller-provided buffer.
 *
 * Today there are exactly two outcomes:
 *   - Path inside rootfs view  → base_fd = rootfs_fd, suffix = path[1..]
 *   - Path is "/" (the rootfs)  → base_fd = rootfs_fd, suffix = ""
 *   - Otherwise: -ENOENT (we deliberately don't escape outside the view).
 *
 * Bind-mount support and `..`/symlink clamping land in subsequent
 * commits inside this file; the API stays stable.
 */
typedef struct {
	int   base_fd;
	long  err;       /* 0 on success, -errno otherwise */
} tawcroot_path_result;

/* Resolution mode — see notes/tawcroot.md §"Translation rules" for the
 * full semantics. The mode parameterizes how the FINAL component of a
 * path is treated; parent components are always followed. Only the
 * symlink walker (forthcoming) varies behavior across modes; the
 * current string-level fold treats all modes alike except for the
 * well-known-symlink memoizer, which avoids rewriting a path whose
 * SOLE component is a memoized symlink under NOFOLLOW/PARENT_*.
 *
 *  - FOLLOW         : default. open without O_NOFOLLOW, stat, access,
 *                     chmod, chdir, exec target. Resolve every
 *                     component including the final.
 *  - NOFOLLOW       : lstat, readlink, openat(O_NOFOLLOW),
 *                     fstatat(...,AT_SYMLINK_NOFOLLOW), fchownat(...,
 *                     AT_SYMLINK_NOFOLLOW). Resolve parents only;
 *                     pass the final component through literally.
 *  - PARENT_CREATE  : mkdir, mknod, symlink dst, openat(O_CREAT) for
 *                     a non-existent leaf. Resolve parents; the leaf
 *                     may not exist and must not be followed.
 *  - PARENT_REMOVE  : unlink, rmdir, rename dst. Resolve parents;
 *                     preserve leaf-op kernel semantics (unlink
 *                     removes the symlink itself, etc.).
 */
typedef enum {
	TAWCROOT_PATH_FOLLOW         = 0,
	TAWCROOT_PATH_NOFOLLOW       = 1,
	TAWCROOT_PATH_PARENT_CREATE  = 2,
	TAWCROOT_PATH_PARENT_REMOVE  = 3,
} tawcroot_path_mode;

/* Translate a guest path. `out_suffix` must point to a buffer of at
 * least `out_cap` bytes (PATH_MAX is sane). On success, the suffix is
 * NUL-terminated; on failure, contents are unspecified.
 *
 * Absolute paths are clamped to the rootfs view: leading `/` is
 * stripped, internal `..` components are reduced, and a `..` that
 * would escape the rootfs gets clamped at the root (proot-compatible:
 * `/foo/../../etc` becomes `etc`).
 *
 * Relative paths (no leading `/`) reverse-translate through the
 * kernel cwd, then re-run the absolute translator on the joined path.
 *
 * `mode` controls final-component handling — see `tawcroot_path_mode`. */
tawcroot_path_result tawcroot_path_translate(const char *guest_path,
					     char *out_suffix, size_t out_cap,
					     tawcroot_path_mode mode);

/* Configure paths to the host root and to the in-rootfs `/proc` mirror
 * for reverse-translation. Set at init from main.c / phase1.c. */
extern char tawcroot_rootfs_host_path[4096];
extern size_t tawcroot_rootfs_host_path_len;

/* Bind-mount table. One entry per `-b src:dst` on the command line.
 * Resolution: after the absolute path is folded, longest-prefix-match
 * against the bind dst paths; on match, base_fd = bind.src_fd and the
 * suffix is the bytes after bind.dst.
 *
 * Fixed size — no malloc reachable from the handler. The current cap
 * comfortably fits the proot-style mount set (/system /vendor /apex
 * /system_ext /linkerconfig /dev /proc /sys + a couple of app-data
 * passthroughs). Bumps go through this header. */
#define TAWCROOT_MAX_BINDS 32

struct tawcroot_bind {
	int    src_fd;               /* O_PATH | O_DIRECTORY of the host src */
	size_t dst_len;              /* length of `dst`, excluding NUL        */
	char   dst[256];             /* rootfs-relative dst (NO leading '/'). */
	char   src[256];             /* host src path, for lazy re-open after
	                              * a guest fork+closefrom killed src_fd. */
};

/* Re-open a reserved fd (rootfs or bind src) from its stored host path
 * if a guest fork-child's closefrom() closed it. No-op if `*fd_p` still
 * resolves; otherwise opens `host_path` with O_PATH | O_DIRECTORY |
 * O_CLOEXEC, F_DUPFD_CLOEXECs into the reserved range, and updates
 * `*fd_p`. Returns the (possibly new) fd on success, -errno on failure.
 * Async-signal-safe — only raw syscalls. */
long tawcroot_reopen_reserved_fd(int *fd_p, const char *host_path);

extern struct tawcroot_bind tawcroot_binds[TAWCROOT_MAX_BINDS];
extern size_t               tawcroot_n_binds;

/* Add a bind. `dst` may have a leading '/'; we strip it. Returns 0 on
 * success, -errno on failure (no slot left, src open failed, dst too
 * long). Must be called BEFORE the seccomp filter is installed; the
 * call opens the src dir via raw `openat`. */
long tawcroot_path_add_bind(const char *src_host, const char *dst_guest);

/* Initialize the well-known-symlink memoization cache. Reads symlinks
 * for `lib`, `lib64`, `bin`, `sbin`, `var/run`, etc. from the rootfs
 * and stores their targets in a small fixed-size table. After this
 * runs, path translation will rewrite a guest path whose first
 * component matches a memoized symlink to the target form before
 * resolving against the rootfs fd.
 *
 * No-op for prefixes that aren't symlinks. Call after rootfs_fd is
 * set up and before the seccomp filter is installed. */
void tawcroot_path_memoize_well_known(void);

/* Lexical absolute-path canonicalizer (no symlink resolution; just `.`
 * and `..` folding with root-clamp). Input must start with '/' and be
 * NUL-terminated; output is rootfs-relative (no leading '/') in the
 * caller-supplied buffer. Returns 0 on success, -errno on failure
 * (-ENAMETOOLONG on overflow, -ENOENT on caller misuse). Public so
 * `path_resolve.c` can re-fold after splicing a symlink target. */
long tawcroot_path_fold_absolute(const char *path, char *out, size_t out_cap);

/* `/proc/self/exe` synthesis (phase 2e).
 *
 * After manual-load the kernel's view of /proc/self/exe is the
 * libtawcroot binary, not the path the guest asked us to exec.
 * Programs that resolve $ORIGIN, parse argv[0] sanity-checking against
 * /proc/self/exe, or symlink-resolve to find their installed prefix
 * (Firefox, glibc dlopen) need the guest-visible path back. We stash
 * it here at production init and the readlinkat handler returns it
 * when the guest queries /proc/self/exe or /proc/<our-pid>/exe.
 *
 * Set BEFORE the seccomp filter is installed (or before the loader
 * jumps); read from the SIGSYS handler. The buffer is fixed-size and
 * immutable post-init — handler-safe per notes/tawcroot.md
 * "Threading and `vfork` invariants". */
extern char   tawcroot_guest_exe_path[4096];
extern size_t tawcroot_guest_exe_path_len;

/* Set the guest exe path. Called by production main / loader after
 * the guest's requested binary path is known. Truncates silently if
 * the path is longer than the buffer. */
void tawcroot_set_guest_exe_path(const char *path);

/* Probe whether the kernel supports `openat2(2)` (≥5.6). Sets
 * `tawcroot_openat2_works` to 1 on success, 0 on failure. Call
 * BEFORE the seccomp filter goes up — the probe issues an openat2
 * directly, which would otherwise TRAP through our handler (we don't
 * dispatch openat2 yet, so the handler would 0-ENOSYS it, falsely
 * indicating no kernel support).
 *
 * When `tawcroot_openat2_works` is 1, the openat handler routes
 * through openat2 with RESOLVE_IN_ROOT, which fixes generic non-
 * final-component symlink resolution for free (kernel handles symlink
 * walks and `..`-clamp inside the rootfs fd). On older kernels the
 * handler falls back to the string-fold + well-known-memo path. */
extern int tawcroot_openat2_works;
void tawcroot_path_probe_openat2(void);
