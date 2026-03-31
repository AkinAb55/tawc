/*
 * ashmem-shim: LD_PRELOAD library that redirects memfd_create() to use
 * Android's ashmem driver instead. This works around SELinux denials where
 * untrusted_app cannot mmap tmpfs-backed memfds created by other processes.
 *
 * ashmem fds are in the mlstrustedobject set, so they bypass MLS checks
 * and can be freely shared between processes via Unix sockets.
 *
 * Handles Wayland SHM pool resize by over-allocating the ashmem buffer on
 * first SET_SIZE (ashmem only allows one SET_SIZE before first mmap).
 * Subsequent "resizes" within the pre-allocated space succeed silently.
 *
 * Usage: LD_PRELOAD=/path/to/libashmem-shim.so firefox
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/ioctl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* ashmem ioctl definitions (from linux/ashmem.h) */
#define ASHMEM_NAME_LEN 256
#define __ASHMEMIOC 0x77
#define ASHMEM_SET_NAME _IOW(__ASHMEMIOC, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_SET_SIZE _IOW(__ASHMEMIOC, 3, size_t)
#define ASHMEM_GET_SIZE _IO(__ASHMEMIOC, 4)

/*
 * Minimum allocation size for ashmem buffers. Wayland SHM pools start small
 * (e.g. 2304 bytes for cursors) then grow (to 6912+). Since ashmem only
 * allows one SET_SIZE before first mmap, we over-allocate to handle resizes.
 * 4MB is generous enough for cursor pools and small SHM buffers.
 */
#define ASHMEM_MIN_ALLOC (4 * 1024 * 1024)

/*
 * Track which fds are ashmem and their allocated/logical sizes.
 */
#define MAX_FD_TRACK 1024

struct ashmem_fd_info {
    int is_ashmem;
    size_t allocated_size;  /* actual ashmem allocation (over-allocated) */
    size_t logical_size;    /* size the caller thinks the buffer is */
};

static struct ashmem_fd_info fd_info[MAX_FD_TRACK];
static pthread_mutex_t fd_lock = PTHREAD_MUTEX_INITIALIZER;

static void fd_set_ashmem(int fd) {
    if (fd >= 0 && fd < MAX_FD_TRACK) {
        pthread_mutex_lock(&fd_lock);
        fd_info[fd].is_ashmem = 1;
        fd_info[fd].allocated_size = 0;
        fd_info[fd].logical_size = 0;
        pthread_mutex_unlock(&fd_lock);
    }
}

static void fd_clear_ashmem(int fd) {
    if (fd >= 0 && fd < MAX_FD_TRACK) {
        pthread_mutex_lock(&fd_lock);
        fd_info[fd].is_ashmem = 0;
        fd_info[fd].allocated_size = 0;
        fd_info[fd].logical_size = 0;
        pthread_mutex_unlock(&fd_lock);
    }
}

static int fd_is_ashmem(int fd) {
    if (fd < 0 || fd >= MAX_FD_TRACK)
        return 0;
    pthread_mutex_lock(&fd_lock);
    int result = fd_info[fd].is_ashmem;
    pthread_mutex_unlock(&fd_lock);
    return result;
}

/*
 * Set or "resize" an ashmem fd. On first call, does the real ASHMEM_SET_SIZE
 * with an over-allocated size. On subsequent calls, if the requested size
 * fits within the allocation, just updates the logical size.
 * Returns 0 on success, -1 on failure (sets errno).
 */
static int ashmem_set_logical_size(int fd, size_t requested_size) {
    if (fd < 0 || fd >= MAX_FD_TRACK)
        return -1;

    pthread_mutex_lock(&fd_lock);

    if (fd_info[fd].allocated_size == 0) {
        /* First SET_SIZE - do the real ioctl with over-allocation */
        size_t alloc = requested_size;
        if (alloc < ASHMEM_MIN_ALLOC)
            alloc = ASHMEM_MIN_ALLOC;

        int ret = ioctl(fd, ASHMEM_SET_SIZE, alloc);
        if (ret < 0) {
            int err = errno;
            pthread_mutex_unlock(&fd_lock);
            errno = err;
            return -1;
        }
        fd_info[fd].allocated_size = alloc;
        fd_info[fd].logical_size = requested_size;
        pthread_mutex_unlock(&fd_lock);

        fprintf(stderr, "ashmem-shim: SET_SIZE(%d, %zu) allocated %zu\n",
                fd, requested_size, alloc);
        return 0;
    }

    /* Subsequent call - check if it fits */
    if (requested_size <= fd_info[fd].allocated_size) {
        fd_info[fd].logical_size = requested_size;
        pthread_mutex_unlock(&fd_lock);

        fprintf(stderr, "ashmem-shim: resize(%d, %zu) fits in %zu - ok\n",
                fd, requested_size, fd_info[fd].allocated_size);
        return 0;
    }

    /* Doesn't fit - can't resize ashmem after first mmap */
    pthread_mutex_unlock(&fd_lock);
    fprintf(stderr,
            "ashmem-shim: resize(%d, %zu) exceeds allocation %zu - FAIL\n",
            fd, requested_size, fd_info[fd].allocated_size);
    errno = EINVAL;
    return -1;
}

/*
 * Check if a memfd should skip ashmem redirection (use real memfd instead).
 * Most memfds need ashmem for cross-process SELinux compatibility, but
 * some application-internal memfds (like Firefox's IPC) break with ashmem
 * semantics and don't need cross-process sharing with the compositor.
 */
static int skip_ashmem(const char *name) {
    if (!name)
        return 0;
    /* Firefox IPC memfds - used between Firefox's own processes, not shared
     * with the compositor. Ashmem breaks Firefox's IPC mechanism. */
    if (strncmp(name, "mozilla-ipc", 11) == 0)
        return 1;
    return 0;
}

