# tawcroot: minor syscall divergences from kernel semantics (grab-bag)

Collected small divergences from the 2026-06 review pass. None blocks
a known workload; listed so they don't have to be re-discovered.

- **chown family fakes success unconditionally**:
  `handle_fchownat`/`handle_fchown` return 0 without validating
  anything — `chown("/does/not/exist", …)` succeeds (kernel: ENOENT),
  `fchown(-1, …)` succeeds (kernel: EBADF), and EFAULT for a wild
  path pointer is never raised (handle_chown_legacy DOES validate —
  internally inconsistent). Fix: translate + fstatat existence probe
  before faking 0, mirroring proot.
- **Reserved-fd "behaves as EBADF" contract is partial** (fdtab.h):
  `openat(1000, "etc/shadow", …)` with a reserved dirfd passes the
  dirfd through; `fstatat(1000, "", AT_EMPTY_PATH)`/`statx` stat the
  reserved fd; `fchdir` is untrapped entirely (see also
  tawcroot-untrapped-path-syscalls.md). Only close/dup/fcntl/
  readlinkat-empty check `tawcroot_fd_is_reserved`.
- **getdents64 filter can fake EOF mid-directory**: if one batch
  contains ONLY reserved-fd entries (tiny guest buffer), compact
  returns 0 = end-of-directory and the remaining real entries are
  never seen. Fix: re-issue host getdents64 until a non-empty
  filtered batch or true EOF.
- **/proc shadow memfds always MFD_CLOEXEC**: a guest opening
  /proc/self/maps WITHOUT O_CLOEXEC loses the fd across its next
  exec. Clear FD_CLOEXEC when the guest didn't ask for it.
- **unlink/rmdir/linkat errno shapes**: `unlink("/")` → EINVAL
  (kernel: EISDIR), `rmdir("/")`/bind-root → EINVAL (kernel: EBUSY),
  `linkat` empty operand → EINVAL (kernel: ENOENT).
- **execveat(AT_SYMLINK_NOFOLLOW)** → ENOSYS placeholder (documented
  in syscalls_exec.c; fine until something uses it).
- **Signal-shadow staleness across fork**: a forked child inherits
  the parent's tid-keyed `blocked` table (COW); kernel tid reuse in
  the child can read a stale "SIGSYS blocked" bit until that thread's
  first rt_sigprocmask. Same family as the documented
  involuntary-thread-death gap (syscalls_control.c handle_exit
  comment); bounded to one wrong shadow-mask read.
- **Path scratch pool holds while acquiring**: one handler chain can
  hold 3–5 of the 128 slots simultaneously
  (handler → fetch_and_translate_at → fd_to_guest_abs → translate);
  acquire spins forever on exhaustion, so enough concurrent
  mid-chain threads could in principle livelock. Theoretical at 128
  slots / realistic thread counts.
- **Trailing-slash semantics are erased by the fold**: `lstat("/l/")`
  should follow `l`, `open("/file/")` should ENOTDIR, `unlink("/d/")`
  should fail — the fold strips the slash and nothing downstream
  re-attaches the "must be a directory" requirement. proot-style
  shortcut; document or thread a flag through the translator.
- **Deep paths**: the fold caps at 256 components (ENAMETOOLONG);
  kernel accepts ~2048 single-char components in 4096 bytes.
