# External storage binds

Goal: let users keep selected rootfs data somewhere outside app-private
storage so it can survive uninstall and be visible to other Android apps.

Do not make this the default Linux home layout. Keep `/root` inside app
data by default and expose Android storage at dedicated guest paths
(default binds `/android` and `/home/android`, see UX below).
Putting all of `/root` on Android shared storage risks exposing dotfiles,
browser profiles, SSH/GPG state, package caches, Unix permissions, and
other Linux-private data to a storage model that does not preserve full
POSIX semantics.

## Approach: all-files access plus normal tawcroot binds

This is the practical path for "Linux apps see a normal directory".
Android's `MANAGE_EXTERNAL_STORAGE` permission gives direct path access
to shared storage after the user explicitly enables "all files access"
for the app. With that grant, tawc can expose any host directory with an
ordinary tawcroot bind:

```
-b /storage/emulated/0:/home/android
```

### Platform and policy

- The API is available on Android 11+ and is still usable by our target
  SDK when the user grants it.
- `WRITE_EXTERNAL_STORAGE` is not a useful replacement for target SDK 30+.
- The main constraint is distribution policy: Google Play treats
  `MANAGE_EXTERNAL_STORAGE` as a sensitive permission and allows it only
  for qualifying core file-management-style use cases. Sideload,
  F-Droid, local debug, and non-Play distribution do not have the same
  review gate, but the app still needs a clear user-facing reason.
- Make the permission build-time optional if possible (e.g. declare it
  in a flavor/variant manifest or strip it with a manifest overlay), so
  a Play Store release can ship without it if review rejects it. The
  feature should detect the permission's absence and hide/disable the
  external-binds UI rather than fail at runtime.

### Data model

Add a persisted external-bind list to installation metadata. Suggested
shape:

```
externalBinds: [
  {
    "kind": "path",
    "label": "Android home",
    "hostPath": "/storage/emulated/0",
    "guestPath": "/home/android",
    "writable": true
  }
]
```

The list holds an arbitrary number of entries, edited via the
manage-binds activity (see UX).

### Rootfs integration

- Extend `TawcrootMethod.bindSpecs()` to append installation-specific
  external binds after the built-in system/share binds.
- Pre-create guest bind target directories in the rootfs before spawn.
- Host bind sources are existing directories the user picked (or the
  built-in defaults); do not auto-create missing sources.
- Binds must be configurable before install so they are already in
  place during the installation process and first boot.
- Keep bind targets out of uninstall deletion risk. Tawcroot fake binds
  are path rewrites, not kernel mounts, so uninstall does not traverse
  into shared storage. The chroot debug method uses real mounts and must
  either not support these binds initially or require extra cleanup
  review before enabling them there.
- Fail closed: if a configured external bind source is missing or access
  is revoked, refuse launch with a clear error rather than silently
  falling back to an empty app-private directory.

### UX

- A "manage binds" activity, reachable via a button from two places:
  - the new-distro install flow, so binds are configured up front and
    external storage is bound in during the installation process;
  - the per-install manage screen, for changes after install.
- The activity edits the install's bind list: add, edit, and remove an
  arbitrary number of entries. Each entry pairs a guest (in-tawcroot)
  path the user types in with a host path chosen via a file-manager
  directory picker. Use an in-app directory browser over real paths
  (the app has all-files access); do not route the picker through SAF.
- New installs default to two binds, editable/removable like any other:
  - `/android` -> `/` (the Android root; much of it is unreadable to
    the app due to SELinux/DAC, which is expected)
  - `/home/android` -> `/storage/emulated/0` (the Android user's home,
    i.e. the user-visible shared-storage root)
- Explain that files under bound host paths survive tawc uninstall and
  may be visible to other apps.
- Binding over `/root` is possible by adding such an entry, but warn:
  many Linux programs assume private, POSIX-like home semantics.

### Testing

- Unit-test metadata parsing defaults and migration.
- Integration-test a configured bind:
  - create file inside `/home/android`;
  - verify it appears under shared storage;
  - write a host-side file and verify it appears inside the rootfs;
  - uninstall the rootfs and verify shared-storage contents remain.
- Test revoked permission path: launch fails clearly and does not create
  an app-private replacement.
- Test fresh installs get the default `/android` and `/home/android`
  binds and that they can be edited and removed.

## Rejected: SAF-backed virtual directories

Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) grants a
`content://` tree URI, not a filesystem path, so tawcroot cannot bind
it directly. Exposing a SAF tree to Linux apps would mean building a
virtual filesystem layer in tawcroot backed by an app-side broker — a
major multi-week feature with poor POSIX fidelity (no symlinks, xattrs,
ownership, exec; provider-dependent semantics). Direct path access via
`MANAGE_EXTERNAL_STORAGE` is not going away, and the only thing SAF
would buy is Play Store policy compliance. Not worth it. If all-files
access is ever unavailable, use SAF for import/export or a
file-picker-driven "copy into shared directory" workflow instead of
live mounts.

## Recommended sequence

1. Implement the bind mechanism plus the manage-binds activity (install
   flow and manage screen entry points, default binds).
2. Keep full external `/root` discouraged: supported via a manual bind
   entry, warned against in the UI.
