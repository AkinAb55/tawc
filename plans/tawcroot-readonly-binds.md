# tawcroot read-only binds

**Status: plan, not started.** No kernel-version gate — this works on
every device tawcroot runs on today (unlike
[plans/tawcroot-landlock.md](tawcroot-landlock.md), which is an
*optional future second wall* on ≥5.13 kernels; this plan must be
fully executable without it).

Tawcroot's bind table is read/write only: `tawcroot_path_add_bind`
opens the src `O_PATH | O_DIRECTORY`, the kernel uses that fd only as
the starting inode for path resolution, and write-mode `openat`s
through the bind succeed. Anything inside the rootfs can write through
to the host-side bind src.

## Why now / consumers

- **`ExternalBind` (user binds, shared storage).** The Kotlin side
  already reserves the design space: `ExternalBind.kt` — "No
  `writable` flag: tawcroot's `-b src:dst` bind table has no read-only
  mode. Add one here when tawcroot grows support." An RO toggle lets a
  user expose e.g. `DCIM` to a guest without trusting every guest
  program with deletes.
- **Reverting copy→bind for APK-shipped assets** (notes/installation.md
  §"Why copy, not bind"): libhybris (~12 MB per rootfs) is copied in
  purely because binds are writable shared state. A whole-dir RO bind
  of `/usr/lib/hybris` removes the per-install copy and the
  per-app-update wipe-and-recopy. (The "bind = replacement, not merge"
  problem is unchanged — single files like the glvnd vendor JSON that
  must coexist with distro-managed siblings stay copies.)
- **Fidelity on Android system binds.** `/system`, `/vendor` etc. are
  host-RO anyway; marking them RO makes guests see kernel-faithful
  `EROFS` / `access(W_OK)` / `statfs` answers instead of host-flavored
  `EACCES` surprises.
- **Landlock coupling.** When plans/tawcroot-landlock.md lands, an RO
  bind's `allowed_access` grant drops the write rights, turning this
  emulation into kernel enforcement. The per-bind flag added here is
  the input that plan threads through.

## Why the kernel doesn't give us RO for free

1. **Opening the bind src `O_RDONLY` instead of `O_PATH` doesn't
   work.** The src fd is only the `dirfd` starting inode for
   `openat(dirfd, suffix, flags)`; the resulting fd's access mode
   comes from the openat *flags*, not from how `dirfd` was opened.
2. **Real `mount --bind -o ro`** needs `CLONE_NEWUSER | CLONE_NEWNS`
   for in-namespace `CAP_SYS_ADMIN` — a much heavier mechanism than
   tawcroot's path-translation model, and unavailable/unreliable under
   Android's app sandbox. Not the natural extension of the design.

So RO must be emulated at the translation layer. The rest of this plan
is about making that emulation **robust by construction** rather than
a scatter of per-syscall checks.

## Design principles (robust by design)

The failure mode to design out: "we forgot a check in one of ~20
handlers and now there's a silent write-through that nothing tests."
Four structural moves, in decreasing order of load borne:

1. **One enforcement point, in pure code.** Every path-bearing syscall
   already funnels through `tawcroot_path_translate` →
   `tawcroot_path_translate_with_ctx` (path_orchestrate.c), which is
   pure-with-context and cleat-unit-tested. The RO refusal lives
   *there*, once, after the final bind route is known — not in
   handlers. Handlers only *declare intent* (read vs write); they never
   implement the refusal. A declaration can be wrong (caught by the
   test matrix below); it cannot be *missing* (the compiler forces it —
   see #2) and the check itself cannot be skipped (there is no
   translation path around it).
