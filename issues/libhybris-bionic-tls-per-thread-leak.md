# libhybris: bionic_tls compat struct leaked per thread

## Summary

Each thread that calls `_hybris_hook___get_tls_hooks()` allocates a 16KB `bionic_tls` compat struct via `calloc` that is never freed. The allocation happens lazily on first access per thread (in `hooks.c`), but there is no corresponding cleanup on thread exit.

## Location

`libhybris/hybris/common/hooks.c` in `_hybris_hook___get_tls_hooks()` — the `calloc(1, BIONIC_TLS_COMPAT_SIZE)` call.

## Impact

Low in practice. GPU driver threads tend to be long-lived, so the leak is bounded by thread count rather than growing over time. 16KB per thread that touches bionic TLS.

## Fix

Needs a thread-exit cleanup hook (e.g. a `pthread_key_t` destructor) to free the allocation when a thread terminates. Non-trivial because the cleanup must run after all bionic code on the thread is done using the struct.
