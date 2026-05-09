# proot's `/dev/shm` is disk-backed; should be memfd via a proot extension

`ProotMethod` binds `<filesDir>/proot-dev-shm` (real on-disk app-private
storage) at `/dev/shm` so Firefox's `shm_open(3)` calls succeed. This
works but has three problems:

1. **Every SHM write hits flash.** Firefox's IPC SHM is hot — content
   process to parent traffic, layer textures, font caches. Writing
   that through ext4 instead of tmpfs is wasted IO and wear.
2. **Storage growth.** Files accumulate in `proot-dev-shm/` across
   crashes (Firefox cleans up its segments on graceful exit, not on
   SIGSEGV/SIGKILL). No mechanism prunes them.
3. **`filesDir` (not `cacheDir`) means it's not even reclaimable
   under storage pressure** — chosen deliberately to avoid SIGBUS on
   live mappings if Android purges the backing file mid-session,
   but the side effect is that the dir grows unbounded.

The chroot path doesn't have this problem because chroot runs as real
uid 0 with write access to the host's devtmpfs, so glibc's `shm_open`
just creates `/dev/shm/<name>` directly there (devtmpfs is a real
in-kernel tmpfs).

## Survey of existing rootless solutions

- **Termux + proot-distro** (the canonical glibc-on-Android stack):
  binds an app-private dir at `/dev/shm`. Same disk-backed approach.
- **UserLAnd, Andronix:** proot-based, same approach.
- **Termux native** (bionic): ships [`libandroid-shmem`](https://github.com/termux/libandroid-shmem),
  an LD_PRELOAD shim that intercepts `shm_open(3)` etc. and routes
  through ashmem. Does **not** help us — bionic-only, libc-layer only,
  and ashmem's no-resize-after-mmap semantics break clients that
  `ftruncate` post-`mmap` (e.g. Firefox).
- **gVisor** has a userland tmpfs, but it's a 150k-LoC project with
  the wrong perf profile (ptrace platform is *slower* than proot;
  KVM platform requires `/dev/kvm` access we don't have on stock
  phones; systrap platform is interesting but not a small dependency).
- **Linux Deploy / Linux on DeX** require root and `mount -t tmpfs`.

Nobody has a clean rootless tmpfs-for-`/dev/shm` solution in
production.

## Plan: memfd-backed `/dev/shm` as a proot extension

The Termux fork already has `src/extension/ashmem_memfd/` that
intercepts ashmem ioctls and rewrites them to memfd_create.
The same machinery — and most of the same scaffolding — extends
naturally to `/dev/shm` path interception.

### Why memfd, not ashmem

ashmem's known-bad properties for POSIX-shm clients:
- Size set via `ASHMEM_SET_SIZE` ioctl *before* first `mmap`,
  immutable thereafter.
- No `mremap`-grow.
- `fstat().st_size` is not useful pre-ioctl.

memfd_create has none of these:
- `ftruncate` works freely both before and after `mmap`.
- `mremap` works (with `MFD_ALLOW_SEALING` for stricter invariants).
- `fstat` returns real sizes.
- Available to `untrusted_app` / `runas_app` since Android 9
  (kernel ≥ 3.17). Android internals already use it for
  graphics/IPC buffers.
- The `name` argument shows up in `/proc/<pid>/fd/N` as
  `memfd:<name>` for debugging.

The only thing memfd loses is `ASHMEM_SET_NAME` semantics, which
Firefox doesn't depend on — Firefox passes shm fds via SCM_RIGHTS
over its Mojo IPC channel, not by reopening by name.

### Why a proot extension beats an LD_PRELOAD shim

Our previous shim sat at the libc boundary, which missed:
- Apps that issue `SYS_open` / `SYS_openat` directly (Go runtime,
  static-linked Rust, Chromium sandbox helpers).
- All the post-open syscalls (`mmap`/`ftruncate`/`mremap`) that
  needed to behave correctly on the returned fd. With ashmem
  backing, those leaked the resize limitation back to clients.

A proot extension intercepts at the **syscall** layer:
- Catches every path-resolution syscall regardless of how the
  tracee got there.
- Once the fd points at a real memfd, all subsequent
  `mmap`/`ftruncate`/`mremap`/`mprotect`/`munmap` are real
  syscalls on a real kernel object — proot doesn't have to
  intercept them, the kernel just does the right thing.

This is the meaningful architectural improvement: only the
**path-resolution** entry points need translation, not the whole
shm API.

### Syscall surface to intercept

Small list:

1. **`open*` / `openat2` with path under `/dev/shm/`** → rewrite
   to `memfd_create(name, MFD_CLOEXEC)`. Mechanism: at syscall-entry
   stop, rewrite the syscall number and argument registers. proot
   already does this kind of rewrite in `src/tracee/seccomp.c`
   (the SIGSYS handler rewriting `access` → `faccessat`).

2. **`unlink("/dev/shm/<name>")`** → drop name from tracer-side
   name table. The memfd backing dies when last fd closes; the
   kernel handles refcount for free.

3. **`stat("/dev/shm/")`, `access("/dev/shm/")`** → synthesize as
   a directory.

4. **`stat("/dev/shm/<name>")` for an existing name** → return
   the memfd's stat (the tracer needs to hold a copy of the fd
   to read its stat — see "name table" below).

