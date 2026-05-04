/* Unit tests for the path-translation orchestration
 * (tawcroot/src/path_orchestrate.c).
 *
 * The orchestration is pure with respect to the OS: every external
 * dependency (rootfs/bind tables, memo table, readlink oracle, cwd
 * source) is in the context struct passed by the caller. Production
 * builds the ctx from process globals + raw_sys helpers in
 * `tawcroot_path_translate` (path.c); these tests build it inline so
 * we can exercise the inter-stage seams that handler-layer integration
 * tests miss:
 *
 *   - fold → bind → (memo → resolver) → bind ordering (B5/D3)
 *   - bind-vs-memo collisions
 *   - rootfs-escape attempts via .. chains, absolute symlinks, and
 *     prefix-matching siblings
 *   - route_through_binds longest-prefix-match and component-boundary
 *     correctness
 *
 * `tawcroot_path_fold_absolute` (path_fold.c) was previously only
 * covered indirectly via test_path_resolve.c. Direct fold tests for
 * its overflow / clamp / `.`-`..`-edge cases are in this file too.
 */

#include <cleat/test.h>
#include <stddef.h>
#include <string.h>

#include "path.h"
#include "path_oracle.h"
#include "path_orchestrate.h"
#include "path_resolve.h"

/* Sentinel base_fd value. The orchestration only inspects whether the
 * result's base_fd != ctx->rootfs_base_fd to decide "did a bind take
 * over"; the value itself is opaque. We use -100 in the rootfs slot
 * and 200+i for bind slots so test failures show which one matched. */
#define TEST_ROOTFS_FD 100

static long mock_readlink_none(void *ctx, const char *suffix,
			       char *out, size_t out_cap)
{
	(void)ctx; (void)suffix; (void)out; (void)out_cap;
	return -22;  /* EINVAL — every component "is not a symlink" */
}

static const struct tawcroot_path_oracle ora_empty = {
	.ctx = 0, .readlink = mock_readlink_none,
};

/* Mock symlink table — same shape as test_path_resolve.c's mock_fs. */
struct mock_link {
	const char *path;
	const char *target;
};

struct mock_fs {
	const struct mock_link *links;
	size_t                  n_links;
};

static long mock_readlink(void *ctx, const char *suffix,
			  char *out, size_t out_cap)
{
	struct mock_fs *fs = ctx;
	if (suffix[0] == 0) return -22;
	for (size_t i = 0; i < fs->n_links; i++) {
		if (strcmp(fs->links[i].path, suffix) == 0) {
			size_t tl = strlen(fs->links[i].target);
			if (tl > out_cap) return -36;
			memcpy(out, fs->links[i].target, tl);
			return (long)tl;
		}
	}
	return -22;
}

/* Cwd source for relative-path tests. The orchestration calls this
 * with the test-supplied ctx; we hand back a fixed string. */
struct cwd_state {
	const char *value;
	long        ret;     /* -errno to report (0 = success). */
};

static long mock_cwd(void *ctx, char *out, size_t out_cap)
{
	struct cwd_state *cs = ctx;
	if (cs->ret < 0) return cs->ret;
	size_t n = strlen(cs->value);
	if (n + 1 > out_cap) return -36;
	memcpy(out, cs->value, n + 1);
	return 0;
}

/* Helpers to populate a tawcroot_bind by hand. The struct's `src_fd`
 * is opaque to the orchestration; we set it to a distinct value per
 * bind so failures can identify which bind matched. */
static void mk_bind(struct tawcroot_bind *b, int fd, const char *dst)
{
	b->src_fd  = fd;
	size_t n = strlen(dst);
	b->dst_len = n;
	memcpy(b->dst, dst, n);
	b->dst[n] = 0;
	b->src[0] = 0;
}

/* ----- Direct fold tests (was: covered only via resolver) ----- */

test(fold_basic_strips_leading_slash)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("/foo/bar", out, sizeof out), 0);
	test_str_eq(out, "foo/bar");
}

