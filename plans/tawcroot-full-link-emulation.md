# tawcroot: Full Hardlink Emulation

Android `untrusted_app` SELinux denies `link` on `app_data_file` (see
`notes/proot.md`). Today's partial fallback (`link_with_symlink_fallback`,
`tawcroot/src/syscalls_fs.c`) renames the real file to the NEW name and
leaves a guest-absolute symlink at the old name. That direction keeps the
two destructive idioms correct (publish: `link(tmp,final);unlink(tmp)`;
backup: `link(f,f.bak);rename(new,f)`) and survives git and full Debian
installs, but it is guest-observably not a hardlink:

1. NOFOLLOW stats report a symlink, not a regular file (tar/rsync/find
   walk it as one).
2. `st_nlink` stays 1; the names report different inodes on NOFOLLOW
   stats. `link()`-then-`st_nlink==2` lock protocols break.
3. `unlink(new)` dangles the old name.
4. `open(old, O_NOFOLLOW)` fails ELOOP.
5. `readlink(old)` succeeds where hardlinks give EINVAL.
6. `rename(tmp, new)` makes the old name read the new bytes.
7. Re-linking builds symlink chains.
8. Bind-mount destinations get wrong (rootfs-rooted) symlink targets.

## Goal

`link`/`linkat` in the rootfs indistinguishable from real hardlinks for
guest-observable behavior — shared inode, live `st_nlink`, any-order
unlink, regular-file NOFOLLOW/readlink semantics, no visible machinery —
except the deviations accepted below. proot/chroot compatibility of the
resulting rootfs is explicitly NOT a goal; only tawcroot needs to
understand the on-disk state.

## Design overview

A hardlinked file's data lives in a **link object** stored *outside the
guest tree*. Every guest name for it is a symlink whose target is an
**opaque token**, not a path. tawcroot intercepts every consumer of those
symlinks (resolution, readlink, NOFOLLOW stats/opens/attrs), so the guest
sees N regular-file names sharing one inode. A per-store **flock**
serializes mutations; a single-slot **intent file** makes every
multi-syscall mutation crash-recoverable, self-healing, with no
full-tree sweep anywhere.

## On-disk layout

```
distros/<id>/
  rootfs/          guest tree (mount root) — guest files only
  metadata.json    (existing, installer-owned)
  tawcroot/        tawcroot-owned emulation state
    version        store format version (ASCII integer; see below)
    lock           mutation lock (flock; created once, never deleted)
    intent         single-slot crash journal (+ intent.new rename twin)
    link/<token>   link objects (hardlink cluster data)
    link/<token>.cnt  refcount sidecars (the only count store)
    work/<token>   transient staged/parked name symlinks (see intent)
    tmp/<ino>      linkable O_TMPFILE files awaiting publish
```

Why out-of-tree: the rename primitive needs the object on the same
*filesystem* as the guest file, not inside the guest *tree*, and
`distros/<id>/` is on the same fs as `rootfs/`. Path translation already
confines guest paths to the rootfs fd, so the guest structurally cannot
name anything under `tawcroot/` — which deletes the entire hiding
apparatus an in-tree stash would need (reserved names, getdents
filtering, post-resolution lookup blocks, collision policy). Same-fs
bind sources (normal case: other /data dirs) share this one store; the
mount distinction dissolves because rename works across the whole fs.

`tawcroot/` is generic metadata with link emulation as first customer.
`lock` + `intent` are subsystem-neutral, but they are a **paired
unit**: the single-slot intent is sound only because that one lock
admits one mutation at a time across all intent users, so one
serialization domain = one lock + one intent slot, always together.
Future multi-syscall emulations (mknod, whiteouts) join the existing
pair — everything behind it is cold-path. Single-syscall metadata
updates (e.g. ownership persistence as one per-file xattr write) need
neither lock nor intent and must not take them; that keeps the one
plausible warm-path customer outside the lock by construction. If a
hot multi-syscall customer ever appears, it gets its own lock+intent
pair (a second domain) under the standing rule: never hold two domain
locks at once.

