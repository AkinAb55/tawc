/**
 * tawc EGL wrapper -- intercepts Wayland EGL calls and routes buffer sharing
 * through the tawc_buffer_v1 protocol + AHardwareBuffer side channel.
 *
 * This is a drop-in libEGL.so replacement. Apps load it via LD_LIBRARY_PATH.
 * It loads the real stock Android EGL driver via libhybris and passes through
 * all calls except the Wayland-specific ones it intercepts.
 *
 * Intercepted:
 *   eglGetDisplay / eglGetPlatformDisplay -- detect Wayland, init stock driver
 *   eglCreateWindowSurface -- allocate AHB pool, create FBOs
 *   eglSwapBuffers -- send AHB to compositor, rotate buffers
 *   eglDestroySurface -- cleanup
 *   eglMakeCurrent -- bind correct FBO
 *
 * Build in chroot:
 *   gcc -shared -fPIC -o libEGL.so tawc-egl.c tawc-buffer-v1-client.c \
 *       -lwayland-client -lhybris-common -lGLESv2 -I.
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <dlfcn.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <wayland-client.h>
#include <wayland-egl-backend.h>
#include <hybris/common/binding.h>

#include "tawc-buffer-v1-client.h"

/* ------------------------------------------------------------------ */
/* AHB types and function pointers (loaded via libhybris)              */
/* ------------------------------------------------------------------ */

typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct {
    uint32_t width, height, layers, format;
    uint64_t usage;
    uint32_t stride, rfu0;
    uint64_t rfu1;
} AHardwareBuffer_Desc;

#define AHB_FORMAT_RGBA8         1
#define AHB_USAGE_GPU_SAMPLED    (1ULL << 8)
#define AHB_USAGE_GPU_COLOR      (1ULL << 9)

typedef int  (*fn_ahb_allocate)(const AHardwareBuffer_Desc *, AHardwareBuffer **);
typedef void (*fn_ahb_release)(AHardwareBuffer *);
typedef void (*fn_ahb_describe)(const AHardwareBuffer *, AHardwareBuffer_Desc *);
typedef int  (*fn_ahb_send)(const AHardwareBuffer *, int fd);

static fn_ahb_allocate  ahb_allocate;
static fn_ahb_release   ahb_release;
static fn_ahb_describe  ahb_describe;
static fn_ahb_send      ahb_send;

/* ------------------------------------------------------------------ */
/* EGL extension function pointers                                     */
/* ------------------------------------------------------------------ */

typedef EGLClientBuffer (*fn_eglGetNativeClientBufferANDROID)(const void *);
typedef EGLImageKHR     (*fn_eglCreateImageKHR)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *);
typedef EGLBoolean      (*fn_eglDestroyImageKHR)(EGLDisplay, EGLImageKHR);

static fn_eglGetNativeClientBufferANDROID pfn_getClientBuffer;
static fn_eglCreateImageKHR              pfn_createImage;
static fn_eglDestroyImageKHR             pfn_destroyImage;

typedef void (*fn_glEGLImageTargetTexture2DOES)(GLenum, void *);
static fn_glEGLImageTargetTexture2DOES pfn_imageTargetTex;

#define EGL_NATIVE_BUFFER_ANDROID 0x3140

/* ------------------------------------------------------------------ */
/* Stock EGL function pointers (real driver via libhybris)             */
/* ------------------------------------------------------------------ */

