# tawcroot: errno constants are redefined inline across many files

`path_resolve.c`, `path_fold.c`, `path.c`, and the `syscalls_*.c`
files each redefine common errno values inline as ad-hoc macros
(`EFAULT_NEG`, `-14`, `-22`, etc.) or as bare numeric literals.

Grepping for `-14` finds dozens of bare numbers without macros.
There is no single header that lists the negated errnos we use,
so:

- A reader can't tell at a glance whether `-22` means `-EINVAL`
  or "I just typed a number."
- A typo (`-13` instead of `-14`, swapping `-EACCES` for
  `-EPERM`) is silent.
- Adding a new errno consumer means inventing yet another local
  macro.

## Fix

Extract a single `tawcroot/include/errno_neg.h` (or similar) with
`TAWC_E*` constants for every errno we currently use as a negated
return value. Replace inline macros and bare literals at every
call site.

Bonus: a single source of truth for the negated-errno convention
(we use raw `-errno` for syscall returns; libc-using callers
expect positive errno separately) so newcomers don't have to
re-derive the rule from each file.

## Severity

Cleanup. Improves readability and reduces typo risk.
