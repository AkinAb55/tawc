# tawcroot: `memcpy`/`memset`/`memmove` duplicated across freestanding files

`loader_stack.c`, `loader_elf.c`, `exec_state.c`, and `loader_map.c`
each open-code their own copies of the basic mem helpers. Production
already has `tawc_memcpy` / `tawc_memset` / `tawc_memmove` in
`tawcroot/src/strings.c`.

The duplicates exist because some of these files are in
`PROD_C_FOR_TESTS` — compiled twice, once freestanding for the
production binary and once into the cleat orchestrator (hosted
glibc). The orchestrator already has the standard `<string.h>`
versions, so a naive include of `strings.c` would collide.

## Fix

A shared `<string.h>`-equivalent header that switches based on
build mode:

- Production / freestanding: include `strings.h` from tawcroot,
  which declares `tawc_memcpy` etc. and `#define memcpy
  tawc_memcpy` (or callers use `tawc_*` directly).
- Hosted (cleat orchestrator): include real `<string.h>` and
  `#define tawc_memcpy memcpy` (or vice versa).

Either direction works as long as callers use one set of names.
Pick the one that requires fewer edits at call sites.

## Severity

Cleanup. Code-size and consistency only — no correctness impact
today.