static EGLDisplay  (*real_eglGetDisplay)(EGLNativeDisplayType);
static EGLBoolean  (*real_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
static EGLBoolean  (*real_eglTerminate)(EGLDisplay);
static EGLBoolean  (*real_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
static EGLBoolean  (*real_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
static EGLContext  (*real_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
static EGLBoolean  (*real_eglDestroyContext)(EGLDisplay, EGLContext);
static EGLSurface  (*real_eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
static EGLBoolean  (*real_eglDestroySurface)(EGLDisplay, EGLSurface);
static EGLBoolean  (*real_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
static EGLBoolean  (*real_eglSwapBuffers)(EGLDisplay, EGLSurface);
static EGLBoolean  (*real_eglBindAPI)(EGLenum);
static void *      (*real_eglGetProcAddress)(const char *);
static EGLint      (*real_eglGetError)(void);
static const char *(*real_eglQueryString)(EGLDisplay, EGLint);
static EGLBoolean  (*real_eglSwapInterval)(EGLDisplay, EGLint);
static EGLBoolean  (*real_eglReleaseThread)(void);
static EGLContext  (*real_eglGetCurrentContext)(void);
static EGLSurface  (*real_eglGetCurrentSurface)(EGLint);
static EGLDisplay  (*real_eglGetCurrentDisplay)(void);
static EGLBoolean  (*real_eglQuerySurface)(EGLDisplay, EGLSurface, EGLint, EGLint *);
static EGLBoolean  (*real_eglWaitGL)(void);
static EGLBoolean  (*real_eglWaitNative)(EGLint);
static EGLBoolean  (*real_eglGetConfigs)(EGLDisplay, EGLConfig *, EGLint, EGLint *);

/* ------------------------------------------------------------------ */
/* Buffer pool for a Wayland surface                                   */
/* ------------------------------------------------------------------ */

#define NUM_BUFFERS 2
#define GL_TEXTURE_EXTERNAL_OES 0x8D65

struct tawc_buffer {
    AHardwareBuffer *ahb;
    EGLImageKHR      image;
    GLuint           tex;
    GLuint           fbo;
    GLuint           depth_rb;
    int              width, height;
};

/* Our fake EGLSurface state */
struct tawc_surface {
    struct wl_surface             *wl_surf;
    struct tawc_ahb_channel_v1    *channel;
    int                            side_fd;
    struct tawc_buffer             buffers[NUM_BUFFERS];
    int                            current;
    int                            width, height;
    /* Real pbuffer surface (for context binding) */
    EGLSurface                     real_pbuffer;
};

#define MAX_SURFACES 16
static struct tawc_surface surfaces[MAX_SURFACES];
static int num_surfaces = 0;

/* ------------------------------------------------------------------ */
/* Global state                                                        */
/* ------------------------------------------------------------------ */

static int initialized = 0;
static EGLDisplay stock_display = EGL_NO_DISPLAY;
static struct wl_display *wayland_display = NULL;

/* tawc protocol objects */
static struct tawc_buffer_manager_v1 *buffer_manager = NULL;

static void log_msg(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[tawc-egl] ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    fflush(stderr);
    va_end(ap);
}

/* ------------------------------------------------------------------ */
/* tawc protocol listener                                              */
/* ------------------------------------------------------------------ */

static void channel_fd_handler(void *data, struct tawc_ahb_channel_v1 *ch, int32_t fd)
{
    struct tawc_surface *s = (struct tawc_surface *)data;
    s->side_fd = fd;
    log_msg("received side channel fd=%d", fd);
}

static void channel_release_handler(void *data, struct tawc_ahb_channel_v1 *ch)
{
    /* Buffer released -- we could track this for smarter buffer rotation */
}

static const struct tawc_ahb_channel_v1_listener channel_listener = {
    .channel_fd = channel_fd_handler,
    .release = channel_release_handler,
};

/* ------------------------------------------------------------------ */
/* Wayland registry -- bind tawc_buffer_manager_v1                     */
/* ------------------------------------------------------------------ */

static void registry_global(void *data, struct wl_registry *reg,
                            uint32_t name, const char *iface, uint32_t ver)
{
    if (strcmp(iface, "tawc_buffer_manager_v1") == 0) {
        buffer_manager = wl_registry_bind(reg, name,
                                          &tawc_buffer_manager_v1_interface, 1);
        log_msg("bound tawc_buffer_manager_v1");
    }
}

static void registry_global_remove(void *data, struct wl_registry *reg, uint32_t name) {}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

/* ------------------------------------------------------------------ */
/* Initialization                                                      */
/* ------------------------------------------------------------------ */

static int load_ahb_functions(void)
{
    void *nw = android_dlopen("/system/lib64/libnativewindow.so", 2);
    if (!nw) { log_msg("FATAL: can't load libnativewindow.so"); return -1; }

    ahb_allocate = (fn_ahb_allocate)android_dlsym(nw, "AHardwareBuffer_allocate");
    ahb_release  = (fn_ahb_release) android_dlsym(nw, "AHardwareBuffer_release");
    ahb_describe = (fn_ahb_describe)android_dlsym(nw, "AHardwareBuffer_describe");
    ahb_send     = (fn_ahb_send)    android_dlsym(nw, "AHardwareBuffer_sendHandleToUnixSocket");

    if (!ahb_allocate || !ahb_release || !ahb_describe || !ahb_send) {
        log_msg("FATAL: missing AHB functions");
        return -1;
    }
    return 0;
}

static int load_stock_egl(void)
{
    /* Load libhybris's EGL wrapper (glibc .so, not bionic) which in turn
     * loads the stock Android EGL driver. We use dlopen with the full path
     * to avoid loading ourselves recursively. */
    void *egl = dlopen("/usr/local/lib/libEGL.so.1.0.0", RTLD_NOW);
    if (!egl) { log_msg("FATAL: can't load libhybris EGL: %s", dlerror()); return -1; }

#define LOAD(name) do { \
    real_##name = (typeof(real_##name))dlsym(egl, #name); \
    if (!real_##name) { log_msg("missing: %s", #name); return -1; } \
} while(0)

    LOAD(eglGetDisplay);
    LOAD(eglInitialize);
    LOAD(eglTerminate);
    LOAD(eglChooseConfig);
    LOAD(eglGetConfigAttrib);
    LOAD(eglCreateContext);
    LOAD(eglDestroyContext);
    LOAD(eglCreatePbufferSurface);
    LOAD(eglDestroySurface);
    LOAD(eglMakeCurrent);
    LOAD(eglSwapBuffers);
    LOAD(eglBindAPI);
    LOAD(eglGetProcAddress);
    LOAD(eglGetError);
    LOAD(eglQueryString);
    LOAD(eglSwapInterval);
    LOAD(eglReleaseThread);
    LOAD(eglGetCurrentContext);
    LOAD(eglGetCurrentSurface);
    LOAD(eglGetCurrentDisplay);
    LOAD(eglQuerySurface);
    LOAD(eglWaitGL);
    LOAD(eglWaitNative);
    LOAD(eglGetConfigs);
#undef LOAD

    return 0;
}

static int load_extensions(void)
{
    pfn_getClientBuffer = (fn_eglGetNativeClientBufferANDROID)
        real_eglGetProcAddress("eglGetNativeClientBufferANDROID");
    pfn_createImage = (fn_eglCreateImageKHR)
        real_eglGetProcAddress("eglCreateImageKHR");
    pfn_destroyImage = (fn_eglDestroyImageKHR)
        real_eglGetProcAddress("eglDestroyImageKHR");
    pfn_imageTargetTex = (fn_glEGLImageTargetTexture2DOES)
        real_eglGetProcAddress("glEGLImageTargetTexture2DOES");

    if (!pfn_getClientBuffer || !pfn_createImage || !pfn_imageTargetTex) {
        log_msg("FATAL: missing EGL/GL extensions");
        return -1;
    }
    return 0;
}

static int init_once(void)
{
    if (initialized) return 0;

    log_msg("initializing tawc EGL wrapper");

    if (load_stock_egl() < 0) return -1;

    /* Initialize stock EGL with default display */
    real_eglBindAPI(EGL_OPENGL_ES_API);
    stock_display = real_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (stock_display == EGL_NO_DISPLAY) {
        log_msg("FATAL: stock eglGetDisplay failed: 0x%x", real_eglGetError());
        return -1;
    }
    if (!real_eglInitialize(stock_display, NULL, NULL)) {
        log_msg("FATAL: stock eglInitialize failed: 0x%x", real_eglGetError());
        return -1;
    }
    if (load_extensions() < 0) return -1;

    log_msg("stock EGL initialized, display=%p", stock_display);
    initialized = 1;
    return 0;
}

/* ------------------------------------------------------------------ */
/* Buffer allocation helpers                                           */
/* ------------------------------------------------------------------ */

static int ensure_ahb_loaded(void)
{
    if (ahb_allocate) return 0;  /* already loaded */
    return load_ahb_functions();
}

static int alloc_buffer(struct tawc_buffer *buf, int w, int h)
{
    if (ensure_ahb_loaded() < 0) return -1;
    AHardwareBuffer_Desc desc = {
        .width = w, .height = h, .layers = 1,
        .format = AHB_FORMAT_RGBA8,
        .usage = AHB_USAGE_GPU_SAMPLED | AHB_USAGE_GPU_COLOR,
    };

    if (ahb_allocate(&desc, &buf->ahb) != 0) {
        log_msg("AHB allocate %dx%d failed", w, h);
        return -1;
    }

    /* Create EGLImage from AHB */
    EGLClientBuffer cb = pfn_getClientBuffer(buf->ahb);
    if (!cb) { log_msg("getClientBuffer failed"); return -1; }

    buf->image = pfn_createImage(stock_display, EGL_NO_CONTEXT,
                                 EGL_NATIVE_BUFFER_ANDROID, cb, NULL);
    if (buf->image == EGL_NO_IMAGE_KHR) {
        log_msg("createImage failed: 0x%x", real_eglGetError());
        return -1;
    }

    /* Create texture + FBO */
    glGenTextures(1, &buf->tex);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, buf->tex);
    pfn_imageTargetTex(GL_TEXTURE_EXTERNAL_OES, buf->image);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glGenFramebuffers(1, &buf->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, buf->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_EXTERNAL_OES, buf->tex, 0);

    /* Depth/stencil renderbuffer */
    glGenRenderbuffers(1, &buf->depth_rb);
    glBindRenderbuffer(GL_RENDERBUFFER, buf->depth_rb);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8_OES, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                              GL_RENDERBUFFER, buf->depth_rb);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                              GL_RENDERBUFFER, buf->depth_rb);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        log_msg("FBO incomplete: 0x%x", status);
        return -1;
    }

    buf->width = w;
    buf->height = h;
    log_msg("buffer allocated: %dx%d fbo=%u tex=%u", w, h, buf->fbo, buf->tex);
    return 0;
}

static void free_buffer(struct tawc_buffer *buf)
{
    if (buf->fbo) glDeleteFramebuffers(1, &buf->fbo);
    if (buf->depth_rb) glDeleteRenderbuffers(1, &buf->depth_rb);
    if (buf->tex) glDeleteTextures(1, &buf->tex);
    if (buf->image) pfn_destroyImage(stock_display, buf->image);
    if (buf->ahb) ahb_release(buf->ahb);
    memset(buf, 0, sizeof(*buf));
}

/* ------------------------------------------------------------------ */
/* EGL API implementation                                              */
/* ------------------------------------------------------------------ */

EGLDisplay eglGetDisplay(EGLNativeDisplayType display_id)
{
    /* If given a wayland display, do protocol binding FIRST (before any
     * libhybris/bionic initialization, since TLS patching deadlocks
     * wl_display_roundtrip_queue) */
    if (display_id != EGL_DEFAULT_DISPLAY && !wayland_display) {
        /* Assume it's a wl_display* */
        wayland_display = (struct wl_display *)display_id;
        log_msg("eglGetDisplay: wayland display %p", wayland_display);

        /* Use a private event queue so our roundtrip doesn't interfere
         * with the app's event dispatch */
        log_msg("binding tawc protocol (non-blocking)...");
        /* Don't do a blocking roundtrip here -- eglGetDisplay may be called
         * from inside wl_display_dispatch (e.g., from a registry callback),
         * which would deadlock. Instead, just register our listener and let
         * the app's event loop dispatch it. We'll check for buffer_manager
         * when we actually need it (in eglCreateWindowSurface). */
        struct wl_registry *reg = wl_display_get_registry(wayland_display);
        wl_registry_add_listener(reg, &registry_listener, NULL);
        wl_display_flush(wayland_display);
        /* Try a non-blocking dispatch to pick up any already-available events */
        wl_display_dispatch_pending(wayland_display);
        log_msg("buffer_manager after pending dispatch: %p", (void*)buffer_manager);
    }

    /* Now do the heavy init (loads bionic libs via libhybris) */
    if (init_once() < 0) return EGL_NO_DISPLAY;

    return stock_display;
}

EGLDisplay eglGetPlatformDisplay(EGLenum platform, void *native_display,
                                 const EGLAttrib *attrib_list)
{
    /* Treat WAYLAND platform like eglGetDisplay with wl_display */
    return eglGetDisplay((EGLNativeDisplayType)native_display);
}

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor)
{
    /* Already initialized in init_once */
    if (major) *major = 1;
    if (minor) *minor = 5;
    return EGL_TRUE;
}

EGLBoolean eglTerminate(EGLDisplay dpy)
{
    return real_eglTerminate(dpy);
}

EGLBoolean eglBindAPI(EGLenum api)
{
    /* Don't init_once here -- it loads libhybris which blocks roundtrip.
     * Defer until eglGetDisplay is called. Just remember the API. */
    if (initialized)
        return real_eglBindAPI(api);
    return EGL_TRUE;  /* will be called again after init */
}

EGLBoolean eglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list,
                           EGLConfig *configs, EGLint config_size, EGLint *num_config)
{
    if (init_once() < 0) return EGL_FALSE;

    /* Modify attribs: replace WINDOW_BIT with PBUFFER_BIT since we use FBOs */
    EGLint modified[64];
    int i = 0, j = 0;
    if (attrib_list) {
        while (attrib_list[i] != EGL_NONE && j < 60) {
            if (attrib_list[i] == EGL_SURFACE_TYPE) {
                modified[j++] = EGL_SURFACE_TYPE;
                modified[j++] = EGL_PBUFFER_BIT;
                i += 2;
            } else {
                modified[j++] = attrib_list[i++];
                modified[j++] = attrib_list[i++];
            }
        }
    }
    modified[j] = EGL_NONE;

    return real_eglChooseConfig(stock_display, modified, configs, config_size, num_config);
}

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config, EGLContext share,
                            const EGLint *attribs)
{
    if (init_once() < 0) return EGL_NO_CONTEXT;
    return real_eglCreateContext(stock_display, config, share, attribs);
}

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx)
{
    return real_eglDestroyContext(stock_display, ctx);
}

EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config,
                                   EGLNativeWindowType win, const EGLint *attribs)
{
    if (init_once() < 0) return EGL_NO_SURFACE;

    struct wl_egl_window *wl_win = (struct wl_egl_window *)win;
    int w = wl_win->width;
    int h = wl_win->height;
    struct wl_surface *wl_surf = wl_win->surface;

    log_msg("eglCreateWindowSurface: %dx%d surface=%p", w, h, wl_surf);

    /* Ensure buffer_manager is bound (lazy binding from eglGetDisplay) */
    if (!buffer_manager && wayland_display) {
        log_msg("buffer_manager not yet bound, doing roundtrip...");
        wl_display_roundtrip(wayland_display);
        log_msg("buffer_manager after roundtrip: %p", (void*)buffer_manager);
    }

    if (num_surfaces >= MAX_SURFACES) {
        log_msg("too many surfaces");
        return EGL_NO_SURFACE;
    }

    struct tawc_surface *ts = &surfaces[num_surfaces];
    memset(ts, 0, sizeof(*ts));
    ts->wl_surf = wl_surf;
    ts->width = w;
    ts->height = h;
    ts->side_fd = -1;
    ts->current = 0;

    /* Create a real pbuffer for MakeCurrent (1x1, just for context binding) */
    EGLint pb_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    ts->real_pbuffer = real_eglCreatePbufferSurface(stock_display, config, pb_attrs);
    if (ts->real_pbuffer == EGL_NO_SURFACE) {
        log_msg("pbuffer creation failed");
        return EGL_NO_SURFACE;
    }

    /* Request AHB channel from compositor */
    if (buffer_manager) {
        ts->channel = tawc_buffer_manager_v1_get_channel(buffer_manager, wl_surf);
        tawc_ahb_channel_v1_add_listener(ts->channel, &channel_listener, ts);

        /* Roundtrip to get the side channel fd.
         * eglCreateWindowSurface is called after the app's init roundtrips,
         * so a blocking roundtrip here should be safe. */
        wl_display_roundtrip(wayland_display);

        if (ts->side_fd < 0) {
            log_msg("WARNING: didn't receive side channel fd");
        } else {
            log_msg("got side channel fd=%d for surface %p", ts->side_fd, wl_surf);
        }
    }

    /* We'll allocate buffers lazily on first MakeCurrent (need GL context) */
    num_surfaces++;

    /* Return a fake EGLSurface -- pointer to our tawc_surface */
    return (EGLSurface)ts;
}

EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config,
                                    const EGLint *attribs)
{
    if (init_once() < 0) return EGL_NO_SURFACE;
    return real_eglCreatePbufferSurface(stock_display, config, attribs);
}

EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface)
{
    /* Check if it's one of our tawc surfaces */
    struct tawc_surface *ts = (struct tawc_surface *)surface;
    for (int i = 0; i < num_surfaces; i++) {
        if (&surfaces[i] == ts) {
            for (int j = 0; j < NUM_BUFFERS; j++)
                free_buffer(&ts->buffers[j]);
            if (ts->channel)
                tawc_ahb_channel_v1_destroy(ts->channel);
            if (ts->real_pbuffer)
                real_eglDestroySurface(stock_display, ts->real_pbuffer);
            log_msg("surface destroyed");
            return EGL_TRUE;
        }
    }
    return real_eglDestroySurface(stock_display, surface);
}

static struct tawc_surface *find_surface(EGLSurface surface)
{
    struct tawc_surface *ts = (struct tawc_surface *)surface;
    for (int i = 0; i < num_surfaces; i++)
        if (&surfaces[i] == ts) return ts;
    return NULL;
}

EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read,
                           EGLContext ctx)
{
    struct tawc_surface *ts = find_surface(draw);

    if (!ts) {
        /* Not our surface, pass through */
        return real_eglMakeCurrent(stock_display, draw, read, ctx);
    }

    /* Bind the real pbuffer to make the GL context current */
    if (!real_eglMakeCurrent(stock_display, ts->real_pbuffer,
                              ts->real_pbuffer, ctx)) {
        log_msg("MakeCurrent pbuffer failed");
        return EGL_FALSE;
    }

    /* Allocate buffers if not yet done (needs active GL context) */
    if (ts->buffers[0].fbo == 0) {
        log_msg("allocating %d buffers (%dx%d)", NUM_BUFFERS, ts->width, ts->height);
        for (int i = 0; i < NUM_BUFFERS; i++) {
            if (alloc_buffer(&ts->buffers[i], ts->width, ts->height) < 0) {
                log_msg("buffer alloc failed");
                return EGL_FALSE;
            }
        }
    }

    /* Bind current buffer's FBO */
    glBindFramebuffer(GL_FRAMEBUFFER, ts->buffers[ts->current].fbo);
    glViewport(0, 0, ts->width, ts->height);

    return EGL_TRUE;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface surface)
{
    struct tawc_surface *ts = find_surface(surface);
    if (!ts) return real_eglSwapBuffers(stock_display, surface);

    /* Flush GL commands */
    glFinish();

    struct tawc_buffer *buf = &ts->buffers[ts->current];

    /* Send AHB to compositor */
    if (ts->side_fd >= 0 && ts->channel && ahb_send) {
        if (ahb_send(buf->ahb, ts->side_fd) != 0) {
            log_msg("AHB send failed");
            return EGL_FALSE;
        }

        tawc_ahb_channel_v1_attach(ts->channel, buf->width, buf->height);
        wl_surface_commit(ts->wl_surf);
        wl_display_flush(wayland_display);
    }

    /* Rotate to next buffer */
    ts->current = (ts->current + 1) % NUM_BUFFERS;
    glBindFramebuffer(GL_FRAMEBUFFER, ts->buffers[ts->current].fbo);
    glViewport(0, 0, ts->width, ts->height);

    return EGL_TRUE;
}