test(fold_collapses_runs_of_slashes)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("///foo//bar///baz",
						out, sizeof out), 0);
	test_str_eq(out, "foo/bar/baz");
}

test(fold_strips_trailing_slash)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("/foo/bar/", out, sizeof out), 0);
	test_str_eq(out, "foo/bar");
}

test(fold_dot_components_drop)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("/./foo/./bar/.",
						out, sizeof out), 0);
	test_str_eq(out, "foo/bar");
}

test(fold_dotdot_clamps_at_root)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("/foo/../../etc",
						out, sizeof out), 0);
	test_str_eq(out, "etc");
}

test(fold_pure_dotdot_at_root_is_root)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("/..", out, sizeof out), 0);
	test_str_eq(out, "");
}

test(fold_root_dot_is_root)
{
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute("/.", out, sizeof out), 0);
	test_str_eq(out, "");
}

test(fold_buffer_overflow_is_enametoolong)
{
	char out[8];
	long r = tawcroot_path_fold_absolute("/aaaaaaaaa/b", out, sizeof out);
	test_int_eq(r, -36);
}

test(fold_deep_dotdot_chain_at_limit)
{
	/* 32 levels of ../ at the root; folds to empty (root). */
	char in[256] = "/";
	for (int i = 0; i < 32; i++) strcat(in, "../");
	strcat(in, "foo");
	char out[256];
	test_int_eq(tawcroot_path_fold_absolute(in, out, sizeof out), 0);
	test_str_eq(out, "foo");
}

/* ----- Orchestration: relative-path cwd source ----- */

test(orch_relative_uses_cwd_to_join)
{
	struct cwd_state cs = { "/srv", 0 };
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd   = TEST_ROOTFS_FD,
		.binds = 0, .n_binds = 0,
		.memos = 0, .n_memos = 0,
		.oracle           = &ora_empty,
		.cwd_to_guest_abs = mock_cwd,
		.cwd_ctx          = &cs,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "data/x", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, TEST_ROOTFS_FD);
	test_str_eq(out, "srv/data/x");
}

test(orch_relative_cwd_at_rootfs_root)
{
	struct cwd_state cs = { "/", 0 };
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd   = TEST_ROOTFS_FD,
		.oracle           = &ora_empty,
		.cwd_to_guest_abs = mock_cwd, .cwd_ctx = &cs,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "etc/passwd", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_str_eq(out, "etc/passwd");
}

test(orch_relative_cwd_propagates_error)
{
	struct cwd_state cs = { "", -2 };  /* ENOENT — cwd outside rootfs */
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd   = TEST_ROOTFS_FD,
		.oracle           = &ora_empty,
		.cwd_to_guest_abs = mock_cwd, .cwd_ctx = &cs,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "x", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, -2);
}

test(orch_relative_no_cwd_fn_is_enoent)
{
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd   = TEST_ROOTFS_FD,
		.oracle           = &ora_empty,
		.cwd_to_guest_abs = 0, .cwd_ctx = 0,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "x", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, -2);
}

/* ----- Orchestration: route_through_binds ----- */

test(orch_bind_exact_match_empty_suffix)
{
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "tmp");
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/tmp", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, 200);
	test_str_eq(out, "");
}

test(orch_bind_prefix_match_strips_dst)
{
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "system");
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/system/lib/foo", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, 200);
	test_str_eq(out, "lib/foo");
}

test(orch_bind_component_boundary_required)
{
	/* Bind dst "system" must NOT match "/system_ext/foo" — the next
	 * byte after the dst is "_", not "/" or end-of-string. */
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "system");
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/system_ext/foo", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, TEST_ROOTFS_FD);
	test_str_eq(out, "system_ext/foo");
}

test(orch_bind_longest_prefix_wins)
{
	struct tawcroot_bind binds[3];
	mk_bind(&binds[0], 201, "usr");
	mk_bind(&binds[1], 202, "usr/lib");
	mk_bind(&binds[2], 203, "usr/lib/firmware");
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 3,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/usr/lib/firmware/blob",
		out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, 203);   /* deepest match wins, not table order */
	test_str_eq(out, "blob");
}

