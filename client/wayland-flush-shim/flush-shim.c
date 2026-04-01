/* LD_PRELOAD shim that flushes the Wayland output buffer after reads.
 *
 * Firefox uses libmozwayland.so which has its own Wayland client
 * implementation that calls recvmsg() directly. It doesn't call
 * wl_display_flush() after reading events, leaving outbound messages
 * (like wl_surface.attach + commit) stuck in the output buffer.
 *
 * This shim wraps recvmsg() to call wl_display_flush() via the
 * system libwayland-client.so for ALL Wayland-connected fds.
 *
 * Build in chroot:
 *   gcc -shared -fPIC -o libwayland-flush-shim.so flush-shim.c -ldl -lwayland-client -Wall
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/socket.h>
#include <wayland-client.h>
#include <stdio.h>

static struct wl_display *wl_dpy = NULL;

/* Hook wl_display_connect to capture the display pointer */
struct wl_display *wl_display_connect(const char *name) {
    struct wl_display *(*real_connect)(const char *) = dlsym(RTLD_NEXT, "wl_display_connect");
    wl_dpy = real_connect(name);
    if (wl_dpy) {
        fprintf(stderr, "flush-shim: captured wl_display %p\n", wl_dpy);
    }
    return wl_dpy;
}

/* Hook wl_display_connect_to_fd */
struct wl_display *wl_display_connect_to_fd(int fd) {
    struct wl_display *(*real_connect_fd)(int) = dlsym(RTLD_NEXT, "wl_display_connect_to_fd");
    wl_dpy = real_connect_fd(fd);
    return wl_dpy;
}

/* Background thread that periodically flushes the Wayland output buffer.
 * Uses libmozwayland.so's own wl_display_flush via dlsym(RTLD_DEFAULT).
 * This ensures struct compatibility since the same library manages the display. */
#include <pthread.h>
#include <unistd.h>

static void *flush_thread(void *arg) {
    (void)arg;
    /* Wait for the display to be connected */
    while (!wl_dpy) usleep(100000);

    fprintf(stderr, "flush-shim: flush thread started, display=%p\n", wl_dpy);

    /* Resolve wl_display_flush from the default search order (libmozwayland.so) */
    int (*moz_flush)(struct wl_display *) = dlsym(RTLD_DEFAULT, "wl_display_flush");
    if (!moz_flush) {
        fprintf(stderr, "flush-shim: can't find wl_display_flush!\n");
        return NULL;
    }
    fprintf(stderr, "flush-shim: wl_display_flush at %p\n", moz_flush);

    /* Periodically flush */
    while (1) {
        usleep(100000); /* 100ms */
        if (wl_dpy) {
            moz_flush(wl_dpy);
        }
    }
    return NULL;
}

/* Start the flush thread when the library is loaded */
__attribute__((constructor))
static void init(void) {
    pthread_t t;
    pthread_create(&t, NULL, flush_thread, NULL);
    pthread_detach(t);
    fprintf(stderr, "flush-shim: init, flush thread created\n");
}
