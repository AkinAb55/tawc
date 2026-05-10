// Regression test for the libhybris TLS-module unregister assertion.
//
// Before the lazy-promote-to-static fix in our libhybris fork, every
// hybris_dlopen of a TLS-using bionic .so would reserve a static-TLS
// slot (because libhybris has no executable, so linker_main() never
// runs and linker_finalize_static_tls() is never called); the matching
// hybris_dlclose then aborted with:
//
//   linker_tls.cpp:93: unregister_tls_module CHECK
//   'mod.static_offset == SIZE_MAX' failed
//
// First seen in the wild on Pixel Fold while launching lxterminal —
// Mesa/GTK pulled in a TLS-using vendor lib that subsequently got
// dlclose'd. Reduced here to a 30-line repro that reproduces the
// abort on any libhybris-supported device.
//
// Pass: clean exit 0 with hybris_dlclose returning 0.
// Fail (unfixed libhybris): SIGABRT (exit 134) with the CHECK above.
#include <stdio.h>

extern void* hybris_dlopen(const char* filename, int flag);
extern void* hybris_dlsym(void* handle, const char* symbol);
extern int   hybris_dlclose(void* handle);
extern char* hybris_dlerror(void);

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <path-to-bionic-tls-lib.so>\n", argv[0]);
        return 2;
    }
    const char* path = argv[1];

    fprintf(stderr, "[repro] hybris_dlopen(%s)\n", path);
    void* h = hybris_dlopen(path, 2 /* RTLD_NOW */);
    if (!h) {
        fprintf(stderr, "[repro] hybris_dlopen failed: %s\n", hybris_dlerror());
        return 1;
    }
    fprintf(stderr, "[repro] handle=%p\n", h);

    int (*get_tls)(void) = (int(*)(void))hybris_dlsym(h, "get_tls");
    if (get_tls) {
        fprintf(stderr, "[repro] get_tls() = %d\n", get_tls());
    } else {
        fprintf(stderr, "[repro] hybris_dlsym(get_tls) failed: %s\n", hybris_dlerror());
    }

    fprintf(stderr, "[repro] hybris_dlclose...\n");
    int r = hybris_dlclose(h);
    fprintf(stderr, "[repro] hybris_dlclose -> %d\n", r);
    fprintf(stderr, "[repro] survived; no abort\n");
    return 0;
}
