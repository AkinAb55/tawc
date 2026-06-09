# tawcroot: /proc/self/maps shadow — remaining " (" tag-split fidelity gap

The 1 MiB truncation and the output-grows-past-input ENOSPC were fixed:
`open_proc_maps_shadow` now reads /proc/self/maps into a growable
anonymous mapping (doubling from 1 MiB to a 256 MiB ceiling) and
rewrites into an output buffer that grows-and-retries on ENOSPC, so a
Firefox-scale maps no longer truncates mid-line or fails to fit.

Remaining (low):

- `proc_rewrite.c` tag-split heuristic: a rootfs/bind-src prefix
  containing `" ("` confuses the `(deleted)`-tag split (it splits the
  path at the first `" ("`), leaking the host path verbatim for that
  one mapping. Paths with spaces in the suffix are fine. The kernel
  only ever appends `" (deleted)"`, so splitting on the LAST `" ("` or
  matching the literal `" (deleted)"` suffix would close it.

## Severity

Low. The truncation (the real Firefox-affecting bug) is resolved.