2. **Fail-closed defaults.** The intent parameter is defined so that
   the zero value means WRITE (denied through RO binds). A forgotten
   or zero-initialized intent in future code degrades to a visible
   `EROFS` on RO binds — a testable functional break — never to a
   silent write-through. Additionally, the two inherently-mutating
   resolution modes force write intent *inside the orchestrator*,
   regardless of what the caller declared:
   `PARENT_CREATE` (mkdir, mknod, symlink dst, O_CREAT|O_EXCL,
   link/rename dst) and `PARENT_REMOVE` (unlink, rmdir, rename src)
   exist only for mutations, so those ~10 syscalls are covered even by
   a mislabeled call site. The residual mislabel-risk surface is small:
   FOLLOW/NOFOLLOW-mode writers (open-for-write, truncate, chmod,
   chown, utimensat, setxattr), dominated by `openat`, which gets its
   own pure, unit-tested flags→intent classifier.
3. **The kernel double-covers all data writes.** Because write-mode
   opens through RO binds are refused, no write-mode fd into an RO
   bind ever exists. `write`, `pwrite*`, `mmap(PROT_WRITE,
   MAP_SHARED)`, `ftruncate`, `fallocate`, `copy_file_range`,
   `splice`, io_uring-style fd writes — all fail in the kernel on the
   fd's access mode with no tawcroot involvement. Even a missed
   path-layer check for a *data* write is therefore backstopped; what
   is NOT kernel-backstopped is exactly (a) namespace mutations
   (create/remove/rename — covered by the forced-write modes) and (b)
   fd-based *metadata* writes (stage 2 below).
4. **Stateless ground truth for the fd residue — no taint table.** An
   earlier revision of this plan tracked "fds opened through an RO
   bind" in an 8 KB bitmap, propagated across `dup`/`dup2`/`dup3`/
   `fcntl(F_DUPFD*)`/`close_range`/`execve` — stateful bookkeeping
   where any missed propagation is a silent hole (and SCM_RIGHTS
   fd-passing between guests defeats it entirely). Dropped. Instead,
   the rare fd-based metadata syscalls that need a verdict derive it
   from kernel ground truth at call time: readlink
   `/proc/self/fd/<n>` (existing helper
   `tawcroot_proc_fd_to_host_path`) and longest-prefix-match the host
   path against the RO bind srcs. No state, nothing to propagate,
   nothing to desync; works for inherited and socket-passed fds. Cost
   is one readlink on syscalls that are cold everywhere.

## The bind flag and its plumbing

- `struct tawcroot_bind` gains `int read_only`.
- **CLI**: `-b SRC:DST[:ro]`. `parse_bind_spec` accepts 2 or 3
  colon-fields; a third field must be exactly `ro` (else the existing
  malformed-spec exit 84). Unambiguous by construction on the app
  path: `ExternalBind.validationError()` already rejects `:` in bind
  paths *because* they travel as `-b src:dst`.
- **Supervisor args** (`supervisor.h`): a parallel `bind_ro` array
  next to `bind_src`/`bind_dst`; `tawcroot_path_add_bind(src, dst,
  read_only)` — signature change so every caller is compiler-audited.
- **exec_state v6**: per-bind flags (`uint8_t bind_ro[MAX_BINDS]`)
  plus a `root_ro` bit (below). Writer/reader are the same binary;
  the existing strict version check handles the bump.
- **chroot**: `tawcroot_path_binds_reanchor` copies/edits bind structs
  in place — `read_only` survives re-anchoring with zero new code.
  One real addition: **chroot *into* an RO bind dst**. After that
  chroot, the whole root view IS the bind src, but routing goes
  through `tawcroot_rootfs_fd`, not the bind table — the flag would be
  lost. So: a process-global `tawcroot_root_ro`, set from the
  translation result's `ro` bit when chroot swaps the root (see next
  section — the result already carries it), consulted by the
  orchestrator for rootfs-routed paths, and ferried in exec_state
  (`root_ro`) since a guest can exec after chrooting. Chroot itself is
  a read operation and is *allowed* into an RO bind, like the kernel.
  An RW bind nested under an RO bind dst stays writable via
  longest-prefix match — same semantics as an RW mount under an RO
  mount; no special code.
