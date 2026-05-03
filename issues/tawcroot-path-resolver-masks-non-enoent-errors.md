# tawcroot: path resolver swallows `-EACCES` (and other non-`-ENOENT` errors) mid-walk

The resolver-oracle contract in `path_oracle.h` says callers should
return:

- `-ENOENT` for missing components,
- `-EINVAL` for "not a symlink",
- any other `-errno` is fatal.

`tawcroot/src/path_resolve.c:156-164` handles the oracle return as:
`-EINVAL` → continue walking, anything else → `return 0` (silent
stop). That last branch is wrong — it folds `-EACCES` (and every
other fatal errno) into the same path as "not a symlink, continue."

## Why this is more than diagnostic

When a parent component's permissions deny the resolver's
`readlinkat`, the resolver decides "guess it's not a symlink" and
walks into the next component as-is. The downstream syscall then
hits a path the kernel might still serve (different rules apply to
the actual op vs. readlinkat), or a partially-translated path that
leaks the guest's intent into a host-relative location.

The right behavior is to bubble `-EACCES` (and any non-`-ENOENT`,
non-`-EINVAL` errno) up so the syscall returns it directly to the
guest.

## Severity

Major. Easy to hit on real rootfs trees with restrictive permissions
on a parent dir (e.g. installer tarballs that drop `0700` mode on a
parent of files we still want to translate).

## Fix sketch

In `path_resolve.c:156-164`, treat the oracle's return as:
- `0` or positive → success, continue,
- `-ENOENT` → component missing, stop with the resolved prefix,
- `-EINVAL` → not a symlink, continue walking,
- anything else → propagate to the caller (typically becomes the
  guest's syscall return).
