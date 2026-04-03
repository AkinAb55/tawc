/*
 * Minimal reproducer: intermittent SIGSEGV in glGenTextures via libhybris.
 *
 * When GTK3 libraries are loaded before EGL initialization, the first GL
 * call inside the tawc WSI wrapper's buffer allocation crashes ~15-20%.
 * Without GTK loaded: 0%.
 *
 * Requires the tawc compositor running (for Wayland socket), but does NOT
 * need any protocol interaction — uses tawc_create_test_surface() to skip
 * the compositor protocol exchange.
 *
 * Build (inside chroot):
 *   gcc -o repro repro.c -L/tmp/tawc-wsi -lEGL -lGLESv2 \
 *       -lwayland-client -ldl -Wall -g
 *
 * Run (compositor must be running):
 *   ./repro          # without GTK — should never crash
 *   ./repro gtk      # with GTK loaded first — crashes ~15-20%
 *
 * Crash rate test:
 *   for i in $(seq 1 50); do ./repro gtk 2>/dev/null && echo -n . || echo -n X; done; echo
 */

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <wayland-client.h>

/* Provided by tawc-egl WSI wrapper — creates a tawc_surface without
 * Wayland protocol exchange (no wl_compositor or tawc_buffer_manager needed) */
extern EGLSurface tawc_create_test_surface(EGLDisplay dpy, EGLConfig config,
                                            int width, int height);

static void die(const char *msg) { fprintf(stderr, "FATAL: %s\n", msg); exit(1); }

int main(int argc, char *argv[])
{
    if (argc > 1 && strcmp(argv[1], "gtk") == 0) {
        fprintf(stderr, "loading GTK3...\n");
        void *gtk = dlopen("libgtk-3.so.0", RTLD_NOW);
        if (!gtk) { fprintf(stderr, "can't load GTK3: %s\n", dlerror()); exit(1); }
        int (*init_check)(int*, char***) = dlsym(gtk, "gtk_init_check");
        if (init_check) { int c = 0; char **v = NULL; init_check(&c, &v); }
        fprintf(stderr, "GTK3 loaded\n");
    }

    /* Connect to Wayland — the crash requires the WSI wrapper's Wayland
     * code path in eglGetDisplay to execute (it does registry dispatch
     * which affects TLS/library state). A running compositor is needed
     * for the socket, but no protocol interaction happens. */
    struct wl_display *wl = wl_display_connect(NULL);
    if (!wl) die("wl_display_connect (is the tawc compositor running?)");

    /* EGL init with Wayland display — triggers WSI wrapper's Wayland path */
    EGLDisplay dpy = eglGetDisplay((EGLNativeDisplayType)wl);
    if (dpy == EGL_NO_DISPLAY) die("eglGetDisplay");
    if (!eglInitialize(dpy, NULL, NULL)) die("eglInitialize");

    EGLint attrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLConfig config; EGLint num;
    if (!eglChooseConfig(dpy, attrs, &config, 1, &num) || num == 0) die("eglChooseConfig");

    EGLint ctx_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, ctx_attrs);
    if (ctx == EGL_NO_CONTEXT) die("eglCreateContext");

    /* Test surface — no compositor protocol needed */
    EGLSurface surf = tawc_create_test_surface(dpy, config, 1080, 2400);
    if (surf == EGL_NO_SURFACE) die("tawc_create_test_surface");

    /* eglMakeCurrent → alloc_buffer → glGenTextures — crash site */
    fprintf(stderr, "calling eglMakeCurrent...\n");
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) die("eglMakeCurrent");

    GLuint tex = 0;
    glGenTextures(1, &tex);
    fprintf(stderr, "OK tex=%u\n", tex);
    return 0;
}
