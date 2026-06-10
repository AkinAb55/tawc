# Wipe consolidation: one deletion engine

Goal: **consolidate and simplify, not add layers.** Today there are three
independent wipe implementations that have already drifted apart; the fix is
one engine where every guard exists exactly once, plus a net *deletion* of
code. Nothing in this plan adds a new defense on top of an old one — where a
guard survives, it replaces the scattered copies.

## Current state (the drift is the bug)

Uninstall dispatches `method.wipe(installDir)` (`Installer.uninstall`), giving
three implementations:

| step | chroot (`RootfsCleaner`) | proot (`ProotMethod.wipe`) | tawcroot (`TawcrootMethod.wipe`) |
|---|---|---|---|
| kill processes | `ProcessScanner.killAllInRootfs` loop | own `pkill -f` on proot argv | nothing |
| mount gate | strict unmount, refuses delete | none (`-xdev` only) | none (`-xdev` only) |
| chmod first | no (deletes as root) | yes, su-preferred | yes, app-uid only |
| crash-safe two-pass (rootfs → metadata → rmdir) | yes | yes | **no** — one `find` over installDir, metadata ordering is readdir luck |

Each copy re-derives the same safety reasoning in comments, and
`RootfsCleaner`'s kdoc claim ("the one and only path that deletes anything
under `<distros>/<id>/`") is false. Two recorded rationales for the drift are
stale:

- `ProcessScanner.kt` says proot's `pkill -f` "pairs" with the scanner to
  cover the supervisor (tracer) the scanner can't match. There is no pairing —
  proot's wipe never calls the scanner. And the scanner already grew the hook
  that closes the gap: `extraCmdlinePath` (added for the install-cancel sweep)
  matches the tracer's argv exactly like `pkill -f` does.
- tawcroot's "no kill needed, PDEATHSIG handles it" contradicts
  `rootfs-tmp-sweep.md`'s observation that `setsid`'d guests from a previous
  app process outlive it unreliably reaped. A live guest holding fds into the
  rootfs during delete is the same vold-FUSE/ANR hazard `RootfsCleaner`
  documents for chroot — it is method-independent, and nothing kills tawcroot
  guests on the normal (non-cancel) uninstall path today.

Also: `-xdev` is quietly load-bearing for proot/tawcroot, but it only refuses
to cross *filesystems*. A bind mount with a same-filesystem source (anything
under `/data`, e.g. the app share dir) has the same `st_dev` and `find -xdev`
walks straight through it. Only chroot's unmount gate actually covers that
case, and only for in-app wipes.

## Design

One engine (keep the `RootfsCleaner` name and file; it absorbs the other
two). `InstallationMethod.wipe()` is **removed** from the interface — methods
can no longer delete. They contribute two capability facts instead of code:

- `usesKernelMounts` (chroot: true) — engine must unmount before deleting.
- `runsGuestsAsRoot` (chroot: true) — engine needs `su` to scan/kill/delete.

The engine sequence, each step written once:

1. **Containment assertion.** Refuse unless `installDir` is exactly
   `<store.baseDir>/<id>` with a valid id. Protects every future caller from
   a path-computation bug handing the engine `/data/data/me.phie.tawc` or
   worse. (New, but it *replaces* the implicit "trust the caller" contract of
   three call sites with one checked contract.)
2. **Kill guests** via `ProcessScanner.killAllInRootfs(rootfsPath, id,
   includeChroot = runsGuestsAsRoot, extraCmdlinePath = installDir)` — the
   exact call the install-cancel path already makes for all methods. This
   retires proot's `pkill -f` (covered by `extraCmdlinePath`) and fixes
   tawcroot's missing kill. Fix the stale "pairs"/PDEATHSIG prose in
   `ProcessScanner.kt` while touching it.
3. **Mount gate, uniform.** Resolve `realpath(installDir)`, read
   `/proc/self/mounts`, and **refuse to delete if any mount entry sits under
   it** — every method, every wipe. For `usesKernelMounts` methods, run
   `ChrootMounter.unmount()` first (its strict-verify behavior is unchanged);
   for the others a hit means something external leaked a mount and a refused
   wipe is the correct, loud outcome. This makes the gate the load-bearing
   protection everywhere and demotes `-xdev` to incidental. The app-side
   mounts view can in principle miss a global-namespace mount on rooted
   devices; `usesKernelMounts` + `unmount()`'s `su -mm` verify covers the one
   method that can create such mounts. Do not add more namespace checks than
   that.
4. **Delete, one primitive.** Ownership-aware ladder:
   - su-first when `runsGuestsAsRoot` (an app-uid pass over a root-owned
     chroot rootfs is doomed and noisy), app-uid-first with one su retry
     otherwise (covers root-owned droppings from interleaved debug use; root
     is necessarily available on any device that can have created them).
   - `chmod -R u+rwX` before the app-uid pass (mode-0500 bootstrap dirs).
   - Pass 1: `find '<rootfs>' -xdev -depth -delete`. Keep `-xdev` — it costs
     nothing — but no comment may call it sufficient; the gate in step 3 is
     the guard.
   - Pass 2: explicit `metadata.json.tmp` → `metadata.json` → `rmdir`, so a
     cancelled wipe always leaves a slot the home screen can still show.
     Tawcroot gets this crash-safety for free; today it lacks it.
5. **Tripwire test.** A JVM unit test scans `app/src/main` for recursive
   deletion primitives (`deleteRecursively`, `rm -rf`, `find … -delete`)
   outside the engine file, with a short allowlist for known non-distro uses
   (`CompositorService.atomicReplaceDir`, `TawcInstaller.removeFromRootfs`).
   Turns "one and only deleter" from kdoc aspiration into a failing test for
   the next person who writes a reset-rootfs feature with
   `File.deleteRecursively()`.

## What gets deleted

- `ProotMethod.wipe` and `TawcrootMethod.wipe` entirely (~150 lines), plus
  the `pkill -f` variant and the per-method `runShell` chmod/delete plumbing
  they carried.
- `wipe()` from the `InstallationMethod` interface.
- Three copies of the two-pass/`-xdev` reasoning comments; the surviving copy
  lives in the engine and stops overselling `-xdev`.

Deliberately retained (existing redundancy, kept because it is free and
already paid for — not new belt-and-suspenders): `-xdev` in the find, and the
single su retry in the delete ladder.

## Invariants to restate where they live

- External binds are tawcroot **path rewrites, never kernel mounts**
  (`notes/external-binds.md`). That invariant — not this engine — is what
  protects Android-side data on OS-level uninstall, where no app code runs.
  The engine's uniform gate is the wipe-time backstop if the invariant is
  ever broken (e.g. external binds wired into chroot); add a line to
  external-binds.md saying so.
- Proot/tawcroot wipes must stay off the `su` path when nothing root-owned is
  involved (no Magisk prompt) — preserved by the capability flags and the
  scanner's existing `includeChroot` gate.

## Testing

Existing uninstall integration tests (incl. `external_binds.rs`'s
contents-survive-uninstall) cover the happy paths. Add: a wipe-refused test
with a synthetic mount entry under the rootfs (emulator, root) proving the
uniform gate refuses for a *tawcroot* install; and a root-owned-droppings
test (su-create a root file in a tawcroot rootfs, uninstall, expect the su
retry to clear it). The tripwire source-scan test runs with the normal app
unit tests.