- **No cap changes**: `TAWCROOT_MAX_BINDS`, dst/src buffer sizes are
  untouched.

## Enforcement: the intent parameter

New enum, deliberately zero-defaulting to the denied state:

```c
typedef enum {
    TAWCROOT_INTENT_WRITE = 0,   /* mutates path metadata/namespace/data */
    TAWCROOT_INTENT_READ  = 1,   /* observes only */
} tawcroot_path_intent;
```

Threaded as a new parameter through `tawcroot_path_translate`,
`tawcroot_path_translate_with_ctx`, and syscalls_fs.c's
`translate_at`/`translate_local` (also the socket and chroot call
sites). The signature change forces every existing call site to be
touched and classified once, in one reviewable diff.

In `tawcroot_path_translate_with_ctx`, after the final bind route is
known (the *last* `route_through_binds` outcome — a symlink walk may
route into and back out of a bind, and only the final base matters):

```
ro = matched_bind ? matched_bind->read_only
                  : (routed_to_rootfs && ctx->rootfs_ro);
effective_write = (intent == TAWCROOT_INTENT_WRITE)
               || mode == TAWCROOT_PATH_PARENT_CREATE
               || mode == TAWCROOT_PATH_PARENT_REMOVE;
if (ro && effective_write) return -EROFS;
r.ro = ro;   /* result gains an `ro` field for handler fidelity uses */
```

`ctx->rootfs_ro` comes from `tawcroot_root_ro` in production; tests set
it directly. The `ro` result field (also mirrored into syscalls_fs.c's
`struct fs_path`) exists for the handful of *fidelity* consumers below
(statfs, linkat) — never for enforcement.

### Intent classification per call site

| Intent | Call sites |
|---|---|
| forced WRITE via mode (declaration irrelevant) | mkdirat, mknodat, symlinkat dst, unlinkat (both forms), renameat2 both operands, linkat dst, openat(O_CREAT\|O_EXCL) |
| declared WRITE | openat/openat2 per flags classifier (below), truncate, fchmodat, fchownat, utimensat (path form), setxattr/lsetxattr, removexattr/lremovexattr, AF_UNIX `bind()` sockaddr, faccessat/faccessat2 with `W_OK` (kernel returns EROFS for W_OK probes on RO fs — the central check produces exactly that) |
| declared READ | stat/lstat/fstatat/statx, readlinkat, getxattr/listxattr (+l variants), faccessat without W_OK, chdir, execve/execveat targets + loader interpreter opens, statfs, open-for-read, chroot target, AF_UNIX `connect`/`sendto`/`sendmsg` sockaddr (connecting to a socket on an RO fs is legal), linkat src (special-cased below), `tawcroot_open_in_view` |

**Required pre-adjustment in syscalls_socket.c**: today
`translate_unix_sockaddr` is shared by bind AND connect/sendto and
translates with `PARENT_CREATE` for all of them — under the
forced-write rule that would wrongly EROFS `connect` to a socket
inside an RO bind. Split the mode per caller: `bind()` keeps
`PARENT_CREATE` (it creates the socket file — forced write is
*correct* there); `connect`/`sendto`/`sendmsg` move to `FOLLOW` +
READ. The mode change is independently more kernel-faithful — the
kernel follows a leaf symlink when connecting, `PARENT_CREATE` does
not — but it is a behavior tweak to existing code and needs its own
hosted test (connect through a symlink to a socket).

`chown`/`chmod` note: their identity-virtualization shortcuts (fake
euid==0 swallows/skips the host call) sit *after* translation, so the
central EROFS fires first — root on a read-only fs gets EROFS on real
Linux too. No interplay code needed.

### openat flags → intent (pure helper, unit-tested)

```
write iff (flags & O_ACCMODE) != O_RDONLY   /* O_WRONLY, O_RDWR, and
                                               accmode 3 fail closed */
      || (flags & O_TRUNC)
      || (flags & O_CREAT)
```

