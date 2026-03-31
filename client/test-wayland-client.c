/**
 * Minimal Wayland client that tests the tawc_buffer_v1 protocol.
 *
 * Uses libhybris to load AHB functions from Android's libnativewindow.so.
 *
 * Build in chroot:
 *   wayland-scanner client-header <xdg-shell.xml> xdg-shell-client.h
 *   wayland-scanner private-code <xdg-shell.xml> xdg-shell-client.c
 *   wayland-scanner client-header tawc_buffer_v1.xml tawc-buffer-v1-client.h
 *   wayland-scanner private-code tawc_buffer_v1.xml tawc-buffer-v1-client.c
 *   gcc -o test-wayland-client test-wayland-client.c xdg-shell-client.c \
 *       tawc-buffer-v1-client.c -lwayland-client -lhybris-common -I.
 *
 * Run:
 *   HYBRIS_PATCH_TLS=1 WAYLAND_DISPLAY=/tmp/tawc-wayland ./test-wayland-client
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <dlfcn.h>
#include <wayland-client.h>

#include "xdg-shell-client.h"
#include "tawc-buffer-v1-client.h"

/* Load libhybris at runtime to avoid TLS patcher conflicts with libwayland */
typedef void *(*android_dlopen_t)(const char *, int);
typedef void *(*android_dlsym_t)(void *, const char *);
static android_dlopen_t my_android_dlopen;
static android_dlsym_t my_android_dlsym;

/* AHB types (inline, no NDK headers needed) */
typedef struct AHardwareBuffer AHardwareBuffer;

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    uint32_t stride;
    uint32_t rfu0;
    uint64_t rfu1;
} AHardwareBuffer_Desc;

#define AHARDWAREBUFFER_FORMAT_RGBA8  1
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED  (1ULL << 8)
#define AHARDWAREBUFFER_USAGE_GPU_COLOR    (1ULL << 9)
#define AHARDWAREBUFFER_USAGE_CPU_WRITE    (1ULL << 5)

/* AHB function pointers */
typedef int  (*AHB_allocate_t)(const AHardwareBuffer_Desc *, AHardwareBuffer **);
typedef void (*AHB_release_t)(AHardwareBuffer *);
typedef void (*AHB_describe_t)(const AHardwareBuffer *, AHardwareBuffer_Desc *);
typedef int  (*AHB_lock_t)(AHardwareBuffer *, uint64_t usage, int32_t fence, const void *rect, void **out);
typedef int  (*AHB_unlock_t)(AHardwareBuffer *, int32_t *fence);
typedef int  (*AHB_sendHandle_t)(const AHardwareBuffer *, int fd);

static AHB_allocate_t   ahb_alloc;
static AHB_release_t    ahb_release;
static AHB_describe_t   ahb_describe;
static AHB_lock_t       ahb_lock;
static AHB_unlock_t     ahb_unlock;
static AHB_sendHandle_t ahb_send;

/* Globals */
static struct wl_display *display;
static struct wl_compositor *compositor;
static struct xdg_wm_base *xdg_wm_base_global;
static struct tawc_buffer_manager_v1 *buffer_manager;

static struct wl_surface *wl_surf;
static struct xdg_surface *xdg_surf;
static struct xdg_toplevel *toplevel;
static struct tawc_ahb_channel_v1 *ahb_channel;

static int side_channel_fd = -1;
static int configured = 0;
static int running = 1;

static void die(const char *msg)
{
    fprintf(stderr, "FATAL: %s\n", msg);
    exit(1);
}

/* --- xdg_wm_base listener --- */

static void wm_base_ping(void *data, struct xdg_wm_base *wm, uint32_t serial)
{
    xdg_wm_base_pong(wm, serial);
}

static const struct xdg_wm_base_listener wm_base_listener = {
    .ping = wm_base_ping,
};

/* --- xdg_surface listener --- */

static void xdg_surface_handle_configure(void *data, struct xdg_surface *surf,
                                         uint32_t serial)
{
    xdg_surface_ack_configure(surf, serial);
    configured = 1;
    printf("xdg_surface configured (serial %u)\n", serial);
}

static const struct xdg_surface_listener xdg_surface_listener = {
    .configure = xdg_surface_handle_configure,
};

/* --- xdg_toplevel listener --- */

static void toplevel_configure(void *data, struct xdg_toplevel *tl,
                               int32_t w, int32_t h, struct wl_array *states)
{
    printf("xdg_toplevel configure: %dx%d\n", w, h);
}

