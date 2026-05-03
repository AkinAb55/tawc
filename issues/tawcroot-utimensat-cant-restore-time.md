# tawcroot: pacman "Can't restore time" warnings when extracting packages

`pacman -S` under tawcroot prints a warning per file extracted:

```
warning: warning given when extracting /usr/lib/libXss.so (Can't restore time)
```

Reproducible during `bash testing/install-test-deps.sh` against a
tawcroot-method install — every package extraction emits one of these
per file, drowning the install log.

## Cause (likely)

libarchive (used by pacman) restores mtime/atime via `utimensat(2)` on
the freshly extracted file. tawcroot doesn't currently trap
`utimensat` (or the legacy `utimes`/`futimesat`/`utime`), so the call
goes to the kernel with a guest-visible path that the kernel resolves
against the host filesystem and fails. libarchive prints "Can't
restore time" and continues.

`utimensat` is a path-bearing syscall and belongs in the same group as
`fchmodat`/`fchownat`/`unlinkat` — it's listed in `notes/tawcroot.md`
"Which syscalls need trapping" alongside the other metadata syscalls
but isn't wired up yet.

## Impact

Cosmetic but loud. Extracted files get the current time as mtime
instead of the time recorded in the package, which:

- breaks reproducible-build comparisons across installs,
- can confuse `make`-style timestamp dependency checks if a tool is
  later run from inside the rootfs against build artifacts that were
  installed from a package,
- floods the pacman install log so real warnings are easy to miss.

No data corruption, no install failures.

## Fix sketch

Add `utimensat` (NR 88 aarch64 / 280 x86_64 in the modern variant) to
the trap list, route to a handler that translates the path arg
(handling `AT_FDCWD` + `dirfd` like the other *at handlers, and
`AT_EMPTY_PATH` with `path == "" / NULL` for the fd form), and
forwards the host call. Same shape as `handle_fchmodat`.

Legacy x86_64 `utimes(2)` (NR 235) and `futimesat(2)` (NR 261) should
route through `utimensat` for the same reason `chmod`/`chown` route
through their `*at` cousins on x86_64.

## Severity

Low (cosmetic), but trivial to fix and makes install logs readable.
