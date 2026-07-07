/* Hosted handler-level tests for syscalls_socket.c's sun_path budget
 * tiers (issue: tawcroot-af-unix-sun-path-budget).
 *
 * sun_path is 108 bytes including NUL. The host-absolute rendering
 * (tier 1) spends the rootfs host prefix on every translated socket
 * path, so guest paths that are fine natively can overflow. These
 * tests pin the fallback spellings:
 *
 *   tier 2 — /proc/self/fd/<base_fd>/<suffix>  (short suffix)
 *   tier 3 — /proc/self/fd/<parent_fd>/<leaf>  (long suffix; bind
 *            reserves the parent fd so getsockname/getpeername can
 *            reverse-translate the stored spelling)
 *
 * All paths here are built relative to the tmpdir rootfs, whose host
 * prefix (~/tmp/tawcroot-hosted-*) is >20 bytes — enough that a
 * 86-byte guest suffix overflows tier 1 deterministically.
 */

#include <cleat/test.h>

#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include "hosted.h"
#include "../integration/rootfs_helpers.h"

#include "errno_neg.h"
#include "fdtab.h"
#include "path.h"
#include "sysnr.h"

/* Build "<dir>/<name repeated to `len` chars>" guest paths. */
static void fill_name(char *dst, char c, size_t len)
{
	memset(dst, c, len);
	dst[len] = '\0';
}

static void mk_sockaddr(struct sockaddr_un *sa, const char *path)
{
	memset(sa, 0, sizeof *sa);
	sa->sun_family = AF_UNIX;
	memcpy(sa->sun_path, path, strlen(path));
}

/* Like test(), but without the auto-registering constructor: the three
 * successful-bind tests below are registered conditionally in the
 * register_dynamic_tests block, so they don't run where the kernel
 * refuses to create a socket file in the test tmpdir (see there). */
#define socket_test(name) \
	static void test_##name([[maybe_unused]] TestCtx *test_ctx, \
				[[maybe_unused]] void const *_null_test_data)

/* Can we create an AF_UNIX socket *file* in TAWCROOT_TEST_TMPDIR? On a
 * real device's app context (app_data_file) yes, so production and the
 * physical-device test path exercise the tiers for real. But Android's
 * shell domain can't create a sock_file under shell_data_file, so the
 * emulator device suite (adb shell, /data/local/tmp) gets EACCES on any
 * successful bind — the same SELinux limitation that makes the
 * FIFO/mknod checks host-only. Probe once and skip rather than fail. */
static int socket_files_creatable(void)
{
	char dir[512];
	snprintf(dir, sizeof dir, "%s/tawcroot-sockprobe-%d",
		 TAWCROOT_TEST_TMPDIR, getpid());
	mkdir(dir, 0755);
	char sp[600];
	snprintf(sp, sizeof sp, "%s/s", dir);

	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	struct sockaddr_un sa;
	mk_sockaddr(&sa, sp);
	int ok = fd >= 0 && bind(fd, (struct sockaddr *)&sa, sizeof sa) == 0;
	if (fd >= 0) close(fd);
	unlink(sp);
	rmdir(dir);
	return ok;
}

/* bind through the handler; returns the raw handler rv. th_sys()
 * captures `test_ctx`, so helpers take it explicitly. */
static long th_bind(TestCtx *test_ctx, int fd, const char *guest_path)
{
	struct sockaddr_un sa;
	mk_sockaddr(&sa, guest_path);
	return th_sys(TAWC_SYS_bind, fd, &sa, sizeof sa, 0, 0, 0);
}

/* getsockname through the handler into `out` (must hold 108+). */
static long th_getsockname(TestCtx *test_ctx, int fd,
			   char *out, size_t out_cap)
{
	struct sockaddr_un sa;
	unsigned int len = sizeof sa;
	memset(&sa, 0, sizeof sa);
	long rv = th_sys(TAWC_SYS_getsockname, fd, &sa, &len, 0, 0, 0);
	if (rv < 0) return rv;
	size_t n = strnlen(sa.sun_path, sizeof sa.sun_path);
	if (n + 1 > out_cap) n = out_cap - 1;
	memcpy(out, sa.sun_path, n);
	out[n] = '\0';
	return rv;
}

/* Tier 2: guest suffix short enough for /proc/self/fd/<rootfs_fd>/
 * <suffix> but the host-absolute path overflows sun_path. The bind
 * must succeed, create the socket inode at the translated host
 * location, and getsockname must reverse-translate the /proc spelling
 * back to the guest path. */
socket_test(hosted_unix_bind_over_budget_short_suffix)
{
	th_view v;
	th_setup(&v, "sock-t2");
	test_true(strlen(v.root) > 20);

	char guest[128] = "/run/";
	fill_name(guest + 5, 'a', 82);          /* guest len 87 */
	char host[4300];
	snprintf(host, sizeof host, "%s%s", v.root, guest);
	test_true(strlen(host) > 107);           /* tier 1 must overflow */

	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	test_true(fd >= 0);
	test_int_eq(th_bind(test_ctx, fd, guest), 0);

	struct stat st = {0};
	test_int_eq(stat(host, &st), 0);
	test_true(S_ISSOCK(st.st_mode));

	char back[128];
	test_int_eq(th_getsockname(test_ctx, fd, back, sizeof back), 0);
	test_str_eq(back, guest);

	test_int_eq(close(fd), 0);
	th_teardown(&v);
}

/* Tier 3: the suffix itself overflows the tier-2 spelling; bind
 * anchors a reserved parent-dir fd. Two binds in the same directory
 * must share one reserved fd (dedup by dev/ino), and getsockname of
 * both must reverse-translate. */
