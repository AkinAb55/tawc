# tawcroot: /proc/self/maps shadow silently truncates at 1 MiB and can't grow lines

`syscalls_fs.c` maps-shadow glue (`MAPS_BUF_SIZE = 1 MiB`):

1. The read loop stops at the cap with no error — a Firefox-scale
   process (>10k mappings) gets a shadow truncated mid-line, and the
   rewriter processes the cut-off partial line as if complete.
   Consumers (crash reporters, GC heap walkers, jemalloc introspection)
   see a maps file that just ends.
2. `out_buf` is the same size as `in_buf`, but a bind whose guest dst
   is longer than its host src makes rewritten lines GROW — a
   just-fitting input fails the open with `-ENOSPC`.

Related smaller fidelity gap (`proc_rewrite.c`, commented in code): a
rootfs/bind-src prefix containing `" ("` confuses the
`(deleted)`-tag split heuristic and leaks the host path verbatim;
paths with spaces in the suffix are fine.

## Fix shape

Grow the shadow with a second pass: read once to measure, `memfd` +
`ftruncate` to the needed size, rewrite streaming into the memfd
(the rewriter is already incremental per line — it just needs an
output sink that isn't a fixed buffer). Streaming also caps handler
memory at one line instead of 2 MiB of static buffers.

## Severity

Low-medium: big apps only, and the failure is a truncated diagnostic
file rather than corruption — but Firefox is a primary target
workload.
