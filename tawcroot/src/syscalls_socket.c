/* AF_UNIX socket address translation.
 *
 * `bind(2)` and `connect(2)` take the path INSIDE a `struct sockaddr_un`,
 * not as a separate path argument the kernel resolves through *at-style
 * APIs. The kernel reads `sun_path` directly from the userspace
 * sockaddr and resolves it against the calling task's filesystem
 * namespace — bypassing every translation rule we apply through the
 * dispatch path.
 *
 * Symptom that pushed this in: `pacman-key --init` runs `gpg-agent
 * --daemon`, which calls `bind(fd, &(struct sockaddr_un){.sun_path =
 * "/root/.gnupg/S.gpg-agent"}, ...)`. Without translation the kernel
 * looks for `/root/` on the host, returns -ENOENT, and gpg-agent
 * exits 2.
 *
 * Translation strategy: rebuild the sockaddr on the handler stack with
 * a `/proc/self/fd/<base_fd>/<suffix>` path. The kernel's path resolver
 * handles `/proc/self/fd/N/x/y` correctly — re-rooting the lookup at
 * the directory referenced by fd N. This avoids needing to know the
 * rootfs's host-side prefix and works uniformly for paths that fall
 * through bind-mount sources.
 *
 * `sun_path` is 108 bytes. `/proc/self/fd/` = 14, plus a 4–5-digit fd,
 * plus '/', plus suffix. Even with a worst-case fd of "10000" + 100B
 * suffix we're at ~120B which overflows; we cap at 107 (leaving room
 * for the trailing NUL the kernel doesn't read but our fold does) and
 * return -ENAMETOOLONG when over budget.
 *
 * Abstract sockets (`sun_path[0] == '\0'`) and non-AF_UNIX families
 * pass through unchanged.
 */

#include <stddef.h>
#include <stdint.h>
#include <ucontext.h>

#include "dispatch.h"
#include "path.h"
#include "raw_sys.h"
#include "sysnr.h"
#include "usercopy.h"

#define AF_UNIX_FAMILY  1
#define EFAULT_NEG    (-14)
#define ENAMETOOLONG_NEG (-36)

/* sockaddr_un layout, mirrored locally to avoid pulling <sys/un.h>:
 *   uint16_t sun_family;
 *   char     sun_path[108];
 * Total 110 bytes.
 *
 * We don't include <linux/un.h> because the freestanding handler build
 * runs without sysroot includes for kernel headers. This local mirror
 * matches the kernel ABI and stays small. */
struct tawc_sockaddr_un {
	uint16_t sun_family;
	char     sun_path[108];
};

/* Render `/proc/self/fd/<fd>/<suffix>` into `dst`. Returns the byte
 * count (excluding NUL) or -ENAMETOOLONG if the rendering doesn't fit
 * in `cap` bytes (cap should be ≤ sizeof sun_path). */
static long render_proc_fd_path(char *dst, size_t cap, int fd,
                                const char *suffix)
{
	static const char prefix[] = "/proc/self/fd/";
	const size_t prefix_len = sizeof prefix - 1;

	if (cap <= prefix_len) return ENAMETOOLONG_NEG;

	size_t i = 0;
	for (; i < prefix_len; i++) dst[i] = prefix[i];

	/* Decimal fd. fd >= 0 by construction (we checked). */
	char fdbuf[12];
	int  fdn = 0;
	int  v = fd;
	if (v < 0) return EFAULT_NEG;
	if (v == 0) fdbuf[fdn++] = '0';
	while (v) { fdbuf[fdn++] = '0' + (v % 10); v /= 10; }
	if (i + (size_t)fdn >= cap) return ENAMETOOLONG_NEG;
	while (fdn--) dst[i++] = fdbuf[fdn];

	/* Suffix may be empty (the fd itself names the bind target). */
	if (suffix && suffix[0]) {
		if (i + 1 >= cap) return ENAMETOOLONG_NEG;
		dst[i++] = '/';
		size_t j = 0;
		while (suffix[j]) {
			if (i + 1 >= cap) return ENAMETOOLONG_NEG;
			dst[i++] = suffix[j++];
		}
	}
	dst[i] = '\0';
	return (long)i;
}