/* ------------------------------------------------------------------ */
/* Pass-through functions                                              */
/* ------------------------------------------------------------------ */

EGLint eglGetError(void)
{
    if (!initialized) return EGL_SUCCESS;
    return real_eglGetError();
}

const char *eglQueryString(EGLDisplay dpy, EGLint name)
{
    if (name == EGL_CLIENT_APIS) return "OpenGL_ES";
    if (name == EGL_EXTENSIONS) return "EGL_KHR_platform_wayland EGL_EXT_platform_wayland";
    if (name == EGL_VERSION) return "1.5";
    if (name == EGL_VENDOR) return "tawc";
    if (!initialized) return "";
    return real_eglQueryString(stock_display, name);
}

void (*eglGetProcAddress(const char *procname))(void)
{
    /* Return our intercepted functions without triggering init */
    if (strcmp(procname, "eglGetPlatformDisplay") == 0)
        return (void(*)(void))eglGetPlatformDisplay;
    if (strcmp(procname, "eglCreatePlatformWindowSurface") == 0)
        return (void(*)(void))eglCreateWindowSurface;
    if (strcmp(procname, "eglGetPlatformDisplayEXT") == 0)
        return (void(*)(void))eglGetPlatformDisplay;
    if (strcmp(procname, "eglCreatePlatformWindowSurfaceEXT") == 0)
        return (void(*)(void))eglCreateWindowSurface;
    if (!initialized) {
        /* Can't forward yet, return NULL for unknown functions */
        return NULL;
    }
    return (void(*)(void))real_eglGetProcAddress(procname);
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval)
{
    return EGL_TRUE; /* ignore, we control frame pacing */
}

