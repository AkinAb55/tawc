# tawcroot: `tawcroot_phase1_main` is a 1100+ line single function

`tawcroot/tests/testhost/src/phase1.c::tawcroot_phase1_main` is one
giant function — 1100+ lines of straight-line scenarios delimited
only by comments. cleat's `register_dynamic_tests` already gives
each check a unique name, but the C code itself doesn't reflect
that structure.

## Why it should be split

- Adding a scenario means hunting through 1000+ lines of
  unrelated setup to find the right insertion point.
- A failing assertion's stack trace points at "phase1_main:817",
  which doesn't tell you which scenario broke until you read
  the comment header.
- Local variable scopes leak across unrelated scenarios; one
  scenario's setup can subtly affect the next.

## Fix

Mechanical refactor: each comment-delimited block becomes a
`static void test_<name>(...)` function. `tawcroot_phase1_main`
becomes a list of calls. Names should match the dynamic test
labels so a failure log gives an immediate file:function pointer.

## Severity

Maintenance debt only — no functional impact. The longer it
sits, the more append-only growth makes the eventual split
harder.