/* Common bind/connect translator. `nr` is the syscall to issue
 * (TAWC_SYS_bind or TAWC_SYS_connect); the ABI is identical. */
static long do_translate_unix_addr(int nr, const tawcroot_syscall_args *args)
{
	int sockfd = (int)args->a;
	const void *guest_addr = (const void *)(uintptr_t)args->b;
	long  addrlen = (long)args->c;

	/* Defensive: if no addr or impossibly small, pass through. */
	if (!guest_addr || addrlen < (long)sizeof(uint16_t)) {
		return TAWC_RAW(nr, args->a, args->b, args->c, 0, 0, 0);
	}

	/* Cap addrlen at our local sockaddr_un size. The kernel does the
	 * same — anything past 110B is ignored. */
	if (addrlen > (long)sizeof(struct tawc_sockaddr_un)) {
		addrlen = sizeof(struct tawc_sockaddr_un);
	}

	struct tawc_sockaddr_un un_in;
	long e = tawc_copy_from_guest(&un_in, (size_t)addrlen, guest_addr);
	if (e < 0) return EFAULT_NEG;

	/* Non-AF_UNIX: untouched. */
	if (un_in.sun_family != AF_UNIX_FAMILY) {
		return TAWC_RAW(nr, args->a, args->b, args->c, 0, 0, 0);
	}

	/* Determine the path-bytes length the guest provided. */
	long path_bytes = addrlen - (long)sizeof(uint16_t);
	if (path_bytes <= 0) {
		/* Nameless / autobind. Pass through. */
		return TAWC_RAW(nr, args->a, args->b, args->c, 0, 0, 0);
	}

	/* Abstract socket: sun_path[0] == 0. No filesystem translation. */
	if (un_in.sun_path[0] == '\0') {
		return TAWC_RAW(nr, args->a, args->b, args->c, 0, 0, 0);
	}

	/* Bind takes a NUL-terminated or addrlen-bounded path. The kernel
	 * uses min(strnlen, path_bytes). We mirror that to extract the
	 * guest's intended path string. */
	if (path_bytes > (long)sizeof un_in.sun_path) {
		path_bytes = sizeof un_in.sun_path;
	}
	char guest_path[109];
	long pl = 0;
	while (pl < path_bytes && un_in.sun_path[pl] != '\0') {
		guest_path[pl] = un_in.sun_path[pl];
		pl++;
	}
	guest_path[pl] = '\0';

	/* Translate. PARENT_CREATE for bind() (the leaf is a socket node
	 * the kernel will create). connect() should be FOLLOW (the socket
	 * already exists), but PARENT_CREATE folds + clamps the same
	 * way and the kernel does the existence check itself; using
	 * PARENT_CREATE for both is fine and saves a code path here. */
	char suffix[1024];
	tawcroot_path_result r = tawcroot_path_translate(guest_path,
	                                                 suffix, sizeof suffix,
	                                                 TAWCROOT_PATH_PARENT_CREATE);
	if (r.err) return r.err;

	/* Re-pack a sockaddr_un with /proc/self/fd/<base>/<suffix>. */
	struct tawc_sockaddr_un un_out;
	un_out.sun_family = AF_UNIX_FAMILY;
	long n = render_proc_fd_path(un_out.sun_path, sizeof un_out.sun_path,
	                             r.base_fd, suffix);
	if (n < 0) return n;

	/* New addrlen: family bytes + path bytes + 1 (NUL). The kernel
	 * accepts a NUL-inclusive length for filesystem sockets. */
	long new_addrlen = (long)sizeof(uint16_t) + n + 1;
	return TAWC_RAW(nr, (long)sockfd, (long)&un_out,
	                new_addrlen, 0, 0, 0);
}

static long handle_bind(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	return do_translate_unix_addr(TAWC_SYS_bind, args);
}

static long handle_connect(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	return do_translate_unix_addr(TAWC_SYS_connect, args);
}

void tawcroot_socket_register(void)
{
	tawcroot_dispatch_install(TAWC_SYS_bind,    handle_bind);
	tawcroot_dispatch_install(TAWC_SYS_connect, handle_connect);
}
