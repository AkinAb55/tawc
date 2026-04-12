# Move CFI workaround into libhybris fork

## Summary

Move the bionic CFI (Control Flow Integrity) binary patch from `client/tawc-wsi/tawc-egl.c` into our libhybris fork's initialization code. This is a prerequisite for the libhybris Wayland platform migration (see `migrate-to-libhybris-wayland-platform.md`), which deletes tawc-egl.c.

## Background: what CFI is and why it crashes

Android's C/C++ libraries (bionic) use Control Flow Integrity as a security feature. When compiled code makes an indirect function call (through a function pointer), CFI checks that the pointer actually targets a valid function of the expected type. The check works via a **shadow table**: a sparse `uint16_t` array covering the address space, where each entry says whether addresses in that 256KB range are valid call targets.

The flow:
1. At process start, the bionic dynamic linker creates the shadow table, populates it for all loaded libraries, and calls `__cfi_init` in `libdl.so` to register the shadow pointer
2. When an indirect call happens, `__cfi_slowpath` in `libdl.so` looks up the target address in the shadow
3. If the shadow entry is `kUncheckedShadow` (value 1), the call is allowed. If `kInvalidShadow` (value 0), it's rejected. Other values encode offsets to per-library `__cfi_check` functions.

**The crash:** When libhybris loads Android vendor GPU libraries (via `hybris_dlopen`), those libraries' code ranges are not registered in the shadow table. The shadow was set up by the real bionic linker at process start for system libraries only. When vendor libraries make indirect calls that trigger CFI checks, `__cfi_slowpath` looks up addresses that were never registered, hits `kInvalidShadow`, and crashes.

## Why libhybris doesn't handle this already

libhybris actually contains a full CFI shadow implementation in `hybris/common/q/linker_cfi.cpp` (`CFIShadowWriter` class). It can create shadows, call `__cfi_init`, and register libraries via `AddLibrary`. **But this code is dead in our configuration.**

The activation path is:
- `linker_main.cpp:486` calls `get_cfi_shadow()->InitialLinkDone(solist)`, which sets `initial_link_done = true` and triggers shadow creation
- `linker.cpp:1912` calls `get_cfi_shadow()->AfterLoad(si, ...)` after each `dlopen`, which adds new libraries to the shadow

The problem: `InitialLinkDone` is only called from `linker_main()`, which runs when libhybris acts as the process's actual dynamic linker (the Ubuntu Touch / Halium deployment model). In our deployment (glibc chroot on stock Android), the entry point is `android_linker_init()` (line 793), which **never calls `linker_main()` or `InitialLinkDone()`**. So `initial_link_done` stays false, and every `AfterLoad` call exits immediately:

```cpp
// linker_cfi.cpp:250
if (!initial_link_done) {
    // Too early.
    return true;
}
```

Even if we activated it, it wouldn't help: `NotifyLibDl` searches libhybris's own soinfo list for `libdl.so`, but the real bionic `libdl.so` (with `__cfi_init`) isn't in that list. And calling the system's `__cfi_init` with a new shadow would replace the existing shadow pointer, breaking CFI for system libraries. The whole CFI shadow system assumes libhybris IS the linker, which it isn't in our case.

**This doesn't matter anyway:** vendor GPU libraries don't have `__cfi_check` symbols, so even a properly initialized shadow would mark them all `kUncheckedShadow` (pass all checks). Patching `__cfi_slowpath` to `ret` is semantically equivalent.

## Current implementation

In `client/tawc-wsi/tawc-egl.c` (lines 502-534), `patch_bionic_cfi()`:

1. Parses `/proc/self/maps` to find the executable (`r-xp`) segment of `libdl.so`
2. Scans for known instruction patterns that identify `__cfi_slowpath`:
   - **Android 14:** `sub w8, w1, #0x7f, lsl #12` (0xd351fc28) + `ubfx x9, x8, #31, #7` (0xd35f9909)
   - **Android 16+:** `xpaclri` (0xd50320ff) + `movn x9, #0xaf40, lsl #16` (0x92b5e809)
3. On match: `mprotect` the page RWX, overwrite the first matched instruction with `ret` (0xd65f03c0), flush icache, restore permissions
4. Logs "patched __cfi_slowpath in bionic libdl.so" on success