socket_test(hosted_unix_bind_over_budget_deep_suffix)
{
	th_view v;
	th_setup(&v, "sock-t3");

	char dir[64];
	fill_name(dir, 'b', 60);
	char hostdir[4300];
	snprintf(hostdir, sizeof hostdir, "%s/run/%s", v.root, dir);
	test_true(rh_mkdir_p(hostdir, 0755));

	char guest1[128], guest2[128];
	snprintf(guest1, sizeof guest1, "/run/%s/", dir);
	fill_name(guest1 + strlen(guest1), 'c', 40);   /* len 106 */
	snprintf(guest2, sizeof guest2, "/run/%s/", dir);
	fill_name(guest2 + strlen(guest2), 'd', 40);

	size_t reserved_before = tawcroot_n_reserved_fds;

	int fd1 = socket(AF_UNIX, SOCK_STREAM, 0);
	int fd2 = socket(AF_UNIX, SOCK_STREAM, 0);
	test_true(fd1 >= 0 && fd2 >= 0);
	test_int_eq(th_bind(test_ctx, fd1, guest1), 0);
	test_int_eq(th_bind(test_ctx, fd2, guest2), 0);

	/* One parent dir → one reserved anchor for both sockets. */
	test_int_eq((long)(tawcroot_n_reserved_fds - reserved_before), 1);

	char host[4300];
	snprintf(host, sizeof host, "%s%s", v.root, guest1);
	struct stat st = {0};
	test_int_eq(stat(host, &st), 0);
	test_true(S_ISSOCK(st.st_mode));

	char back[128];
	test_int_eq(th_getsockname(test_ctx, fd1, back, sizeof back), 0);
	test_str_eq(back, guest1);
	test_int_eq(th_getsockname(test_ctx, fd2, back, sizeof back), 0);
	test_str_eq(back, guest2);

	test_int_eq(close(fd1), 0);
	test_int_eq(close(fd2), 0);
	th_teardown(&v);
}

/* connect to an over-budget path: the parent fd is transient (no
 * reserved-fd growth), and getpeername on the client reverse-
 * translates the server's stored tier-3 bind spelling. */
socket_test(hosted_unix_connect_over_budget_transient_fd)
{
	th_view v;
	th_setup(&v, "sock-conn");

	char dir[64];
	fill_name(dir, 'b', 60);
	char hostdir[4300];
	snprintf(hostdir, sizeof hostdir, "%s/run/%s", v.root, dir);
	test_true(rh_mkdir_p(hostdir, 0755));

	char guest[128];
	snprintf(guest, sizeof guest, "/run/%s/", dir);
	fill_name(guest + strlen(guest), 'c', 40);

	int srv = socket(AF_UNIX, SOCK_STREAM, 0);
	test_true(srv >= 0);
	test_int_eq(th_bind(test_ctx, srv, guest), 0);       /* reserves the anchor */
	test_int_eq(listen(srv, 1), 0);

	size_t reserved_after_bind = tawcroot_n_reserved_fds;

	int cli = socket(AF_UNIX, SOCK_STREAM, 0);
	test_true(cli >= 0);
	struct sockaddr_un sa;
	mk_sockaddr(&sa, guest);
	test_int_eq(th_sys(TAWC_SYS_connect, cli, &sa, sizeof sa, 0, 0, 0), 0);

	/* connect must not accumulate reserved fds. */
	test_int_eq((long)(tawcroot_n_reserved_fds - reserved_after_bind), 0);

	struct sockaddr_un peer;
	unsigned int plen = sizeof peer;
	memset(&peer, 0, sizeof peer);
	test_int_eq(th_sys(TAWC_SYS_getpeername, cli, &peer, &plen, 0, 0, 0), 0);
	test_str_eq(peer.sun_path, guest);

	test_int_eq(close(cli), 0);
	test_int_eq(close(srv), 0);
	th_teardown(&v);
}

/* A single-component leaf too long for even the tier-3 spelling stays
 * -ENAMETOOLONG (the kernel could not bind a path with that leaf under
 * any prefix longer than ~20 bytes either). */
test(hosted_unix_bind_leaf_too_long_enametoolong)
{
	th_view v;
	th_setup(&v, "sock-long");

	char guest[128] = "/";
	fill_name(guest + 1, 'e', 105);          /* 105-byte leaf at / */

	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	test_true(fd >= 0);
	test_int_eq(th_bind(test_ctx, fd, guest), TAWC_ENAMETOOLONG);

	test_int_eq(close(fd), 0);
	th_teardown(&v);
}

/* Register the successful-bind tests only where a socket file can
 * actually be created in the test tmpdir (host, and app context on a
 * real device). See socket_files_creatable() above. The
 * enametoolong test never reaches a kernel bind, so it registers
 * unconditionally via test() and runs everywhere. */
register_dynamic_tests
{
	if (!socket_files_creatable()) return;
	csview mod = test_module_from_file(__FILE__);
	register_test(mod, c_sv("hosted_unix_bind_over_budget_short_suffix"),
		      test_hosted_unix_bind_over_budget_short_suffix,
		      nullptr, nullptr);
	register_test(mod, c_sv("hosted_unix_bind_over_budget_deep_suffix"),
		      test_hosted_unix_bind_over_budget_deep_suffix,
		      nullptr, nullptr);
	register_test(mod, c_sv("hosted_unix_connect_over_budget_transient_fd"),
		      test_hosted_unix_connect_over_budget_transient_fd,
		      nullptr, nullptr);
}