Shared by `openat` and `openat2` (which additionally keeps its
existing RESOLVE_* handling; none of those add write paths). `O_PATH`
opens are reads (kernel-faithful; the O_PATH fd can't write — its
metadata uses are stage 2's problem). `O_APPEND` alone is NOT write
intent (kernel allows `O_RDONLY|O_APPEND` on RO fs; append only
matters on write-mode fds, which we never grant). `O_TMPFILE` requires
a write accmode anyway, and tmpfile publish is blocked at the linkat
dst side.

**O_CREAT-on-existing-file fidelity rule**: POSIX/Linux allow
`open(existing, O_RDONLY|O_CREAT)` on an RO fs. The classifier above
would EROFS it. `handle_openat` implements the exact semantics in one
place: when the central check returns EROFS and flags are
`O_CREAT`-without-`O_EXCL`, a write-free accmode, and no `O_TRUNC`,
retry the translation+open with `O_CREAT` dropped and READ intent; a
resulting `ENOENT` is rewritten to `EROFS` (kernel: creating on RO fs
→ EROFS). This is the only flags special case.

## Two-path syscalls

Both operands translate (and thus RO-check) **before** any host
attempt or emulation branch — enforcement precedes the linkstore's
EACCES-triggered emulation and the v1 rename fallback by construction.

- **renameat/renameat2**: old is PARENT_REMOVE, new is PARENT_CREATE —
  both forced write, uniform `EROFS` when either lands in an RO bind.
  (Kernel gives EXDEV for the cross-mount flavor; uniform EROFS is
  equally terminal for `mv` and simpler. Revisit only if a workload
  needs the EXDEV shape.)
- **linkat**: dst is PARENT_CREATE (forced write → EROFS when dst is
  RO — covers both-in-RO first). For a *source* in an RO bind with dst
  elsewhere, return **EXDEV**, deliberately:
  - Kernel-faithful for every cross-fs RO bind (system partitions,
    shared storage), and tools (`cp -al`, git) degrade to copy on
    EXDEV.
  - For a *same-fs* RO bind (app-data srcs — exactly the shared state
    this plan protects) the host linkat would *succeed*, handing out a
    rootfs-named hardlink whose content is then writable — the classic
    RO-bind-mount hardlink escape. We diverge from kernel fidelity
    here on purpose and refuse. Documented divergence.
  - Since the source translates with READ intent (translation must
    succeed to learn `result.ro`), this is a handler-level errno
    decision off the `ro` result bit — one of the two fidelity
    consumers of that bit.
  - The **AT_EMPTY_PATH / `/proc/self/fd/N` source spellings** don't
    go through the path translator, but the linkat handler *already*
    resolves the source fd's host path for linkstore source detection
    (link-emulation.md §"linkat source detection") — prefix-match that
    host path against RO bind srcs in the same step → EXDEV. Uses the
    same stateless helper as stage 2.
- **symlinkat**: only the dst is a path (forced write); the target
  string is opaque and untouched, as today.

## Fidelity niceties (cheap, same change)

- **statfs**: when `result.ro`, OR `ST_RDONLY` into `f_flags` before
  copying out — `df`, pacman free-space checks, and RO-detecting
  installers then see the truth. (Second fidelity consumer of the
  result bit.)
- **faccessat W_OK → EROFS** falls out of the intent table above; no
  handler code.

## Stage 2: fd-based metadata residue

Path-layer enforcement plus the kernel's fd-mode backstop leaves one
genuine residue: syscalls that mutate **metadata through an fd** that
was legitimately opened read-only through an RO bind, plus the
magic-link re-open. All are cold syscalls; all use one new pure-ish
helper `tawcroot_host_path_in_ro_bind(path, len)` (longest-prefix
match against active RO bind srcs — same shape as
`tawcroot_host_path_to_guest_abs`) fed by `/proc/self/fd` ground
truth. Stage 2 is separable: stage 1 alone already blocks every data
write and every namespace mutation.

- `fchmod` — currently untrapped by design (path-translation.md).
  Trap it (new filter entry + small handler): resolve the fd's host
  path, RO-prefix → `EROFS`, else raw passthrough.
- `fsetxattr` / `fremovexattr` — same treatment (currently untrapped;
  mostly `EOPNOTSUPP` on app-data anyway, but same-fs RO binds need
  the refusal).
- `utimensat(fd, NULL, …)` (futimens) — already trapped; the
  empty-path branch currently passes the dirfd through. Add the fd
  check there.
- `fchown` — already trapped and identity-virtualized; add the fd
  check on the forwarding (dropped-identity) branch. The fake-root
  branch should EROFS rather than fake success, matching the
  path-based chown behavior above.
- **`/proc/<pid>/fd/<n>` write-mode re-open**: a guest that holds an
  RO-bind file open read-only can ask for
  `open("/proc/self/fd/<n>", O_RDWR)`; the path routes through the
  `/proc` bind and the kernel re-opens the inode writable (host mount
  is RW, so the kernel won't refuse as it would on a real RO mount).
  In `handle_openat`, when intent is write and the translated route
  lands in the `/proc` bind with a suffix classifying as
  `<pid>/fd/<n>` (or `map_files/*`), readlink the magic link and
  RO-prefix-check the target → `EROFS`. Sibling-guest pids are covered
  identically (same readlink). This is the one check that cannot live
  in the orchestrator (the target is only known by asking the kernel),
  which is why it's called out explicitly rather than left implicit.

## Interaction with the linkstore

Mostly already safe by prior design: emulated names are never planted
inside binds (link-emulation.md §"Accepted deviations"), token routing
re-bases at the store fd (not a bind), and the store lives outside
every bind. The enforcement-before-emulation ordering above closes the
rest: linkat/rename operands are RO-checked before the v1 fallback
could ever `renameat2` a source out of an RO bind. The tmp-publish
path is blocked at the dst (PARENT_CREATE) like any create.

## What this does and does not promise (honesty)

This is **accident containment with kernel-shaped errors**, matching
tawcroot's overall stance ("not a security boundary",
notes/tawcroot/overview.md). Specifically NOT covered:

- A guest that attacks tawcroot in-process (mmap over the handler,
  `/proc/self/mem`) can bypass all of it — as it can bypass everything
  else. Kernel-enforced RO is exactly what the Landlock plan adds on
  ≥5.13 kernels (grant RO srcs read/exec rights only).
- `/proc/<pid>/root` and general cross-process `/proc` passthrough
  remain the documented view-escape caveats they are today; RO binds
  neither widen nor fix them (Landlock's territory).
- Resolver escape bugs (path-translation known gaps) that route a path
  *around* the bind match also route around the RO check — same trust
  base as containment itself, same Landlock answer.
- Stage-1-only deployments leave the fd-metadata residue open
  (fchmod/f*xattr/futimens on an fd opened before or through the RO
  bind) — metadata only, never data.

## Files touched

- `include/path.h`, `src/path.c` — intent enum, result `ro` field,
  `add_bind` flag, `tawcroot_root_ro`.
- `src/path_orchestrate.c`, `include/path_orchestrate.h` — ctx
  `rootfs_ro`, final-route RO check (the enforcement point).
- `src/syscalls_fs.c` — intent at every translate call, openat
  classifier + O_CREAT retry, linkat EXDEV + fd-source check, statfs
  decoration, utimensat fd branch (stage 2).
- `src/syscalls_socket.c` — intent at the translate call plus the
  bind-vs-connect mode split above; `src/chroot.c` — intent, and
  chroot stores `result.ro` into `tawcroot_root_ro`.
- `src/main.c` (`parse_bind_spec`), `src/supervisor.c`,
  `include/supervisor.h` — `:ro` parsing and plumbing.
- `src/exec_state.c`, `include/exec_state.h` — v6: bind flags +
  root_ro.
- Stage 2: `src/filter_build.c` (trap fchmod/fsetxattr/fremovexattr),
  a small handler home (syscalls_fd.c or syscalls_fs.c), the
  `host_path_in_ro_bind` helper in path.c.

Rough size: stage 1 ~300 LOC prod (most of it the mechanical intent
threading), stage 2 ~150.

## Tests

Per the maintenance contract (notes/tawcroot/status.md):

- **Unit (cleat, tests/unit/test_path_orchestrate.c +)**: RO bind +
  WRITE → -EROFS; READ ok; PARENT_* modes deny even with READ declared
  (the fail-closed coupling); memo-rewrite-into-RO-bind denied;
  symlink out of an RO bind into rootfs allowed for write (final-route
  wins); nested RW-under-RO bind writable; `rootfs_ro` ctx behaves as
  an RO root; reanchor preserves the flag. Plus the openat
  flags→intent classifier table and `parse_bind_spec` `:ro` forms.
- **Hosted handler matrix (tests/hosted/, new test_ro_binds.c)**: an
  RO bind fixture, then *every* path-bearing syscall exercised against
  it — writes/creates/removes/renames/metadata → EROFS, linkat source
  → EXDEV, reads/stats/readlink/getdents/exec → success; `access(W_OK)`
  → EROFS; `open(existing, O_RDONLY|O_CREAT)` → success;
  `open(missing, O_CREAT|…)` → EROFS; statfs shows ST_RDONLY; unix
  `bind()` → EROFS, `connect()` → works. The same matrix runs against
  an RW bind asserting *nothing* changed. This matrix — not code
  review — is what catches a wrongly-declared READ intent.
- **Integration (testhost + tests/integration)**: prod spawn with
  `-b …:ro`; RO persists across guest `execve` (exec_state v6 ferry);
  chroot *into* an RO bind then attempt writes at "/" → EROFS, reads
  ok; chroot into an RW bind from an RO root un-sets it.
- **Stage 2**: fchmod/futimens/fsetxattr on an O_RDONLY fd from an RO
  bind → EROFS; same ops on rootfs fds unaffected; `/proc/self/fd/N`
  O_RDWR re-open → EROFS; SCM_RIGHTS-passed fd still refused (the
  stateless design's signature win over the dropped taint table).
- **Device suite** (`tawcroot/test.sh --device`): the hosted matrix
  subset that runs there today, plus a pacman/Firefox smoke with the
  standard bind set marked RO where applicable (nothing should
  change — today's set is either genuinely host-RO or needs RW).

## Follow-ups unlocked (separate changes, not this plan)

- `ExternalBind` gains the reserved `writable`/`ro` flag + Manage
  binds UI; `TawcrootMethod.bindSpecs` emits `:ro`.
- `TawcInstaller` partial revert to binds for whole-dir assets
  (`/usr/lib/hybris`), keeping copies for merge-into-distro-dir files.
  Decide there whether the disk/update-churn win justifies it.
- Landlock plan threads `read_only` into per-bind `allowed_access`.
- statfs-level `f_flags` decoration for the RO *root* case if a
  workload cares.

## Doc updates (same change as implementation)

- notes/tawcroot/path-translation.md: new §"Read-only binds" — intent
  parameter, enforcement point, errno table, stage-2 surface.
- notes/installation.md §"Why copy, not bind": point at the primitive;
  update if/when the TawcInstaller revert happens.
- notes/external-binds.md + `ExternalBind.kt` comment when the app
  toggle lands.
- plans/tawcroot-landlock.md §"Interaction with read-only binds":
  flip to "landed first — thread the flag".
- This plan is deleted and folded into notes/tawcroot/ when done.