static void toplevel_close(void *data, struct xdg_toplevel *tl)
{
    printf("Toplevel close\n");
    running = 0;
}

static void toplevel_configure_bounds(void *data, struct xdg_toplevel *tl,
                                      int32_t w, int32_t h) {}
static void toplevel_wm_capabilities(void *data, struct xdg_toplevel *tl,
                                     struct wl_array *caps) {}

static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = toplevel_configure,
    .close = toplevel_close,
    .configure_bounds = toplevel_configure_bounds,
    .wm_capabilities = toplevel_wm_capabilities,
};

/* --- tawc_ahb_channel_v1 listener --- */

static void channel_fd(void *data, struct tawc_ahb_channel_v1 *ch, int32_t fd)
{
    side_channel_fd = fd;
    printf("Received side-channel fd: %d\n", fd);
}

static void channel_release(void *data, struct tawc_ahb_channel_v1 *ch)
{
    printf("Buffer released by compositor\n");
}

static const struct tawc_ahb_channel_v1_listener channel_listener = {
    .channel_fd = channel_fd,
    .release = channel_release,
};

/* --- wl_registry listener --- */

static void registry_global(void *data, struct wl_registry *reg,
                            uint32_t name, const char *iface, uint32_t ver)
{
    printf("Global: %s v%u\n", iface, ver);

    if (strcmp(iface, "wl_compositor") == 0)
        compositor = wl_registry_bind(reg, name, &wl_compositor_interface, 4);
    else if (strcmp(iface, "xdg_wm_base") == 0) {
        xdg_wm_base_global = wl_registry_bind(reg, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(xdg_wm_base_global, &wm_base_listener, NULL);
    } else if (strcmp(iface, "tawc_buffer_manager_v1") == 0)
        buffer_manager = wl_registry_bind(reg, name,
                                          &tawc_buffer_manager_v1_interface, 1);
}

static void registry_remove(void *data, struct wl_registry *reg, uint32_t name) {}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_remove,
};

/* --- AHB helper --- */

static AHardwareBuffer *create_test_ahb(int w, int h)
{
    AHardwareBuffer_Desc desc = {
        .width = w, .height = h, .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_RGBA8,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED
               | AHARDWAREBUFFER_USAGE_GPU_COLOR
               | AHARDWAREBUFFER_USAGE_CPU_WRITE,
    };

    AHardwareBuffer *buf = NULL;
    if (ahb_alloc(&desc, &buf) != 0) {
        fprintf(stderr, "AHB allocate failed\n");
        return NULL;
    }

    /* Fill with green/yellow checkerboard */
    void *data = NULL;
    if (ahb_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE, -1, NULL, &data) != 0) {
        fprintf(stderr, "AHB lock failed\n");
        ahb_release(buf);
        return NULL;
    }

    AHardwareBuffer_Desc actual;
    ahb_describe(buf, &actual);

    uint32_t *px = (uint32_t *)data;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            int c = ((x / 32) + (y / 32)) % 2;
            px[y * actual.stride + x] = c ? 0xFF00FF00 : 0xFF00FFFF;
        }

    ahb_unlock(buf, NULL);
    printf("AHB created: %dx%d\n", w, h);
    return buf;
}

/* --- Main --- */