**Invariant:** `rootfs/` alone is no longer self-contained — hardlinked
data lives in `tawcroot/link/`. Backup/export/migration must treat
`distros/<id>/` as the unit (already true of install management; must be
documented in notes/ when this ships).

Token = the file's inode number (known before the rename, which
preserves it; unique per fs among live files). Create objects with
RENAME_NOREPLACE and retry with a suffixed token on EEXIST — a
host-level copy of a distro renumbers inodes, so a fresh token can
collide with a stale baked name; plain rename would silently destroy an
existing object.

## Store versioning

`tawcroot/version` (ASCII integer) is written when the store is first
created and read once at session start, alongside the intent check.
It guards not forward migration (new code can always be taught old
formats) but **old code mutating a newer store** — an APK downgrade or
an imported `distros/<id>/` — blind to invariants it doesn't know
(e.g. a new sidecar that must stay consistent with the object being
deleted). That only works if the check ships in the first release:
"absence means v1, add the file at the first bump" fails because v1
binaries never look.

- version == supported → proceed.
- version < supported → migrate under the store lock; bump the file
  last, so a killed migration re-runs (same recheck-then-act
  discipline as intent recovery).
- version > supported → disable store *mutations* only: link fallback
  degrades to raw EPERM, unknown emulated names surface as dangling
  symlinks. Degraded guest behavior, zero corruption, legible in logs.

One number for the whole store — subsystems are cold and migrations
rare. Formats living outside the store are versioned in-band instead:
symlink targets are self-describing (a future format is a new
`tawcroot:` prefix recognized alongside the old — the v1 path-target
symlinks already work this way), and xattr names can do the same. Most
evolutions therefore never bump the store version; it gates the
store's internal invariants.

## Name symlinks: opaque tokens

Target text: `tawcroot:link:<token>`, exact literal. It never resolves
as a path; the resolver detects the prefix and maps the token to
`(link-dir fd, token)` directly, via store fds captured at init.
Consequences, all by construction:

- **Location-independent.** Renaming a name, its ancestors, or whole
  subtrees never breaks links (the v1-style path targets and proot-style
  relative targets both fail some rename shape).
- **Guest-chroot immune.** `chroot(2)` emulation (`chroot.c`) swaps the
  root view; the store fds and token mapping don't route through it.
  pacman 6.x's chroot hop is a live workload, so this is core, not edge.
- **Fails loudly** outside tawcroot (a path-shaped target that
  half-resolves under some other runtime is worse than one that
  obviously dangles). Acceptable per the non-goal above.

Forgery guard: detection-by-target-text is only sound if guests can
never author it — a forged symlink is a phantom referrer whose unlink
decrements a count it never contributed to (the one route to data
loss). So `symlinkat` rejects guest targets beginning with `tawcroot:`
(EPERM). rename preserves target text so can't mint one; archives can't
contain one because `readlink` on emulated names is intercepted (the
literal never appears in guest-readable output, so legit tars are
self-consistently clean).

`readlink` on an emulated name: EINVAL ("it's a regular file") — except
when the object is itself a symlink (hardlink-of-a-symlink, e.g. from
`rsync -H`): then forward the object's real target, and stats report the
object's real mode rather than hardcoding S_IFREG. The resolver likewise
splices the object's target and continues the guest-side walk when a
FOLLOW hop lands on a symlink object.

## Refcounts

Sidecar-only: the count for `link/<token>` lives in `link/<token>.cnt`
in the store, mutated only under the lock. An xattr count
(`user.tawc.nlink` on the object) was considered and dropped: sidecars
are mandatory anyway (the kernel rejects `user.*` xattrs on
non-regular files — EPERM on symlinks, FIFOs, device nodes — and
hardlink-of-a-symlink is in scope), the codebase already expects
xattrs to EOPNOTSUPP on app-private storage (`syscalls_fs.c`
xattr-handler comment), and the xattr path's only payoff over the
mandatory sidecar was ~2 saved syscalls on cold reads. One store, one
code path.