EGLBoolean eglReleaseThread(void)
{
    if (!initialized) return EGL_TRUE;
    return real_eglReleaseThread();
}

EGLContext eglGetCurrentContext(void)
{
    if (!initialized) return EGL_NO_CONTEXT;
    return real_eglGetCurrentContext();
}

EGLSurface eglGetCurrentSurface(EGLint readdraw)
{
    if (!initialized) return EGL_NO_SURFACE;
    return real_eglGetCurrentSurface(readdraw);
}

EGLDisplay eglGetCurrentDisplay(void)
{
    if (!initialized) return EGL_NO_DISPLAY;
    return stock_display;
}

EGLBoolean eglQuerySurface(EGLDisplay dpy, EGLSurface surface, EGLint attr, EGLint *value)
{
    struct tawc_surface *ts = find_surface(surface);
    if (ts) {
        switch (attr) {
            case EGL_WIDTH:  *value = ts->width;  return EGL_TRUE;
            case EGL_HEIGHT: *value = ts->height; return EGL_TRUE;
            default: return EGL_TRUE;
        }
    }
    return real_eglQuerySurface(stock_display, surface, attr, value);
}

EGLBoolean eglWaitGL(void)
{
    if (!initialized) return EGL_TRUE;
    return real_eglWaitGL();
}