test(orch_bind_no_match_keeps_rootfs_fd)
{
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "system");
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/etc/passwd", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, TEST_ROOTFS_FD);
	test_str_eq(out, "etc/passwd");
}

/* ----- Orchestration: stage ordering ----- */

test(orch_bind_takes_priority_over_memo)
{
	/* Memo /lib → usr/lib AND a bind on /lib → src=200.
	 *
	 * Bind must come first (B5/D3): the user-supplied bind table is
	 * the authoritative replacement for /lib. If memo ran first the
	 * input would become "usr/lib/x" and the bind on /lib would no
	 * longer apply, silently routing the request to the rootfs view
	 * instead of the user's bind src. */
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "lib");
	struct tawcroot_symlink_memo memos[1] = {{
		.src = "lib", .src_len = 3,
		.target = "usr/lib", .target_len = 7, .target_absolute = 0,
	}};
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.memos = memos, .n_memos = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/lib/libc.so.6", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, 200);    /* bind won, memo did not run */
	test_str_eq(out, "libc.so.6");
}

test(orch_memo_then_bind_when_no_first_pass_match)
{
	/* Memo /lib → usr/lib AND a bind on /usr/lib → src=200.
	 *
	 * Input /lib/x doesn't match the bind directly, so the first
	 * bind pass falls through. Memo then rewrites to usr/lib/x. The
	 * second bind pass picks up the bind on /usr/lib. */
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "usr/lib");
	struct tawcroot_symlink_memo memos[1] = {{
		.src = "lib", .src_len = 3,
		.target = "usr/lib", .target_len = 7, .target_absolute = 0,
	}};
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.memos = memos, .n_memos = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/lib/x", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, 200);
	test_str_eq(out, "x");
}

test(orch_memo_with_absolute_target_refolds)
{
	/* Memo /bin → /usr/bin (absolute target). After the rewrite the
	 * suffix is "/usr/bin/x"; the orchestration re-folds, stripping
	 * the leading slash, and the final suffix is "usr/bin/x". */
	struct tawcroot_symlink_memo memos[1] = {{
		.src = "bin", .src_len = 3,
		.target = "usr/bin", .target_len = 7, .target_absolute = 1,
	}};
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.memos = memos, .n_memos = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/bin/sh", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_str_eq(out, "usr/bin/sh");
}

test(orch_memo_skipped_under_nofollow_when_sole_component)
{
	/* Memo /lib → usr/lib. Under NOFOLLOW, an op against /lib itself
	 * (lstat, readlink, unlink) must operate on the symlink, not the
	 * link target — skip the rewrite. */
	struct tawcroot_symlink_memo memos[1] = {{
		.src = "lib", .src_len = 3,
		.target = "usr/lib", .target_len = 7, .target_absolute = 0,
	}};
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.memos = memos, .n_memos = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/lib", out, sizeof out, TAWCROOT_PATH_NOFOLLOW);
	test_int_eq(r.err, 0);
	test_str_eq(out, "lib");

	/* But under FOLLOW the rewrite still applies. */
	r = tawcroot_path_translate_with_ctx(
		&ctx, "/lib", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_str_eq(out, "usr/lib");
}

test(orch_memo_applies_to_path_with_trailing_components_under_nofollow)
{
	/* The sole-component skip is FOR FOLLOW only when the input is
	 * exactly "lib". /lib/foo under NOFOLLOW still goes through the
	 * rewrite (the leaf is foo, not the symlink). */
	struct tawcroot_symlink_memo memos[1] = {{
		.src = "lib", .src_len = 3,
		.target = "usr/lib", .target_len = 7, .target_absolute = 0,
	}};
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.memos = memos, .n_memos = 1,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/lib/foo", out, sizeof out, TAWCROOT_PATH_NOFOLLOW);
	test_int_eq(r.err, 0);
	test_str_eq(out, "usr/lib/foo");
}

/* ----- Orchestration: resolver runs after memo, before final bind ----- */

test(orch_resolver_fires_after_memo_before_final_bind)
{
	/* No memo. A symlink etc/host-secret → /etc/passwd. Bind on
	 * /etc/passwd → src=200. The resolver must rewrite the symlink
	 * (clamping the absolute target inside the rootfs view); the
	 * final bind pass must then catch the resulting path. */
	struct tawcroot_bind binds[1];
	mk_bind(&binds[0], 200, "etc/passwd");
	const struct mock_link links[] = {
		{ "etc/host-secret", "/etc/passwd" },
	};
	struct mock_fs fs = { links, 1 };
	struct tawcroot_path_oracle ora = { .ctx = &fs, .readlink = mock_readlink };
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.binds = binds, .n_binds = 1,
		.oracle = &ora,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/etc/host-secret", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, 200);
	test_str_eq(out, "");
}

/* ----- Orchestration: rootfs-escape attempts ----- */

test(orch_dotdot_chain_clamps_at_rootfs_root)
{
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.oracle = &ora_empty,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/foo/../../../../../../etc/passwd",
		out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, TEST_ROOTFS_FD);
	test_str_eq(out, "etc/passwd");   /* clamped, not host-relative */
}

