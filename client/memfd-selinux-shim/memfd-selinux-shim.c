/*
 * memfd-selinux-shim: LD_PRELOAD library that relabels memfd_create() fds
 * so they can be shared cross-process on Android.
 *
 * Problem: On Android, memfds created by root/chroot processes get SELinux
 * label "u:object_r:tmpfs:s0". The untrusted_app domain (Android apps) lacks
 * { read write map } permission on the tmpfs type, so apps can't mmap these
 * fds even when received via SCM_RIGHTS.
 *
 * Fix: After creating the memfd, call fsetxattr() to relabel it to
 * "u:object_r:appdomain_tmpfs:s0". Apps have full access to appdomain_tmpfs,
 * and MLS categories are not enforced for this type. This preserves full
 * memfd semantics (resize, mmap, sealing) — unlike the old ashmem approach.
 *
 * Requires root (for fsetxattr on SELinux labels).
 *
 * Usage: LD_PRELOAD=/path/to/libashmem-shim.so wayland-client
 */

#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>

static const char LABEL[] = "u:object_r:appdomain_tmpfs:s0";

int memfd_create(const char *name, unsigned int flags) {
    int fd = (int)syscall(SYS_memfd_create, name, flags);
    if (fd < 0)
        return fd;

    if (fsetxattr(fd, "security.selinux", LABEL, sizeof(LABEL) - 1, 0) < 0) {
        fprintf(stderr,
                "memfd-shim: fsetxattr(%d, \"%s\") failed: %s (non-fatal)\n",
                fd, LABEL, strerror(errno));
    }

    return fd;
}