EGLBoolean eglWaitNative(EGLint engine)
{
    if (!initialized) return EGL_TRUE;
    return real_eglWaitNative(engine);
}

EGLBoolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config, EGLint attr, EGLint *value)
{
    if (init_once() < 0) return EGL_FALSE;
    return real_eglGetConfigAttrib(stock_display, config, attr, value);
}

EGLBoolean eglGetConfigs(EGLDisplay dpy, EGLConfig *configs, EGLint config_size, EGLint *num_config)
{
    if (init_once() < 0) return EGL_FALSE;
    return real_eglGetConfigs(stock_display, configs, config_size, num_config);
}

EGLBoolean eglWaitClient(void) { return EGL_TRUE; }
EGLBoolean eglCopyBuffers(EGLDisplay d, EGLSurface s, EGLNativePixmapType t) { return EGL_FALSE; }
EGLSurface eglCreatePixmapSurface(EGLDisplay d, EGLConfig c, EGLNativePixmapType p, const EGLint *a) { return EGL_NO_SURFACE; }
EGLBoolean eglSurfaceAttrib(EGLDisplay d, EGLSurface s, EGLint a, EGLint v) { return EGL_TRUE; }
EGLImage eglCreateImage(EGLDisplay d, EGLContext c, EGLenum t, EGLClientBuffer b, const EGLAttrib *a) { return EGL_NO_IMAGE; }
EGLBoolean eglDestroyImage(EGLDisplay d, EGLImage i) { return EGL_TRUE; }
EGLSync eglCreateSync(EGLDisplay d, EGLenum t, const EGLAttrib *a) { return EGL_NO_SYNC; }
EGLBoolean eglDestroySync(EGLDisplay d, EGLSync s) { return EGL_TRUE; }
EGLint eglClientWaitSync(EGLDisplay d, EGLSync s, EGLint f, EGLTime t) { return EGL_CONDITION_SATISFIED; }
EGLBoolean eglWaitSync(EGLDisplay d, EGLSync s, EGLint f) { return EGL_TRUE; }
EGLSurface eglCreatePlatformWindowSurface(EGLDisplay d, EGLConfig c, void *w, const EGLAttrib *a) {
    return eglCreateWindowSurface(d, c, (EGLNativeWindowType)w, NULL);
}
EGLSurface eglCreatePlatformPixmapSurface(EGLDisplay d, EGLConfig c, void *p, const EGLAttrib *a) { return EGL_NO_SURFACE; }
