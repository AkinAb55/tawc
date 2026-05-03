# tawcroot: `prod_readlink` can't tell a full target from one truncated to cap-1

`prod_readlink` (`tawcroot/src/path.c:320-322`) reads with `cap-1` to
leave a byte for the NUL terminator. If the symlink target is exactly
`cap-1` bytes, `readlinkat(2)` returns `cap-1` and Linux gives no
"truncated" indicator — you'd have to retry with a bigger buffer to
know. We accept the result as the full target.

When this happens path canonicalization silently disagrees with the
kernel's view of the symlink: the resolver works against a truncated
target while the eventual syscall the guest issues sees the full
target. The mismatch is hard to debug — translation looks correct,
the kernel disagrees.

## Likelihood

Buffer is `TAWC_PATH_MAX = 4096`, so we'd need a symlink target
exactly 4095 bytes long. Rare in practice but reachable; some
package-manager pipelines build deeply nested symlinks.

## Fix

Retry with a larger buffer when the return equals `cap-1`. Either
double until it fits, or call `fstatat` first and size to `st_size + 1`.
