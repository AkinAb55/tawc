# Default binds read-only

Make tawcroot's built-in binds use the new `-b SRC:DST:ro` primitive
where the guest never legitimately writes. Two independent pieces that
share one mechanism:

1. **System-partition binds → `:ro`** — small, low-risk, kernel-faithful.
2. **App-shipped assets: copy → RO bind** — a follow-on cleanup that
   trades disk/upgrade-churn for a whole-dir RO bind.

Neither touches the user-facing `ExternalBind` feature; that is
[tawcroot-user-ro-binds.md](tawcroot-user-ro-binds.md).

**Status: plan, not started.** The RO primitive itself already ships
(`-b SRC:DST:ro`, enforced centrally at the translation layer —
notes/tawcroot/path-translation.md §"Read-only binds"). This plan is
purely about the Kotlin side choosing to emit `:ro`.

## Current state

`TawcrootMethod.bindSpecs()`
(app/src/main/java/me/phie/tawc/install/TawcrootMethod.kt:319-337)
emits every bind 2-field (RW) via `rootfsArgv()` (`:223`, `add(listOf(
"-b", "$src:$dst"))`). Nothing produces a `:ro` suffix today. The
built-in set, in order:

| Bind | Writable needed? |
|---|---|
| `/dev`, `/proc`, `/sys` | **Yes** — ptmx, `/proc/self`, `/sys` tunables. Stay RW. |
| `LIBHYBRIS_BIND_DIRS`: `/apex`, `/vendor`, `/system`, `/system_ext`, `/linkerconfig` | **No** — dlopen targets, read-only on host Android. → piece 1 |
| `tawcShare → /usr/share/tawc` | Yes — guest-writable, Wayland/X sockets. Stay RW. |
| ando socket dir → `/run/tawc-ando` | Yes — socket. Stay RW. |
| `$tawcShare/xtmp/.X11-unix → /tmp/.X11-unix` | Yes — X server creates the socket. Stay RW. |

App-shipped assets (libhybris) are **copied** into each rootfs, not
bound — see notes/installation.md §"Why copy, not bind". → piece 2.

## Piece 1 — system-partition binds `:ro`

`LIBHYBRIS_BIND_DIRS` (TawcrootMethod.kt:359-365) are Android system
partitions, mounted read-only on the host. libhybris only `dlopen`s
`.so` files out of them — pure reads. Binding `:ro`:

- **Kernel-faithful** — a guest write that would `EROFS` on real
  Android now also `EROFS`s here, instead of the current host EROFS/
  EACCES grab-bag depending on partition mount state.
- **Defense in depth / clean errors** — nothing in a working path
  writes there, so RO can't break a working boot; it turns an
  accidental write into an early, unambiguous `EROFS` rather than a
  silent app-private landing or partial state.
- **Exercises the RO path in the default boot flow** — free coverage of
  the tawcroot RO enforcement in the most-run code path.

### Work

- `bindSpecs()` needs to carry a per-bind RO flag. Cheapest shape that
  also serves the user-binds plan: change the return type from
  `List<Pair<String,String>>` to a small triple/`BindSpec(src, dst, ro)`,
  and have `rootfsArgv()` emit `"$src:$dst"` when `ro == false` and
  `"$src:$dst:ro"` when true. Keep the 2-field form for RW so existing
  behaviour/tests are byte-identical.
- Mark the `LIBHYBRIS_BIND_DIRS` loop entries `ro = true`; leave
  `/dev`, `/proc`, `/sys`, share, ando, X11 as `ro = false`.
- Keep `ProotMethod` unchanged — proot has no RO primitive; the "kept
  in sync with ProotMethod" comment (`:357-358`) is about the *dir
  list*, not RO, so it stays valid.

### Verification

- Boot a rootfs on the device target and confirm the compositor/
  libhybris path still works (nothing reads-then-writes into `/system`
  et al.). This is the one load-bearing assumption; verify before
  calling it done.
- Spot-check that a write into `/system/...` from a guest shell now
  returns `EROFS`. Good candidate for a tawcroot device test under
  plans/tawcroot-prod-env-tests.md rather than a unit test.
- App unit test: assert `bindSpecs()` emits `:ro` for the system dirs
  and bare `src:dst` for `/dev`/`/proc`/`/sys`/share.

## Piece 2 — app-shipped assets: copy → RO bind

notes/installation.md §"Why copy, not bind" (lines 515-555) already
records that the copy design was forced by two problems, one of which
the RO primitive now removes:

1. **No RO bind at decision time** — *solved.* Without RO, anything in
   the rootfs could write back through the bind into shared host asset
   state; that is exactly what RO binds prevent.
2. **Bind = replacement, not merge** — **unchanged.** A single-file
   bind into a distro-managed dir (e.g. glvnd vendor JSON in
   `/usr/share/glvnd/egl_vendor.d/`) doesn't appear in the parent's
   `readdir` (tawcroot `getdents` is a passthrough), and a whole-dir
   bind shadows distro-shipped siblings.

So piece 2 only applies to **whole, app-owned dirs with no
distro-managed siblings**. `/usr/lib/hybris/`
(`LibhybrisInstallProvider`, `GUEST_LIB_DIR = "/usr/lib/hybris"`) is the
clean candidate: an app-owned dir, not shared with distro packages.
Files that must coexist with distro siblings (glvnd JSON) stay copied.

### Work (only if the disk/churn win is judged worth it)

- Add the libhybris asset dir to `bindSpecs()` as a `ro = true`
  whole-dir bind (host = the extracted asset dir, guest =
  `/usr/lib/hybris`), and drop the corresponding `LibhybrisInstallProvider`
  copy from the `TawcInstaller` manifest.
- Reconcile `Installation.tawcInstalls` / `tawcStamp` bookkeeping
  (notes/installation.md:540-549): a bound dir has no COPY/LINK manifest
  entry, so the stamp/refresh logic must not expect one for it.
- Leave the empty-manifest x86_64 fast path intact (no libhybris there).
- The payoff: ~12 MB/arm64 install saved and no per-upgrade copy; the
  cost: one more built-in bind and the asset dir must outlive every
  spawn (it already does — it is host-side app storage).

### Verification

- Boot libhybris path on device with the asset dir bound RO; confirm
  GPU init still works.
- Uninstall/reinstall and app-upgrade (`adb install -r`) cycles: confirm
  no stale copy is left behind and the bound dir refreshes with the APK.

## Sequencing

Piece 1 first (isolated, proves the mechanism in the default flow).
Piece 2 after, and only if `TawcInstaller` owners judge the disk/churn
win worth the manifest-bookkeeping change. Both are independent of the
user-bind UX plan, though piece 1's `BindSpec(src,dst,ro)` refactor is
the shared foundation that plan builds on.