Consequences:
- No emulation state lives in xattrs → the guest xattr surface needs
  *nothing*: no `user.tawc.*` filtering, no trapping of the fd-based
  `f*xattr` family (deliberately untrapped today,
  `syscalls_fs.c:1532`), and no way for xattr-copying tools
  (`cp --preserve=xattr`, rsync `-X`) to read or corrupt a count. Any
  future subsystem that does store state in `user.*` xattrs must add
  that filtering (path and `f*xattr` variants) with it.
- First link always creates/overwrites the sidecar, never trusts a
  stale one — host-copied distros can bake stale `.cnt` files against
  renumbered inodes (the NOREPLACE token retry covers the object
  side).
- Missing sidecar (e.g. a partial host-side copy): report `st_nlink` 2
  and never delete the object — degraded but data-safe. A count that
  cannot be maintained degrades the same way.
- Count reads cost open+read+close on the sidecar — paid only on stats
  of emulated names, cold.

## Concurrency: the store lock

tawcroot handlers run in-process per guest thread (no proot-style tracer
serialization), and refcount read-modify-write races across processes
can undercount → premature object deletion → data loss. So: one
`flock(LOCK_EX)` on `tawcroot/lock` around every structural mutation.

Why this exact shape:
- **flock, because the threat model is SIGKILL** (LMK, force-stop) —
  the kernel releases the lock when the holder dies. Shared-memory
  futexes wedge forever; create/delete sentinel files wedge on a stale
  sentinel.
- **Not on the object's own inode** — the guest sees that inode as the
  linked file and may legitimately flock it; flock excludes across fds
  even within one process, so the emulation would self-deadlock against
  the guest's own lock. The lock file is an inode the guest can never
  address. Each acquisition opens its own fd — flock attaches to the
  open file description, so threads sharing one fd would not exclude
  each other.
- **Coarse (per store), because mutations are cold** (dpkg/pacman
  bursts, git gc — never hot loops), critical sections are bounded
  handler code that never returns to guest code while holding, and a
  single lock needs no ordering analysis. Per-cluster locks are the
  escalation if contention ever measures, at the cost of a lock-file
  lifecycle problem; don't start there.
- **Readers are lock-free.** Stats/opens tolerate racing a teardown; an
  ENOENT there is indistinguishable from the unlink serializing first.
  Locks serialize; they don't make sequences atomic — that's the intent
  slot's job.

## Crash safety: the intent slot

No POSIX primitive updates a directory entry and a count atomically, and
a process can die between any two syscalls. Two mechanisms make every
reachable state safe and self-healing, with recovery O(one operation),
never O(tree):

**Monotone ordering invariant:** the stored count is always ≥ live
referrers. Increment *before* a name appears; decrement *after* a name
is gone. Every kill window therefore errs toward a leak (object kept too
long), never a deficit (object deleted while referenced).

**Single-slot intent file.** Because the lock admits one mutation at a
time, and every lock holder repairs any leftover intent before writing
its own, at most one pending intent can ever exist — so the "journal" is
one small file, not a log. Protocol per mutation (CLOBBER opts out of
the record — see its row):

1. `flock(LOCK_EX)`.
2. Read `intent`; non-empty → a holder died mid-operation → run
   recovery, clear the slot.
3. Write own intent (op, token, rootfs-absolute name paths — recovery
   at session start has no guest cwd/chroot context — and
   **count-before**, authoritative for the *count* because the lock
   excludes concurrent mutations) via `intent.new` + rename, so the
   slot is never torn.
4. Execute the operation's syscalls in monotone order.
5. Clear the slot, unlock.

**Recovery keys on store-internal state only.** Guest-tree paths are
unreliable witnesses: plain renames — of files, of emulated names, and
especially of *directories* — never take the lock, so between crash and
recovery a recorded name can move or its path be reoccupied.
Path-keyed recovery ("does the recorded name still exist?") then
mis-answers "did the op complete?", and in the undercount direction
that deletes a still-referenced object — data loss. So each op routes
its guest-visible directory-entry step through `work/` with one atomic
rename, recovery reads only `work/`, `link/`, and the intent, and
where a window is still ambiguous it rolls to the safe side
(overcount/leak, never undercount). Recovery is recheck-then-act with
absolute values and idempotent, so recovery itself can be killed and
re-run; any guest-tree entry it does touch is verified to be the
*matching token* symlink first, never assumed:

- `ADD tok name n` — stage a token symlink at `work/<tok>`, count :=
  n+1, `RENAME_NOREPLACE(work/<tok> → name)` (atomic appearance;
  link()'s EEXIST for free), clear. Recovery: staged present → roll
  back (count := n, delete staging); staged absent → count := n+1
  (either completed, or crashed pre-staging: +1 leak, safe).
- `DEL tok name n` — park via `rename(name → work/<tok>)` (atomic
  disappearance), count := n−1, at 0 unlink the object (+sidecar),
  unlink parked, clear. Recovery: parked present → roll forward
  (count := n−1, at 0 unlink object, unlink parked); parked absent →
  if the object exists, count := n (a crash after the parked unlink
  overcounts by 1: leak, safe), clear.
- `NEW tok src dst` — pick the token first (under the lock,
  `link/<tok>` free), stage *both* names as token symlinks —
  `work/<tok>.dst`, and `work/<tok>.src` strictly before the object
  rename (the exclusion argument below depends on it). Then:
  `RENAME_NOREPLACE(work/<tok>.dst → dst)` (EEXIST → clean abort), set
  count 2, `RENAME_NOREPLACE(src-file → link/<tok>)` — the point of no
  return — `RENAME_NOREPLACE(work/<tok>.src → src)` (EEXIST from a
  create racing the one-syscall vacancy → park the entry; count stays
  high: safe leak). Recovery: object present → roll forward by
  *consuming still-staged entries only* — publish each via
  RENAME_NOREPLACE to its recorded path, parking on EEXIST or a
  vanished parent. Never recreate a name from a recorded path: a
  publish atomically destroys its staged entry, so staged-absent is a
  store-internal witness that the publish happened exactly once (for
  the trailing src entry, "never staged" is excluded by the object's
  presence — hence the ordering constraint). Recreating a name whose
  original was moved by a lock-free guest rename would mint a phantom
  referrer: two unlinks later the count hits zero under a live name —
  undercount, data loss. Object absent → roll back: delete staged
  entries, and delete the published dst symlink at its recorded path
  only if it verifies as the matching token symlink (moved →
  content-safe strand; the object never existed). The brief window
  where dst dangles reads as ENOENT through our own handlers —
  consistent with "not linked yet".
- CLOBBER (rename onto an emulated name) — writes **no intent**. It
  cannot be store-keyed without breaking rename's atomic-replace
  guarantee to observers, and under conservative recovery ("never
  decrement") an intent record is actively harmful: restoring
  count-before after the holder's decrement already landed would
  re-increment a correct count. So: take the lock (recovering others'
  pending intents as always), rename, decrement, at 0 unlink object +
  sidecar. Crash windows degrade to a +1 overcount or a count-0
  orphan object — rare, safe direction, strictly no worse than the
  with-intent variant.

Single-syscall mutations (RENAME_EXCHANGE of name symlinks, O_TMPFILE
publish rename) are atomic in the kernel and need no intent.

Self-healing hooks: recovery runs at every lock acquisition, plus an
O(1) session-start check (read `intent`; non-empty → lock and repair).
No sweep, no user action; the worst interim state is one object with a
count one too high, guest-visible only as a transiently wrong
`st_nlink` until the next mutation or session start heals it.

No fsync: the threat is process death, and the page cache outlives the
process, so the intent write is reliably ordered before the operation.
True power loss can reorder them; the monotone invariant still holds
independently, so the worst case is one permanently leaked object —
accepted (an optional fsync of the intent would close even that).

Residue of emulating an atomic syscall with several: NEW has a
one-syscall window where the old name is absent. Recovery touches
guest paths only to move staged entries out (RENAME_NOREPLACE) or to
verified-match-delete a rollback symlink — never to create a name from
a recorded path — so no interleaving of a crash with lock-free guest
renames can mint a phantom referrer. What remains: a parked staged
entry or a moved rollback symlink leaves a marooned nameless object
(kept, never deleted) or one stale strand — bounded, content-safe,
accepted below.

## Syscall surface

Emulated-name detection is reactive: NOFOLLOW handlers check the leaf
only when it stats as a symlink (+1 readlinkat), so non-symlink hot
paths pay nothing; FOLLOW paths pay nothing at all (the resolver already
readlink-probes every component and just learns one new target form).

- `linkat` — detect an emulated-name source **before** the host
  attempt (one readlink probe, cold): symlinks are a different SELinux
  class (`lnk_file`), so the host linkat might *succeed* and hardlink
  the token symlink itself — a phantom referrer the count never learns
  about, and wrong semantics even in isolation. Emulated source →
  `ADD` (no chains). Source that is an open link object
  (`/proc/self/fd/N` resolving into `link/`, or AT_EMPTY_PATH whose
  inode matches an object — the lookup must verify the candidate's
  `st_ino`, since suffixed tokens break the ino↔token bijection after
  a host-side copy) → `ADD`. Otherwise host linkat first; on
  EACCES/EPERM emulate. Directory sources still pass the kernel's
  EPERM through. Source with no name (O_TMPFILE fd, not one of our
  `tmp/` objects) → deliberate EXDEV (see O_TMPFILE).
- `unlinkat` — leaf emulated → `DEL`.
- `renameat2` — old and new resolving to the *same* token → POSIX
  no-op: return 0, both names remain (the kernel does this check by
  inode; we do it by token). Dst clobbers an emulated name →
  `CLOBBER` + rename. Src emulated → plain rename IS correct (opaque
  targets are location-independent). RENAME_EXCHANGE → atomic, no
  count change.
- `newfstatat`/`statx` — NOFOLLOW leaf emulated → stat the object:
  real mode, shared `st_ino`, `st_nlink` from the count. FOLLOW stats
  landing in the store (result base fd == link-dir fd) → same nlink fix.
- `openat` `O_NOFOLLOW` (incl. `O_PATH`) — leaf emulated → open the
  object instead of ELOOP. O_CREAT|O_EXCL on an emulated name gets
  EEXIST naturally.
- `readlinkat` — EINVAL / forward-if-symlink-object (above).
- `utimensat`/`fchownat` with NOFOLLOW — apply to the object.
  (`fchmodat` has no NOFOLLOW flag at the syscall level; `fchmodat2`
  stays ENOSYS. `faccessat2` with flags stays ENOSYS by existing
  policy, `syscalls_fs.c:679` — Android's own seccomp traps it — and
  libc's fallback emulation lands on our fixed stat handlers.)
- `symlinkat` — forgery guard (above).
- xattr syscalls (path and fd variants) — **untouched**; sidecar-only
  counts mean no emulation state is xattr-addressable (see Refcounts).
- `getdents64` — nothing hidden lives in-tree, but `d_type` for
  emulated names says DT_LNK, and type-trusting walkers (`find -type
  f` via fts FTS_NOSTAT, fd, ripgrep) never stat — gap 1 would
  survive stat interception. Rewrite DT_LNK → DT_UNKNOWN for
  rootfs-view directories (in-buffer byte flip in the already-trapped
  handler, zero extra syscalls; the per-call dirfd readlink probe
  already exists), forcing type-curious callers to stat into the
  fixed-up handlers. `d_ino` stays the name symlink's inode — accepted
  deviation.

## O_TMPFILE

The publish idiom (`open(O_TMPFILE)` … `linkat` the fd its first name)
is a hardlink of a *nameless* inode: SELinux denies it identically, and
rename-based emulation needs a name. Split on O_EXCL:

- `O_TMPFILE|O_EXCL` ("will never link"): **pure passthrough** — anon
  file creation is allowed (only `link` is denied), fstat nlink 0 is
  exact, and a linkat attempt fails ENOENT in-kernel just like the real
  thing. Zero new code.
- `O_TMPFILE` without O_EXCL (linkable): create `tawcroot/tmp/<ino>`
  and return its fd. Publish linkat (source resolves through
  `/proc/self/fd/N` to a `tmp/` path, or AT_EMPTY_PATH + fstat →
  inode-keyed O(1) lookup) = `renameat2(tmp → dst, NOREPLACE)`: atomic,
  reproduces linkat's EEXIST, and the still-open fd references the same
  inode so post-publish writes land at the destination (which
  copy-based emulation gets wrong). A second linkat after publish
  works via the `/proc/self/fd/N` spelling (the magic link tracks the
  rename to the guest path → normal emulation); the AT_EMPTY_PATH
  spelling misses the inode lookup post-publish → EXDEV, accepted.
  Never-linked strays are cleaned at session start by listing `tmp/` —
  O(one directory) — **gated on verifying the session model tears down
  all guest processes first** (spike item: LMK kills processes, not
  process trees; a guest surviving into a new session would have its
  live temp unlinked, degrading its publish to EXDEV — no data loss,
  but verify rather than assume).

Prevalence check (informs priority): scratch dominates — `tmpfile(3)`
and Python `tempfile` use O_TMPFILE and never link; publish users are
ostree/flatpak, systemd components, casync. git/dpkg/apt/pacman publish
via named temp + rename and never hit this. So until the tmp/ stage
lands, the deliberate-EXDEV fallback for anonymous sources is
low-impact, and denying O_TMPFILE at open (breaking scratch to fix
publish) is firmly rejected.

## Bind mounts and guest chroot

Same-fs binds (the normal case): covered by the one store; gap 8
disappears because nothing is path-addressed. True cross-fs binds: link
within them cannot reach the store (EXDEV on rename) — unsupported
initially, degrading to today's behavior; the documented escalation is a
per-fs in-tree `/.tawcroot` stash, which drags the full hiding apparatus
back for that mount only, so don't build it speculatively. Guest
`chroot(2)`: immune via opaque tokens + init-captured store fds (above).

## Legacy v1 artifacts

Existing rootfses contain v1 fallback symlinks (guest-absolute path
targets). They don't match the token form, so they keep behaving exactly
as today — ordinary symlinks, no migration, no misdetection. New links
on top of them treat them as plain symlink sources.

## Accepted deviations

- `fstat(fd)` / `statx(fd, "", AT_EMPTY_PATH)` report the object's real
  `st_nlink` (1). Fixing it would need an fd→count lookup on
  essentially every regular-file fstat (hot — and counts are sidecars,
  so there is no fd-addressable store at all); no known consumer
  compares fd-nlink to path-nlink. Revisit only on evidence.
- Non-O_EXCL O_TMPFILE: fstat nlink 1 (not 0); `/proc/self/fd` readlink
  shows a live `tmp/` path (no " (deleted)"); churn-heavy never-linking
  users accumulate invisible temps until session end (best-effort eager
  cleanup via a high-fd close-trap dup is a documented refinement, not
  initial scope).
- One-syscall old-name-missing window during first-link.
- Transiently high `st_nlink` after a crash, until healed. A crashed
  CLOBBER leaves a permanent +1 count (leak, safe direction); a crashed
  NEW whose staged publish can no longer land (parent vanished, path
  reoccupied) parks the entry — a marooned nameless object or one stale
  strand, leak-only by construction (roll-forward consumes staged
  entries; it never recreates names by path).
- Power loss (not process death) can leak one object permanently.
- Anonymous-source linkat without a `tmp/` object: EXDEV. Likewise
  AT_EMPTY_PATH re-link of an already-published tmpfile fd (the
  `/proc/self/fd/N` spelling works).
- fd-path introspection of emulated hardlinks leaks store paths:
  `readlink(/proc/self/fd/N)`, `/proc/self/maps`, `/proc/self/exe`
  reverse-translate only rootfs/bind prefixes (`proc_rewrite.c:57`),
  and the store is a sibling of `rootfs/`, so they return raw host
  paths the guest can't resolve — the documented Bun-realpath class
  (`syscalls_fs.c:636`). Directories are never emulated, which
  excludes the common realpath-of-dir flow; the exposure is fd
  introspection of hardlinked files, mapped hardlinked libraries, and
  hardlinked binaries as `/proc/self/exe` (git's hardlinked
  `git-core/` layout is the likely first tripwire). No sound reverse
  map exists without token→name tracking, which the store deliberately
  omits; escalation if a real workload breaks: a best-effort
  primary-name hint on the object.
- `d_ino` in directory entries is the name symlink's inode (stat gives
  the shared object inode).
- Cross-fs binds: no emulation.

## Rejected alternatives

- **Copy-on-link:** breaks shared-inode writes, doubles I/O and peak
  disk (git packs), still lies about `st_nlink`.
- **Raw EPERM:** libarchive/pacman hardlink extraction hard-fails — the
  original motivation (`notes/proot.md`).
- **FUSE / mount tricks:** need privileges `untrusted_app` lacks.
- **proot-style same-directory hidden files** (this plan's previous
  shape, from proot's link2symlink): strands still-referenced hidden
  files in guest directories (`rm -rf` of a `cp -al` source hits
  ENOTEMPTY on an apparently-empty dir), ancestor renames dangle
  cross-dir targets, its same-directory-scoped GC deletes live
  cross-directory data, and hiding scattered names needs per-entry
  getdents filtering everywhere.
- **In-tree stash at the mount root:** fixed the above but still needed
  the full hiding/reservation apparatus; superseded by out-of-tree
  `tawcroot/` once proot/chroot compat was dropped as a goal.
- **Path-shaped symlink targets:** root-view-dependent (break under
  guest chroot — a shipped workload) or depth-dependent (relative);
  opaque tokens are immune to both.
- **Full-sweep GC:** O(tree), needs quiescence, and scoped variants are
  unsound; replaced by the intent slot (O(1), self-healing).
- **Coordinator daemon / central metadata DB:** the fs must stay the
  source of truth (guest processes die mid-op, so any index drifts and
  must be rebuilt from disk — the daemon is just a cache with a
  lifecycle, an availability dependency, and fakeroot/pseudo's
  db-corruption fragility). Serialization, the only other thing it
  offers, is one flock.

## Staging

1. **Spike (½ day):** whether SELinux denies hardlinking *symlinks*
   (`lnk_file` is a separate class from the documented `file` denial);
   whether the session model guarantees guest-process teardown (tmp/
   sweep precondition); confirm flock and O_TMPFILE-passthrough
   assumptions on device.
2. **Core:** `tawcroot/` layout + store fds at init, version file +
   refuse-if-newer check, lock, intent slot + `work/`-staged mutation
   protocol + store-keyed recovery, opaque-token detection in the
   resolver, linkat (emulated-source detection before the host
   attempt) /unlinkat/renameat2 (incl. same-token no-op)
   /fstatat/statx, symlinkat forgery guard, DT_LNK → DT_UNKNOWN dirent
   rewrite. Hosted tests: fault-injection fidelity matrix (publish,
   backup, farm, any-order unlink, st_ino/st_nlink, rename-over,
   d_type walk) plus a **kill-matrix harness** — run every mutation,
   kill after syscall k for every k, run recovery, assert invariants —
   including kill-then-guest-rename interleavings (published names and
   parent dirs) for the store-keyed discriminators — in particular the
   fully-published-NEW crash followed by a rename of either name. Closes gaps 1–3 and 6–8; the bulk of the value.
3. **Edge surface:** O_NOFOLLOW/O_PATH opens, readlink semantics,
   NOFOLLOW utimens/chown, CLOBBER + RENAME_EXCHANGE,
   hardlink-of-symlink, linkat from an open link-object fd
   (st_ino-verified lookup), deliberate EXDEV for anonymous linkat
   sources.
4. **O_TMPFILE:** `tmp/` objects, publish rename, session-start sweep.
5. **Integration + hygiene:** session-start recovery hook wiring; device
   tests (git clone, tar round-trip of a hardlinked tree, pacman package
   with hardlinks, `cp -al` farm, hardlinks inside a guest chroot);
   cross-process race stress test (hosted kill-matrix can't exercise
   real races); perf spot-check (NOFOLLOW stats on symlink-heavy trees);
   document the `distros/<id>/`-is-the-unit invariant and the store
   format in notes/.

Each stage is shippable.
