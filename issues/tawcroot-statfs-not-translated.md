# tawcroot: `statfs` not in the trap set, path not translated

`statfs` takes a guest path; it's in `notes/tawcroot.md` "Which
syscalls need trapping" but isn't currently trapped. `fstatfs`
takes an fd and doesn't need translation.

Today, a guest calling `statfs(path)` against a guest-visible
path either:

- gets `-ENOENT` if the path doesn't exist relative to the host
  cwd of the tawcroot process, or
- gets stat info for whatever happens to live at `path` on the
  host (a different filesystem than the guest expects).

## When this surfaces

- Programs that check available disk space before writing
  (browsers, pacman, build tools).
- `df` from inside the chroot.

The result data itself doesn't usually leak host paths (statfs
returns block counts, fs type, mount flags), so the impact is
"guest sees wrong free-space figures" rather than information
disclosure.

## Fix sketch

Add `statfs` to the trap list, route to a handler that
translates the path arg and dispatches to `statfs` on the host.
Mirror the pattern used by `newfstatat`.

## Severity

Low. Listing here for completeness — `notes/tawcroot.md` is
explicit that this is a "probably a non-issue" item, but it's
worth getting right alongside the other path-translation
syscalls.