test(orch_absolute_symlink_is_clamped_via_resolver)
{
	const struct mock_link links[] = {
		{ "etc/secret", "/etc/passwd" },
	};
	struct mock_fs fs = { links, 1 };
	struct tawcroot_path_oracle ora = { .ctx = &fs, .readlink = mock_readlink };
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.oracle = &ora,
	};
	char out[256];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/etc/secret", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	test_int_eq(r.base_fd, TEST_ROOTFS_FD);
	test_str_eq(out, "etc/passwd");
}

/* ----- Misuse: NULLs and zero capacity ----- */

test(orch_null_guest_path_is_efault)
{
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD, .oracle = &ora_empty,
	};
	char out[16];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, 0, out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, -14);
}

test(orch_null_out_suffix_is_efault)
{
	/* Tightened by the post-review pass — the orchestration's pre-
	 * fold helpers would segfault on a NULL out_suffix; surface it
	 * as -EFAULT instead so misuse fails cleanly. */
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD, .oracle = &ora_empty,
	};
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/foo", 0, 16, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, -14);
}

test(orch_zero_capacity_is_efault)
{
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD, .oracle = &ora_empty,
	};
	char out[16];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/foo", out, 0, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, -14);
}

/* ----- Memo loop bound ----- */

test(orch_memo_loop_terminates_at_eight_hops)
{
	/* Pathological memo set: a → b, b → a (a 2-cycle). Without the
	 * hop bound, the orchestration would loop forever. With it, we
	 * exit after 8 hops with whichever side we land on; the only
	 * thing the test cares about is that the call returns at all
	 * (cleat has no test timeout — an infinite loop hangs the run). */
	struct tawcroot_symlink_memo memos[2] = {
		{ .src = "a", .src_len = 1,
		  .target = "b", .target_len = 1, .target_absolute = 0 },
		{ .src = "b", .src_len = 1,
		  .target = "a", .target_len = 1, .target_absolute = 0 },
	};
	struct tawcroot_path_translate_ctx ctx = {
		.rootfs_base_fd = TEST_ROOTFS_FD,
		.memos = memos, .n_memos = 2,
		.oracle = &ora_empty,
	};
	char out[16];
	tawcroot_path_result r = tawcroot_path_translate_with_ctx(
		&ctx, "/a", out, sizeof out, TAWCROOT_PATH_FOLLOW);
	test_int_eq(r.err, 0);
	/* After 8 hops alternating a↔b, the suffix is one of "a" or "b". */
	int landed = (out[0] == 'a' || out[0] == 'b') && out[1] == 0;
	test_int_eq(landed, 1);
}
