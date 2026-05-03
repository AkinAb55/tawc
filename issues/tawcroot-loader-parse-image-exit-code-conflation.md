# tawcroot: `parse_image` exits 61/62 inconsistently with documented codes

`loader_exec.c::parse_image` exits with code 61 on EINVAL and 62
on anything else. The documented codes in `loader_exec.h:43-58`
say:

- 61 = "ehdr read/parse failed"
- 62 = "phdrs read/parse failed"

`parse_image` does both in one function, and the EINVAL-vs-other
heuristic is just a guess at which stage failed — not actually
correlated with whether the failure was in the ehdr or the phdr
read. A real ehdr-parse failure that returns `-EIO` exits as 62
("phdrs failed"), and vice versa.

## Why it matters

The exit codes are the only signal upstream debug tooling has when
parse_image fails (the loader runs in an exec'd child). Mislabeled
codes send a debugger looking in the wrong place.

## Fix

Either:
- Split `parse_image` into `parse_ehdr` and `parse_phdrs`, each
  returning a specific exit code on failure. Cleanest.
- Or: track which stage failed via a local enum and exit with the
  matching code. Smaller diff but keeps the function shape.

## Severity

Low. Diagnostics-only; fails the same way regardless of the exit
code, you just go looking in the wrong place first.
