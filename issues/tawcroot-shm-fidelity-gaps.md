# tawcroot: /dev/shm emulation fidelity gaps

`shm.c` (memfd-backed POSIX shm emulation) diverges from real
/dev/shm in ways visible to guests:

1. **`mode` is ignored** (`(void)mode` in shm_open) and stat always
   synthesizes 0600 regardless of the creator's mode or later
   `fchmod`. Probably fine under fake-root, but `shm_open(...,
   O_RDONLY)` handing back a **read-write-capable fd** (next item) is
   observable.
2. **O_RDONLY openers get a writable fd**: guest fds are `F_DUPFD`s
   of the internal fd, so access mode can't differ per open. Fix:
   re-open the memfd read-only via `/proc/self/fd/<internal>` with
   `O_RDONLY` instead of dup'ing.
3. **Shared file offset across independent `shm_open` calls**: all
   guest fds for one name dup ONE open file description, so
   `read`/`write`/`lseek` users see each other's offsets. mmap users
   (the common case — Mozilla IPC) don't care. The /proc/self/fd
   re-open in (2) fixes this too (new file description).
4. **Handler inconsistency around the /dev/shm directory**:
   `stat`/`statx`/`access` fake the dir as existing, but
   `openat`/`chdir` of it return ENOENT, and
   `truncate`/`utimensat`/`renameat*`/`statfs` of `/dev/shm/<name>`
   aren't intercepted at all. A configure-style probe sequence sees a
   directory that stats but can't be opened.
5. `/dev/shm/.` and `/dev/shm/..` are classified as shm names
   (`tawcroot_shm_match` rejects only embedded `/`) → ENOENT instead
   of resolving to the directory / `/dev`.
6. `tawcroot_shm_statx_dir` sets `STATX_SIZE` in `stx_mask` while
   leaving `stx_size` 0.

## Severity

Low individually; (2)+(3) are the ones a real program could trip
(shm used via read/write, or a security-conscious O_RDONLY consumer).
