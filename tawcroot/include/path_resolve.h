/* Manual symlink-aware path canonicalization.
 *
 * =======================================================================
 *  WHY THIS EXISTS
 * =======================================================================
 *
 * Linux kernel <5.6 does not have `openat2(RESOLVE_IN_ROOT)`. Without
 * it, an in-rootfs symlink with an *absolute* target (e.g.
 * `/etc/host-secret -> /etc/passwd`) escapes our rootfs view, because
 * we are not actually `chroot()`'d: the kernel resolves the absolute
 * target against the host root, not against `tawcroot_rootfs_fd`.
 *
 * Our primary device (OnePlus 9, Android 14, kernel 5.4.284) is in
 * this category. Manual symlink resolution is therefore correctness-
 * required there, not optional.
 *
 * On kernel >=5.6 we still call into this resolver. The kernel's
 * `openat2(RESOLVE_IN_ROOT)` would do equivalent clamping inside the
 * `openat` handler, but running our resolver first means *every*
 * path-bearing handler (`fstatat`, `readlinkat`, `unlinkat`, ...)
 * gets the same clamping discipline regardless of kernel version. The
 * cost is a few extra `readlinkat` calls per non-cached path; the
 * well-known-symlink memo absorbs the hot-path glibc rootfs symlinks
 * (`/lib`, `/lib64`, ...) so the typical request hits zero
 * resolver-issued readlinks.
 *
 * The well-known-symlink memo in `path.c` is intentionally NOT here:
 * it is also a perf optimization on kernels that have `openat2`, so
 * it stays even if this file goes away.
 *
 * =======================================================================
 *  HOW TO DROP THIS WHEN OUR MIN KERNEL BECOMES 5.6+
 * =======================================================================
 *
 *   1. Delete `tawcroot/src/path_resolve.c`.
 *   2. Delete `tawcroot/include/path_resolve.h` and `path_oracle.h`.
 *   3. In `tawcroot/src/path.c`, remove the `#include "path_resolve.h"`,
 *      the production oracle (`prod_*`), and the call to
 *      `tawcroot_path_resolve_symlinks(...)` inside
 *      `tawcroot_path_translate()`. (One call site, marked `LEGACY-5.4`.)
 *   4. Remove `path_resolve.c` from `tawcroot/Makefile`'s `PROD_C` /
 *      `PROD_C_FOR_TESTS` and from `tawcroot/build`'s
 *      `SRC_C_PROD`.
 *   5. Delete `tawcroot/tests/unit/test_path_resolve.c`.
 *   6. In `tawcroot/tests/testhost/src/phase1.c`, re-gate the absolute-symlink
 *      escape test on `tawcroot_openat2_works` (it once was, before
 *      this resolver landed) and audit each non-`openat` path-bearing
 *      handler for symlink-clamp coverage; on >=5.6-only the only
 *      handler currently routing through `openat2(RESOLVE_IN_ROOT)`
 *      is `handle_openat`, so the others would need either explicit
 *      `openat2(O_PATH | RESOLVE_IN_ROOT)` canonicalization or a
 *      different design.
 *
 * =======================================================================
 */

#pragma once

#include <stddef.h>

#include "path.h"
#include "path_oracle.h"

/* Resolve symlinks in `suf` (rootfs-relative path, no leading '/',
 * NUL-terminated) in place. On success, `suf` is overwritten with a
 * canonical rootfs-relative path with all symlinks followed per the
 * given mode. On failure, `suf` contents are unspecified.
 *
 * Mode behavior:
 *   FOLLOW         — every component including the leaf
 *   NOFOLLOW
 *   PARENT_CREATE
 *   PARENT_REMOVE  — parent components only; leaf preserved verbatim
 *
 * Returns 0 on success, -errno on failure:
 *   -ELOOP        — exceeded SYMLOOP_MAX (40) symlink hops (covers both
 *                   self-loop and chain-bomb cases)
 *   -ENAMETOOLONG — splicing a target into the path overflowed `cap`
 *
 * If `oracle->readlink` returns -EINVAL for a component (i.e. the
 * component is not a symlink), resolution continues to the next
 * component. If it returns -ENOENT (component missing) or any other
 * error, the resolver stops walking and returns 0; downstream syscalls
 * get to surface their own kernel-defined error (ENOENT / ENOTDIR / ...
 * depending on context). This deferral is deliberate — the resolver's
 * job is symlink clamping, not stat-style existence checking.
 */
long tawcroot_path_resolve_symlinks(char *suf, size_t cap,
				    tawcroot_path_mode mode,
				    const struct tawcroot_path_oracle *oracle);