5. **`readdir("/dev/shm/")`** → start with empty / synthesized
   from name table. Most apps don't enumerate `/dev/shm`. Skip
   until something needs it.

`mmap`, `ftruncate`, `read`, `write`, `mremap`, `mprotect`,
`munmap`, `close` need **zero handling** — they operate on a real
kernel fd at that point.

### Architectural challenge: cross-process open-by-name

If process A does `shm_open("foo", O_CREAT)` and process B
(unrelated, not a fork-child of A) does `shm_open("foo", 0)`
expecting to attach to the same segment, memfd is anonymous so
this doesn't naturally work.

Two options:

**Option A — fd-passing handles it (start here).** Firefox,
Chromium, every modern POSIX-shm-using IPC framework actually
passes shm fds via Unix socket `SCM_RIGHTS`, not by name. The
`shm_open` is just to *manufacture* an fd; the name is only
relevant within the creating process. **Sufficient for Firefox.**

**Option B — name table + `pidfd_getfd`.** proot maintains
`Map<name, tracer-side-fd>`. When tracee B opens an existing
name, the tracer dups its fd into B's fd table via
`pidfd_getfd(2)` (kernel ≥ 5.6). Covers the Postgres-style
"everyone reopens by name" pattern. Skips on kernel 5.4 (older
Android phones); fallback there is uglier (named-pipe rendezvous
via tracer, or SCM_RIGHTS courier).

Implement (A) first, only add (B) when a real client demands it.

### Implementation location

New extension at `deps/proot/src/extension/dev_shm/dev_shm.c`, mirroring
the existing `ashmem_memfd` extension. Hook on
`SYSCALL_ENTER_END` (path-translation event) for path-bearing
syscalls and on `SYSCALL_EXIT_START` for any post-syscall fixup.
Self-contained; no entanglement with Termux's existing patches.

The proot fork already has the path-translation primitives
(`translate_path` in `src/path/binding.c`) and the
syscall-rewrite primitives (register-tweaking helpers per arch
in `src/syscall/*`, syscall number replacement in
`src/tracee/seccomp.c`). The `ashmem_memfd` extension is a
working example of "intercept a syscall, do something memfd-y,
return a synthesized fd or rewrite the syscall."

### Effort estimate

~1 focused week for a working prototype handling `open*`,
`unlink`, `stat`, `access` on `/dev/shm/`. Bulk of the work is
corner cases: `openat` with `AT_FDCWD`, relative paths,
`O_TMPFILE`, `O_CREAT` vs O_RDONLY, paths that resolve to
`/dev/shm` via symlink, name-table lifetime across `fork`/`exec`,
`F_DUPFD_CLOEXEC` interactions.

### Compatibility / kernel requirements

- `memfd_create(2)` — kernel ≥ 3.17. Universal on Android phones
  we care about.
- Allowed by `untrusted_app` / `runas_app` seccomp filters —
  Android uses memfd internally; verified accessible from app uid.
- `pidfd_getfd(2)` — kernel ≥ 5.6. Only relevant if we add
  Option B; not needed for Firefox.

### Maintenance

Two paths for landing the patch:

1. **Carry locally.** Add a patch to `apply_local_patches` in
   `scripts/build-proot.sh`. Self-contained extension, low merge
   risk. Same maintenance contract as the existing
   `ashmem_memfd.c` `#include <string.h>` patch.

2. **Upstream to Termux fork.** `proot-distro` users would
   benefit; Termux already maintains `ashmem_memfd`, evidence
   they accept this kind of extension. Lower long-term
   maintenance burden but slower to land.

Start with (1); upstream once stable.

## What this would let us simplify

- Delete `ProotMethod.devShmDir` and the `-b $devShmDir:/dev/shm`
  arg in `prootArgs`.
- Delete `proot-dev-shm` directory creation in `mkdirs()`.
- The `filesDir` vs `cacheDir` decision in
  `ProotMethod.kt:60-62` becomes moot.
- No more "stale Firefox segments accumulate across crashes"
  (memfd is anonymous, dies with the fd).

## When to do this

Not urgent. The current disk-backed bind works for Firefox; users
haven't complained about flash wear or quota growth. Worth doing
when:

- A real client needs `mremap`-grow semantics (some databases,
  some custom shm-using apps) — disk-backed actually works for
  this since it's a real ext4 file, but ashmem-shim history
  suggests it's a footgun area.
- We notice meaningful flash-write volume from Firefox SHM in
  production logs.
- Someone wants to upstream a proper rootless `/dev/shm` to
  Termux's proot.

## References

- `app/src/main/java/me/phie/tawc/install/ProotMethod.kt`
  (`devShmDir`, the `-b` flag in `prootArgv`, and the proot argv
  emission in `startInside`)
- `notes/proot.md` "Firefox under proot" → item 1 (`/dev/shm` bind)
- `notes/proot.md` "What we ship" → existing `ashmem_memfd`
  extension that this would mirror
- Termux fork: `deps/proot/src/extension/ashmem_memfd/ashmem_memfd.c`
  (working example of the extension pattern)
- Termux fork: `deps/proot/src/tracee/seccomp.c` (working example of
  syscall-number rewriting)
- `scripts/build-proot.sh` (where a local patch would be applied)
- Termux's `libandroid-shmem`:
  https://github.com/termux/libandroid-shmem (the ashmem-based
  approach we want to *avoid* and why)
