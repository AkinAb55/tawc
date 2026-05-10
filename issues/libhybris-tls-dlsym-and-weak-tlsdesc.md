# libhybris: dlsym(TLS) and unresolved-weak TLSDESC read raw tpidr_el0

Two pre-existing latent paths in libhybris's vendored Q linker still
read the host glibc thread pointer instead of the patched bionic TP,
and the patched-MRS TLS architecture doesn't cover them. They're
unreachable in our current integration suite but worth recording so
they don't bite a future workload.

## 1. `do_dlsym` of a `__thread` symbol

`hybris/common/q/linker.cpp:2441` — `do_dlsym` of a TLS symbol calls
`TLS_GET_ADDR` directly. That resolves to q.so's
`bionic_elf_tls.cpp::__tls_get_addr` (333..357), which calls
`__get_bionic_tcb()` — a raw `mrs tpidr_el0` returning *glibc's* TP,
not the patched thunk's bionic TP. The result is a pointer into glibc's
TLS storage, not into `tls_static_tls`. Silent corruption rather than
abort.

Q.so's `__tls_get_addr` is now hidden by `q.ver`, so external callers
can't reach it, but `do_dlsym` is internal — the symbol resolves
within q.so's own link map.

Trigger: `hybris_dlsym(handle, "<__thread var name>")` on a bionic .so.
We don't currently do this anywhere in tawc.

Fix options: either make `do_dlsym` `DL_ERR` on TLS symbols, or rewrite
`__tls_get_addr` to use the patched-MRS path (`_hybris_hook___get_tls_hooks`
→ `tls_static_tls + BIONIC_TPIDR_OFFSET`).

## 2. `tlsdesc_resolver_unresolved_weak`

`hybris/common/q/tlsdesc_resolver.S:192` — the unresolved-weak TLSDESC
resolver reads `tpidr_el0` directly. Intent (per upstream): return an
arg that, when added to the patched TP by bionic code, produces NULL
(the canonical "weak symbol resolved to null" address). Implementation
arithmetic relies on the resolver's TP and the bionic code's TP being
the same — under libhybris they aren't (`mrs tpidr_el0` here returns
glibc's TP; bionic code's `mrs tpidr_el0` is patched to return
`tls_static_tls + 8`). The resulting "NULL" address is actually
`addend + (bionic_tp - glibc_tp)`, a nonsense value.

Trigger: an unresolved weak `__thread` reference in a bionic .so. None
in our current GPU stack.

Fix options: rewrite the resolver to call back into hooks.c for the
bionic TP, or have the IE/TLSDESC handler in `linker.cpp` `DL_ERR` on
unresolved-weak TLS instead of installing this resolver.

## 3. Pre-existing duplicate `mod_ptr` in `__tls_get_addr`

`hybris/common/q/bionic/libc/bionic/bionic_elf_tls.cpp:312-322` (the
q.so copy, not the linker.cpp one) declares `void* mod_ptr = nullptr`
inside an inner block, shadowing the outer `mod_ptr` from line 312.
The `posix_memalign` result lands in the inner shadow, leaks the
`tls_allocator.memalign` allocation from the outer one, and
`dtv->modules[module_idx]` is set from the always-null inner.

Pure latent bug — only matters if `do_dlsym(TLS)` (item 1 above) is
ever exercised. Filing for completeness.

## Notes

All three are arch-independent in libhybris's Q linker but only matter
on aarch64 (TLSDESC is arm64-only in this code, and the patched-MRS
flow is aarch64-only). On x86_64 libhybris these paths fall back to
upstream behaviour, which is unrelated to our patched-MRS scheme.

The catch-up-replay invariant in `_hybris_hook___get_tls_hooks` is a
related class: a glibc-spawned worker thread that only does TLSDESC
reads against bionic .sos and never calls a hooked libc function will
read zeros for non-zero `__thread` initialisers. Documented in
hooks.c's comment but not enforced. If any of the above three issues
get fixed, audit that comment too.
