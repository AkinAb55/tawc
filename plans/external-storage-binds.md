# External storage binds and SAF-backed directories

Goal: let users keep selected rootfs data somewhere outside app-private
storage so it can survive uninstall and be visible to other Android apps.

Do not make this the default Linux home layout. Keep `/root` inside app
data by default and expose opt-in user-data directories first
(`/root/Documents`, `/root/Downloads`, `/root/Shared`, or similar).
Putting all of `/root` on Android shared storage risks exposing dotfiles,
browser profiles, SSH/GPG state, package caches, Unix permissions, and
other Linux-private data to a storage model that does not preserve full
POSIX semantics.

## Track A: all-files access plus normal tawcroot binds

This is the practical path for "Linux apps see a normal directory".
Android's `MANAGE_EXTERNAL_STORAGE` permission gives direct path access
to shared storage after the user explicitly enables "all files access"
for the app. With that grant, tawc can create a real directory such as:

```
/storage/emulated/0/tawc/<install-id>/Documents
```

and add an ordinary tawcroot bind:

```
-b /storage/emulated/0/tawc/<install-id>/Documents:/root/Documents
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

### Data model

Add a persisted external-bind list to installation metadata. Suggested
shape:

```
externalBinds: [
  {
    "kind": "path",
    "label": "Documents",
    "hostPath": "/storage/emulated/0/tawc/arch/Documents",
    "guestPath": "/root/Documents",
    "writable": true
  }
]
```

Initial UI should offer a few predefined destinations rather than an
arbitrary path editor. Arbitrary host paths can come later behind an
advanced screen.

### Rootfs integration

- Extend `TawcrootMethod.bindSpecs()` to append installation-specific
  external binds after the built-in system/share binds.
- Pre-create guest bind target directories in the rootfs before spawn.
- Pre-create host source directories only when the app has all-files
  access and the path is under a tawc-owned shared-storage prefix.
- Keep bind targets out of uninstall deletion risk. Tawcroot fake binds
  are path rewrites, not kernel mounts, so uninstall does not traverse
  into shared storage. The chroot debug method uses real mounts and must
  either not support these binds initially or require extra cleanup
  review before enabling them there.
- Fail closed: if a configured external bind source is missing or access
  is revoked, refuse launch with a clear error rather than silently
  falling back to an empty app-private directory.

### UX

- Settings entry: "External storage for Linux files".
- Explain that files in the chosen shared location survive tawc uninstall
  and may be visible to other apps.
- Prefer per-directory choices:
  - Documents -> `/root/Documents`
  - Downloads -> `/root/Downloads`
  - Shared -> `/root/Shared`
- Full external `/root` should be an advanced option with a warning:
  many Linux programs assume private, POSIX-like home semantics.

### Testing

- Unit-test metadata parsing defaults and migration.
- Integration-test a configured bind:
  - create file inside `/root/Documents`;
  - verify it appears under shared storage;
  - write a host-side file and verify it appears inside the rootfs;
  - uninstall the rootfs and verify shared-storage contents remain.
- Test revoked permission path: launch fails clearly and does not create
  an app-private replacement.
- Test multiple installs use separate host directories by default.

## Track B: SAF-backed virtual directories

Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) is the modern
picker-based API where the user grants access to a directory tree. It is
good Android API, but it grants a `content://` tree URI, not a normal
filesystem path. Tawcroot cannot bind a URI directly.

Supporting SAF as a Linux-visible directory means building a virtual
filesystem layer, not adding a normal bind.

### Architecture

Add a new tawcroot source kind:

```
normal path bind: guest path -> host dirfd + suffix -> kernel syscall
SAF bind:         guest path -> virtual SAF node -> broker request
```

Tawcroot's SIGSYS handler cannot call Android Java/Kotlin APIs or JNI.
It would need to talk to a broker owned by the Android app:

```
guest syscall
  -> tawcroot SIGSYS handler
  -> Unix socket request to SAF broker
  -> Kotlin ContentResolver/DocumentsContract call
  -> result or fd returned to tawcroot
```

For regular files, the broker may be able to call
`ContentResolver.openFileDescriptor()` and pass the resulting fd back
with `SCM_RIGHTS`. Directory handles and metadata still need synthetic
Linux fd state in tawcroot.

### Minimum viable syscall surface

An MVP SAF directory must handle at least:

- path resolution under a SAF bind;
- directory open and `getdents64`;
- `openat` for regular files;
- `read`, `write`, `lseek`, `close`, `dup*`, `fcntl` enough to track
  virtual fds and passed-through regular-file fds;
- `newfstatat`, `statx`, and fstat-style empty-path calls;
- `mkdirat`, `unlinkat`, `renameat2`, `truncate`, `utimensat`, and
  basic `access`.

Return `EOPNOTSUPP` for features SAF cannot faithfully provide:

- symlink and hardlink creation;
- device nodes, FIFOs, Unix sockets;
- xattrs;
- real uid/gid/mode ownership;
- executing binaries from SAF;
- file locking unless a specific provider/fd path proves safe.

### Compatibility limits

- Some SAF providers are cloud-backed or stream-backed. A file
  descriptor may not behave like a seekable local file.
- Directory listing is provider-mediated and can be slower than a local
  filesystem.
- Rename/move semantics vary by provider.
- Linux applications that rely on POSIX permissions, symlinks, hardlinks,
  mmap, executable bits, or atomic replace patterns may fail.

For these reasons, SAF-backed directories should be presented as a
compatibility/storage bridge, not as a general replacement for `/root`.

### Implementation phases

1. **Broker prototype.** Android-side service accepts requests over a
   Unix domain socket and can list/create/delete/read/write files under
   one persisted tree URI.
2. **Read-only SAF bind in tawcroot.** Support path lookup, stat,
   directory listing, and regular-file open/read. No writes.
3. **Basic writes.** Add create, overwrite, truncate, mkdir, unlink, and
   rename where the provider supports them.
4. **Fd provenance.** Track virtual SAF fds through close/dup/fcntl and
   fd-relative path syscalls. This overlaps with tawcroot's planned
   fd-provenance work.
5. **Workload validation.** Test common CLI/file-manager workflows.
   Defer browser profiles, package caches, and full home directories
   unless the compatibility story is proven.

Expected size: large. Read-only browsing is a moderate project; a
read/write SAF directory usable by common Linux tools is likely a major
multi-week feature. A convincing full-home implementation is probably
not worth pursuing unless direct shared-storage path access is
unavailable for the target distribution channel.

## Recommended sequence

1. Implement Track A for selected directories only.
2. Keep full external `/root` experimental.
3. Use SAF for import/export or a file-picker-driven "copy into shared
   directory" workflow if all-files access is unavailable.
4. Revisit Track B only after real users need a Play-policy-friendly
   storage bridge and we are willing to own a small VFS layer.
