# tawcroot tests: a filter matching zero tests exits 0

`deps/cleat`'s runner counts non-matching tests as skipped and returns
success when `failed == 0` (`deps/cleat/src/test.c::run_tests`,
`return failed == 0;`). So:

    tawcroot/test.sh handler/test_fundation_smoke   # typo
    → runs 0 tests, exits 0

CLAUDE.md and the device path's `__exit=` plumbing teach everything to
trust this exit code, so a typo'd filter (or a renamed test) reads as
a pass to any script or agent. A human sees "0 passed" in the summary;
automation doesn't.

## Fix shape

Best fixed in cleat itself (exit nonzero — or at least a distinct
code — when argv filters were given and matched nothing), then bump
the pin in `deps/deps.list`. A tawcroot-local band-aid: `test.sh`
could grep the summary line for `^0 tests? passed` when PASSTHROUGH
is non-empty, but that's output-format coupling the cleat fix avoids.

## Severity

Low-medium: no wrong code ships, but green-when-nothing-ran is the
classic CI trap, and this repo's workflow leans on test.sh exit codes.
