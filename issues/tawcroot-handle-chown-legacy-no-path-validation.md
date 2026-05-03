# tawcroot: `handle_chown_legacy` returns 0 without validating the path argument

`handle_chown_legacy` returns 0 unconditionally to mirror proot's
`-0` behavior (fake-chown succeeds, no actual filesystem change).
The mirror is correct in spirit but the implementation doesn't
even fetch the guest path argument, so a wild pointer (a value
that `prod_readbytes_from_guest` would reject as `-EFAULT`)
silently returns 0 instead of `-EFAULT`.

## Why it matters

Minor info-leak resistance: a malicious or buggy guest can
distinguish "chown was ignored" from "chown got a bad pointer"
on every other path-bearing syscall, but not this one. Cleaner
contract is to return `-EFAULT` for bad pointers even on
fake-success syscalls.

## Fix

Read the path arg with the standard guest-pointer helper at the
top of `handle_chown_legacy`, return `-EFAULT` if it fails, then
return 0. Matches the pattern other `fake_*` handlers use.

## Severity

Low. Pure consistency; no real workload depends on the
distinction.
