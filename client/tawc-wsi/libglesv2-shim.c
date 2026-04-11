/**
 * libGLESv2.so shim -- adds GLX stubs to the real GLES library.
 *
 * libepoxy (used by GTK) does dlsym(libGLESv2_handle, "glXGetCurrentContext")
 * to detect GLX vs EGL. On GLES-only Android, the real GLES library doesn't
 * have GLX symbols, and libepoxy's error handling calls abort().
 *
 * This shim replaces libGLESv2.so.2 in LD_LIBRARY_PATH. It:
 *   1. Exports GLX stubs returning NULL (no GLX -> use EGL)
 *   2. Has DT_NEEDED on libGLESv2_real.so (the real libhybris GLES, with
 *      soname patched via patchelf to break the naming cycle)
 *
 * This way dlsym(shim_handle, "glFoo") finds GLES symbols in the dep chain,
 * and dlsym(shim_handle, "glXGetCurrentContext") finds our stub.
 *
 * Build: gcc -shared -fPIC -o libGLESv2.so.2 libglesv2-shim.c \
 *            -L/tmp/tawc-wsi -l:libGLESv2_real.so \
 *            -Wl,-rpath,/tmp/tawc-wsi -Wl,--no-as-needed \
 *            -Wl,-soname,libGLESv2.so.2
 */

#include <stddef.h>

/* GLX stubs -- libepoxy probes these to detect active GLX context.
 * Returning NULL means "no GLX context", causing fallthrough to EGL. */
void *glXGetCurrentContext(void)             { return NULL; }
void *glXGetCurrentDisplay(void)             { return NULL; }
void *glXGetProcAddress(const char *name)    { return NULL; }
void *glXGetProcAddressARB(const char *name) { return NULL; }
