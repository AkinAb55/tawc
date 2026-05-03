# tawcroot: `mknod`/`mknodat` not in the trap set, paths not translated

`mknod` and `mknodat` are listed in `notes/tawcroot.md`'s "Which
syscalls need trapping" but aren't currently trapped. Both take a
guest path that should go through the normal translate-then-
dispatch flow.

The kernel will likely return `-EPERM` for most device-node types
under Android's `untrusted_app` SELinux policy, but trapping still
matters: we want the failure to happen against the guest-visible
path, not the host-relative one (otherwise the guest sees an
errno tied to a path it doesn't recognize).

## When this surfaces

- A real `tar -x` from inside the chroot that includes char/block
  device entries. We currently dodge this on the install path
  via Kotlin's `ProotArchiveExtractor` skipping mknod entries,
  but a guest-side extract bypasses that.
- Anything that creates FIFOs / sockets via `mknod` rather than
  `mkfifo` / `socket+bind` (rare, but `mknod(path, S_IFIFO)` is
  a thing).

## Fix sketch

Add `mknod` and `mknodat` to the trap list, route to a handler
in `syscalls_path.c` that translates the path arg and dispatches
to `mknodat` on the host. Mirror the pattern used by `mkdirat`.

## Severity

Low until something we run actually exercises it. Listing here so
the next agent looking at "why does my tar extract fail" finds
the answer.