int main(void)
{
    const int W = 256, H = 256;

    fprintf(stderr, "test-wayland-client starting\n");

    /* Connect to Wayland first (before libhybris, to avoid TLS conflicts) */
    fprintf(stderr, "connecting...\n");
    display = wl_display_connect(NULL);
    fprintf(stderr, "connect returned %p\n", (void*)display);
    if (!display) die("Failed to connect to Wayland display");
    fprintf(stderr, "Connected to Wayland\n");

    struct wl_registry *reg = wl_display_get_registry(display);
    fprintf(stderr, "registry=%p\n", (void*)reg);
    wl_registry_add_listener(reg, &registry_listener, NULL);
    fprintf(stderr, "pre roundtrip\n");
    wl_display_roundtrip(display);
    fprintf(stderr, "post roundtrip\n");

    fprintf(stderr, "compositor=%p xdg_wm_base=%p buffer_manager=%p\n",
            (void*)compositor, (void*)xdg_wm_base_global, (void*)buffer_manager);
    if (!compositor) die("No wl_compositor");
    if (!xdg_wm_base_global) die("No xdg_wm_base");
    if (!buffer_manager) die("No tawc_buffer_manager_v1");
    fprintf(stderr, "All globals bound\n");

    /* Create surface + xdg toplevel */
    wl_surf = wl_compositor_create_surface(compositor);
    fprintf(stderr, "surface=%p\n", (void*)wl_surf);
    xdg_surf = xdg_wm_base_get_xdg_surface(xdg_wm_base_global, wl_surf);
    fprintf(stderr, "xdg_surf=%p\n", (void*)xdg_surf);
    xdg_surface_add_listener(xdg_surf, &xdg_surface_listener, NULL);
    toplevel = xdg_surface_get_toplevel(xdg_surf);
    fprintf(stderr, "toplevel=%p\n", (void*)toplevel);
    xdg_toplevel_add_listener(toplevel, &toplevel_listener, NULL);
    fprintf(stderr, "toplevel listener set\n");
    xdg_toplevel_set_title(toplevel, "tawc test");
    xdg_toplevel_set_app_id(toplevel, "tawc-test");
    wl_surface_commit(wl_surf);
    fprintf(stderr, "initial commit done\n");

    /* Get AHB channel for our surface */
    ahb_channel = tawc_buffer_manager_v1_get_channel(buffer_manager, wl_surf);
    fprintf(stderr, "ahb_channel=%p\n", (void*)ahb_channel);
    tawc_ahb_channel_v1_add_listener(ahb_channel, &channel_listener, NULL);
    fprintf(stderr, "waiting for configure + side channel...\n");

    /* Wait for configure + side channel fd */
    while ((!configured || side_channel_fd < 0) && running) {
        fprintf(stderr, "dispatch loop: configured=%d side_channel_fd=%d\n", configured, side_channel_fd);
        if (wl_display_dispatch(display) < 0) die("dispatch failed");
    }

    if (!running) return 0;
    printf("Ready: configured=%d, side_channel_fd=%d\n", configured, side_channel_fd);

    /* Now load libhybris (after Wayland is set up) */
    printf("Loading libhybris...\n");
    void *hybris = dlopen("/usr/local/lib/libhybris-common.so", RTLD_LAZY);
    if (!hybris) { fprintf(stderr, "dlopen libhybris: %s\n", dlerror()); return 1; }
    my_android_dlopen = (android_dlopen_t)dlsym(hybris, "android_dlopen");
    my_android_dlsym  = (android_dlsym_t)dlsym(hybris, "android_dlsym");
    if (!my_android_dlopen || !my_android_dlsym)
        die("Missing android_dlopen/android_dlsym");
    printf("libhybris loaded\n");

    /* Load AHB functions via libhybris */
    void *nw = my_android_dlopen("/system/lib64/libnativewindow.so", 2);
    if (!nw) die("Failed to load libnativewindow.so via libhybris");
    ahb_alloc    = (AHB_allocate_t)  my_android_dlsym(nw, "AHardwareBuffer_allocate");
    ahb_release  = (AHB_release_t)   my_android_dlsym(nw, "AHardwareBuffer_release");
    ahb_describe = (AHB_describe_t)  my_android_dlsym(nw, "AHardwareBuffer_describe");
    ahb_lock     = (AHB_lock_t)      my_android_dlsym(nw, "AHardwareBuffer_lock");
    ahb_unlock   = (AHB_unlock_t)    my_android_dlsym(nw, "AHardwareBuffer_unlock");
    ahb_send     = (AHB_sendHandle_t)my_android_dlsym(nw, "AHardwareBuffer_sendHandleToUnixSocket");
    if (!ahb_alloc || !ahb_release || !ahb_describe || !ahb_lock || !ahb_unlock || !ahb_send)
        die("Missing AHB functions");
    printf("AHB functions loaded\n");

    /* Create AHB, fill, send */
    AHardwareBuffer *ahb = create_test_ahb(W, H);
    if (!ahb) return 1;

    if (ahb_send(ahb, side_channel_fd) != 0)
        die("AHB send failed");
    printf("AHB sent on side channel\n");

    /* Tell compositor about the buffer + commit */
    tawc_ahb_channel_v1_attach(ahb_channel, W, H);
    wl_surface_commit(wl_surf);
    printf("Surface committed\n");

    /* Event loop -- keep buffer alive */
    while (running)
        if (wl_display_dispatch(display) < 0) break;

    /* Cleanup */
    ahb_release(ahb);
    tawc_ahb_channel_v1_destroy(ahb_channel);
    tawc_buffer_manager_v1_destroy(buffer_manager);
    xdg_toplevel_destroy(toplevel);
    xdg_surface_destroy(xdg_surf);
    wl_surface_destroy(wl_surf);
    wl_display_disconnect(display);
    printf("Done\n");
    return 0;
}
