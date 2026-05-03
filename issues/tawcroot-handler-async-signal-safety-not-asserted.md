# tawcroot: no test enforces async-signal-safety in the SIGSYS handler

The SIGSYS handler runs in signal context and must be
async-signal-safe — no `malloc`, no `printf`, no locale lookups,
no dynamic linker re-entry. Today we keep that property by
convention: handler.c, dispatch.c, and the per-syscall handlers
only call hand-rolled `tawc_*` helpers. There's no build-time
check or runtime assertion that catches an accidental libc call.

## What a regression looks like

Someone adds a `printf` for debugging in `handle_openat`,
forgets to remove it. Production builds link against no libc, so
the call ends up resolving to whatever happens to be in the
runtime's symbol table — most likely the guest's libc. From a
signal context, inside the guest's address space, with a partial
syscall in flight. Hard to reproduce, harder to debug.

## Suggested check

Link `handler.c + dispatch.c + syscall_*.c` against a stub libc
that defines `malloc`, `free`, `printf`, `fprintf`, `vfprintf`,
`abort`, `__assert_fail`, `localeconv`, etc. as symbols that
immediately abort. Build the resulting object as a test harness
and run the smoke through it; if any of those symbols got pulled
in, the link fails (or the test aborts on first invocation).

Could also be done at link time only via `--no-undefined` on a
narrow object set, with a deny-list of known-bad symbols.

## Severity

Low today (we're careful), but the cost of a regression slipping
in is high (hard-to-reproduce crashes in signal context). Cheap
test to add once the harness scaffolding exists.