/*
 * Replace memfd_create with ashmem, but ONLY for Wayland-related memfds.
 * Other memfds (like Firefox's mozilla-ipc) are left as real memfds since
 * they don't cross the SELinux boundary.
 */
int memfd_create(const char *name, unsigned int flags) {
    if (skip_ashmem(name)) {
        /* Pass through to real memfd_create via syscall */
        long ret = syscall(SYS_memfd_create, name, flags);
        return (int)ret;
    }

    (void)flags;

    int fd = open("/dev/ashmem", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "ashmem-shim: open(/dev/ashmem) failed: %s\n",
                strerror(errno));
        return -1;
    }

    if (name) {
        /* ASHMEM_SET_NAME takes a fixed-size buffer, truncate if needed */
        char ashmem_name[ASHMEM_NAME_LEN];
        strncpy(ashmem_name, name, ASHMEM_NAME_LEN - 1);
        ashmem_name[ASHMEM_NAME_LEN - 1] = '\0';
        ioctl(fd, ASHMEM_SET_NAME, ashmem_name);
    }

    fd_set_ashmem(fd);

    fprintf(stderr, "ashmem-shim: memfd_create(\"%s\") -> ashmem fd %d\n",
            name ? name : "(null)", fd);
    return fd;
}

/*
 * Intercept ftruncate to translate to ASHMEM_SET_SIZE for ashmem fds.
 * libwayland calls ftruncate() to set the pool size after memfd_create().
 * Uses over-allocation + logical size tracking to handle resizes.
 */
int ftruncate(int fd, off_t length) {
    if (fd_is_ashmem(fd)) {
        int ret = ashmem_set_logical_size(fd, (size_t)length);
        if (ret < 0) {
            fprintf(stderr,
                    "ashmem-shim: ftruncate(%d, %ld) failed: %s\n", fd,
                    (long)length, strerror(errno));
            return -1;
        }
        return 0;
    }

    /* Not an ashmem fd - call real ftruncate via syscall */
    return syscall(SYS_ftruncate, fd, length);
}

/* Also intercept ftruncate64 in case glibc routes through it */
int ftruncate64(int fd, off_t length) {
    return ftruncate(fd, length);
}

/*
 * Intercept posix_fallocate - weston's os_create_anonymous_file() uses this
 * instead of ftruncate to size the buffer. Uses over-allocation to handle
 * Wayland SHM pool resizes.
 */
int posix_fallocate(int fd, off_t offset, off_t len) {
    if (fd_is_ashmem(fd)) {
        size_t total = (size_t)(offset + len);
        int ret = ashmem_set_logical_size(fd, total);
        if (ret < 0) {
            int err = errno;
            fprintf(stderr,
                    "ashmem-shim: posix_fallocate(%d, %ld, %ld) failed: %s\n",
                    fd, (long)offset, (long)len, strerror(err));
            return err;
        }
        return 0;
    }

    /* Not ashmem - call real posix_fallocate */
    static int (*real_posix_fallocate)(int, off_t, off_t) = NULL;
    if (!real_posix_fallocate) {
        real_posix_fallocate = dlsym(RTLD_NEXT, "posix_fallocate");
    }
    return real_posix_fallocate(fd, offset, len);
}

int posix_fallocate64(int fd, off_t offset, off_t len) {
    return posix_fallocate(fd, offset, len);
}

/*
 * Intercept fallocate (the raw syscall wrapper) as well.
 */
int fallocate(int fd, int mode, off_t offset, off_t len) {
    if (fd_is_ashmem(fd)) {
        size_t total = (size_t)(offset + len);
        int ret = ashmem_set_logical_size(fd, total);
        if (ret < 0) {
            int err = errno;
            fprintf(stderr,
                    "ashmem-shim: fallocate(%d, ..., %ld, %ld) failed: %s\n",
                    fd, (long)offset, (long)len, strerror(err));
            return -1;
        }
        return 0;
    }

    return syscall(SYS_fallocate, fd, mode, offset, len);
}

/*
 * Intercept fstat to report the logical size for ashmem fds.
 * Some Wayland clients check the buffer size via fstat after resize.
 */
int __fxstat(int ver, int fd, struct stat *buf) {
    static int (*real_fxstat)(int, int, struct stat *) = NULL;
    if (!real_fxstat)
        real_fxstat = dlsym(RTLD_NEXT, "__fxstat");

    int ret = real_fxstat ? real_fxstat(ver, fd, buf) : syscall(SYS_fstat, fd, buf);

    if (ret == 0 && fd >= 0 && fd < MAX_FD_TRACK) {
        pthread_mutex_lock(&fd_lock);
        if (fd_info[fd].is_ashmem && fd_info[fd].logical_size > 0)
            buf->st_size = fd_info[fd].logical_size;
        pthread_mutex_unlock(&fd_lock);
    }
    return ret;
}

int fstat(int fd, struct stat *buf) {
    /* Try syscall directly (aarch64 glibc may not use __fxstat) */
    int ret = syscall(SYS_fstat, fd, buf);

    if (ret == 0 && fd >= 0 && fd < MAX_FD_TRACK) {
        pthread_mutex_lock(&fd_lock);
        if (fd_info[fd].is_ashmem && fd_info[fd].logical_size > 0)
            buf->st_size = fd_info[fd].logical_size;
        pthread_mutex_unlock(&fd_lock);
    }
    return ret;
}

/*
 * Intercept close to clean up our fd tracking.
 */
int close(int fd) {
    fd_clear_ashmem(fd);

    static int (*real_close)(int) = NULL;
    if (!real_close) {
        real_close = dlsym(RTLD_NEXT, "close");
    }
    return real_close(fd);
}
