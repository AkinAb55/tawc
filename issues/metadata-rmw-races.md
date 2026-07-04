# metadata.json writers race each other (whole-record RMW, no lock)

Every `metadata.json` writer does load → mutate a full `Installation`
copy → atomic-rename save, with no per-id lock or generation check.
The rename keeps records untorn, but the *loser's fields are silently
reverted* whenever two writers overlap. Known overlapping writers:

- `TawcInstaller.installInto` (TawcInstaller.kt:83 load, :143 save)
  holds its snapshot across the whole manifest re-copy — minutes after
  an APK upgrade. A user toggling ando (or committing binds) in that
  window sees the toggle succeed, then the installer's save writes the
  pre-toggle record back. For ando the listener also stays up until
  the next refresh, disagreeing with metadata.
- `DistroInfoActivity.buildAndoRow` and `ManageBindsActivity.commit`
  clobber each other's field (`andoEnabled` vs `externalBinds`) when
  their windows overlap. Both re-check the READY/FAILED gate at write
  time, which protects against the *service*'s state transitions but
  not against each other or TawcInstaller.
- Gate TOCTOU vs uninstall: load sees READY, uninstall's
  `RootfsCleaner.wipe` then deletes metadata.json, and the gated
  writer's save recreates it — a ghost slot with no rootfs (or a
  failed uninstall `gone()` check). ManageBinds has always had this;
  the ando toggle mirrors it per the original plan.

Rapid double-taps of the ando toggle itself are already serialized
(single-thread executor in DistroInfoActivity), so this is about
cross-writer overlap only.

Sketch of a real fix: route all metadata writes through one
`InstallationStore.update(id) { inst -> inst.copy(...) }` that holds a
per-id in-process lock, re-loads inside the lock, and refuses (not
recreates) when the file is gone; long-running writers like
TawcInstaller then re-load inside that update instead of saving a
stale snapshot.
