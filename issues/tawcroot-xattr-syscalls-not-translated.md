# tawcroot: xattr syscalls not in the trap set, paths not translated

The xattr family — `setxattr`, `getxattr`, `listxattr`,
`removexattr`, plus the `l*xattr` (no-symlink-follow) variants
and the `f*xattr` (fd-based) variants — is listed in
`notes/tawcroot.md` "Which syscalls need trapping" but not
currently trapped.

The path-bearing variants (`*xattr` and `l*xattr`) need the
normal translate-then-dispatch flow. The `f*xattr` variants
take an fd and don't need path translation, so they can pass
through.

## When this surfaces

- `cp -a` over a tree with extended attributes (capabilities,
  SELinux contexts, ACLs).
- `pacman` installing packages that ship file capabilities (the
  default flow does not, but `--needed`-style refresh runs
  occasionally hit them).

The kernel usually returns `-EOPNOTSUPP` or `-ENOTSUP` for xattrs
on Android's app-private storage anyway, so the practical impact
is "guest sees the right errno against the right path" rather
than "the call succeeds."

## Fix sketch

Add the path-bearing xattr syscalls to the trap list; route to a
handler in `syscalls_path.c` (or a new `syscalls_xattr.c`) that
translates the path arg and dispatches to the corresponding
`f*xattr` on an `O_PATH` fd of the translated path, since there's
no `*xattrat` family. Mirror the pattern proot uses.

`f*xattr` variants don't need to be trapped.

## Severity

Low. Surfaces if pacman starts insisting on capabilities, or if
someone does `cp -a` of a tree that uses xattrs.