Currently called from `do_init()` after `eglGetDisplay` (which loads bionic libs via libhybris) but before `eglInitialize` (which triggers CFI-checked calls in vendor code).

## Plan

### Patch location in libhybris

Add CFI patching to `android_linker_init()` in `hybris/common/q/linker_main.cpp`. This function is the entry point when libhybris is used as a library (our case). It runs before any `hybris_dlopen` calls, so the patch will be in place before any vendor libraries are loaded.

Specifically, add it at the end of `android_linker_init()` (around line 830), after all the linker setup is complete but before the function returns. This is analogous to where `linker_main()` calls `InitialLinkDone()` — the linker is fully initialized, and we're about to start loading Android libraries.

### Implementation

Create a new file `hybris/common/q/cfi_bypass.c` (plain C, no C++ needed):

```c
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>

// Patch bionic's __cfi_slowpath to a no-op return.
// When libhybris loads vendor libraries in a glibc process, those libraries'
// code ranges are not registered in the CFI shadow table (which was set up by
// the real bionic linker for system libraries only). This causes crashes when
// vendor code makes indirect calls. Since vendor libraries don't have
// __cfi_check symbols, a proper shadow would mark them kUncheckedShadow anyway,
// so disabling the check entirely is semantically equivalent.
void hybris_patch_bionic_cfi(void)
{
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return;

    char line[512];
    while (fgets(line, sizeof(line), f)) {
        unsigned long lo, hi;
        char perms[5];
        if (sscanf(line, "%lx-%lx %4s", &lo, &hi, perms) < 3) continue;
        if (!strstr(line, "libdl.so")) continue;
        if (perms[0] != 'r' || perms[2] != 'x') continue;

        uint32_t *code = (uint32_t *)lo;
        size_t count = (hi - lo) / 4;
        for (size_t i = 0; i + 1 < count; i++) {
            // Android 14: sub w8,w1,#0x7f,lsl#12 + ubfx x9,x8,#31,#7
            // Android 16+: xpaclri + movn x9,#0xaf40,lsl#16
            if ((code[i] == 0xd351fc28 && code[i+1] == 0xd35f9909) ||
                (code[i] == 0xd50320ff && code[i+1] == 0x92b5e809)) {
                unsigned long page = (unsigned long)&code[i] & ~0xFFFUL;
                if (mprotect((void*)page, 4096, PROT_READ|PROT_WRITE|PROT_EXEC) == 0) {
                    code[i] = 0xd65f03c0; // ret
                    __builtin___clear_cache((char*)&code[i], (char*)&code[i+1]);
                }
                fclose(f);
                return;
            }
        }
    }
    fclose(f);
}
```

Then in `linker_main.cpp`, at the end of `android_linker_init()`:

```cpp
extern "C" void hybris_patch_bionic_cfi(void);
// ...
hybris_patch_bionic_cfi();
```

### Build integration

Add `cfi_bypass.c` to the `hybris/common/` Makefile.am so it's compiled into `libhybris-common`. It has no dependencies beyond libc.

### Testing

1. Build libhybris with the patch
2. Remove the `patch_bionic_cfi()` call from tawc-egl.c (or just test with the new libhybris before the Wayland platform migration)
3. Run any EGL app (GTK3 debug app, Firefox) — should work without CFI crashes
4. Test on both Android 14 and 16+ if possible (different instruction patterns)

### Future-proofing

The instruction-pattern approach will break on future Android versions if `__cfi_slowpath` is recompiled with different codegen. When that happens, a new pattern needs to be added. Alternatives that would be more robust:
- Look up `__cfi_slowpath` by symbol name in `libdl.so`'s ELF symbol table instead of pattern matching. This is more reliable but requires ELF parsing. Could use libhybris's existing ELF loading code.
- Check if libdl.so exports the symbol (it may not — CFI functions are often hidden).

For now, pattern matching with version-specific patterns is fine. It's what we've been doing and it works. Add a log warning if no pattern matches so failures are obvious.

### Update TAWC_FORK.md

Document the CFI patch in `libhybris/TAWC_FORK.md` as one of our fork's changes.
