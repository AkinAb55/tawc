# tawcroot: `tawcroot_path_translate` orchestration not reachable from unit tests

The path translator (`tawcroot_path_translate` in
`tawcroot/src/path.c`) chains four stages: fold → memo → resolver
→ bind. Three of the four are pure functions already in
`PROD_C_FOR_TESTS` (compiled into both the freestanding production
build and the hosted-glibc cleat orchestrator) and have unit
coverage. The orchestration itself sits behind `raw_sys.h`, which
the hosted build can't link, so nothing test-side can call
`tawcroot_path_translate` directly.

The only way to exercise the chained behavior today is through
`test_path_resolve.c`, which covers the resolver indirectly and
misses the inter-stage seams.

## Refactor

Pass an oracle to the orchestration helper for the three things
that need OS access: the cwd source, `readlinkat`, and the bind
table. Move the orchestration into `PROD_C_FOR_TESTS` and have
`path.c`'s production entry point wire it to a thin oracle backed
by `raw_sys` (`prod_readlink` already exists and is the model).

## Tests this unblocks

Once `tawcroot_path_translate` is testable directly:

- **fold-then-memo-then-resolve-then-bind ordering** — verify each
  stage runs with the previous stage's output. Boundary cases
  noted as "B4" in the original review.
- **bind-vs-memo collisions** — what happens when a bind dst
  matches a memoized prefix. "B5" cases.
- **escape attempts** — `..` chains, absolute symlinks,
  `/proc/self/cwd/...` reverse-engineering of the rootfs.

## Adjacent test gaps blocked on the same refactor

- **`tawcroot_path_fold_absolute`** has no direct unit tests despite
  living in `PROD_C_FOR_TESTS`. Coverage is via `test_path_resolve.c`
  only. Add: deep `..` chain at the limit; `MAX_COMPONENTS+1`
  components rejected; `/.` and `/..` at top; `///` runs; trailing
  `/`; exact PATH_MAX overflow.
- **`route_through_binds`** is `static` in `path.c`; extracting it
  along with the orchestration lets us cover longest-prefix-match
  across overlapping binds, single-component dst, bind dst exactly
  matching the suffix (empty leftover), and the memo collision
  case (B5).

## Priority

High. Path translation is the most consequential code in tawcroot
and the most likely place for a subtle regression to slip past CI.
