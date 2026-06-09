# tawcroot: /dev/shm emulation fidelity gaps (remaining)

`shm.c` (memfd-backed POSIX shm emulation) still diverges from real
/dev/shm in two lower-value ways. The observable ones — O_RDONLY
access mode, per-open file offsets, `.`/`..` classification, and the
bogus STATX_SIZE bit — were fixed (re-open the internal memfd through
`/proc/self/fd/<n>` with the guest's access mode instead of `F_DUPFD`,
which also gives each open its own file description).

Remaining:

1. **`mode` is ignored** (`(void)mode` in shm_open) and stat always
   synthesizes 0600 regardless of the creator's mode or later
   `fchmod`. Fine under fake-root in practice; the observable
   consequence (O_RDONLY handing back a writable fd) is already fixed.
4. **Handler inconsistency around the /dev/shm directory**:
   `stat`/`statx`/`access` fake the dir as existing, but
   `openat`/`chdir` of it return ENOENT, and
   `truncate`/`utimensat`/`renameat*`/`statfs` of `/dev/shm/<name>`
   aren't intercepted at all. A configure-style probe sequence sees a
   directory that stats but can't be opened.

## Severity

Low. The real-program-tripping cases (O_RDONLY consumer, read/write
offset sharing) are resolved.
