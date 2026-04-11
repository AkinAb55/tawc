/**
 * libGL.so shim -- GLX stubs + GLES forwarding via DT_NEEDED.
 *
 * Firefox resolves GL symbols via dlsym("libGL.so", "glFoo"). GTK/libepoxy
 * probes libGL.so for glXGetCurrentContext to detect GLX vs EGL.
 *
 * This shim:
 *   - Exports GLX stubs (return NULL) so libepoxy detects "no GLX, use EGL"
 *   - Links against libGL.so.1 (DT_NEEDED) which is our GLES symlink, so
 *     dlsym(handle, "glBindTexture") finds GLES symbols via the dep chain
 *
 * Build (in chroot):
 *   gcc -shared -fPIC -o libGL.so libgl-shim.c \
 *       -L/tmp/tawc-wsi -l:libGL.so.1 -Wl,-rpath,/tmp/tawc-wsi -Wl,--no-as-needed
 */

#include <stddef.h>

/* GLX stubs -- libepoxy probes these to detect active GLX context.
 * Returning NULL means "no GLX context", causing fallthrough to EGL. */
void *glXGetCurrentContext(void)            { return NULL; }
void *glXGetCurrentDisplay(void)            { return NULL; }
void *glXGetProcAddress(const char *name)   { return NULL; }
void *glXGetProcAddressARB(const char *name) { return NULL; }
