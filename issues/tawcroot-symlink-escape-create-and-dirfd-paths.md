# tawcroot: symlink clamping gaps — O_CREAT leaf and dirfd-relative paths escape the rootfs

The manual symlink resolver clamps absolute/`..` symlink targets at
the rootfs boundary, but two paths bypass it:

## 1. `open(O_CREAT)` on an existing symlink leaf (high)

`syscalls_fs.c::translate mode selection`: `O_CREAT` (without
`O_EXCL`) maps to `TAWCROOT_PATH_PARENT_CREATE`, which never resolves
the leaf (path_resolve.c: "NOFOLLOW / PARENT_*: don't readlink the
leaf"). The handler then issues plain
`openat(base_fd, suffix, O_CREAT|...)`. Kernel semantics for `O_CREAT`
without `O_EXCL`: an EXISTING symlink leaf IS followed. So
`open("/etc/resolv.conf", O_WRONLY|O_CREAT)` where `resolv.conf` is an
absolute symlink (`→ /run/...` — extremely common) makes the host
kernel chase the target against the HOST root: writes land outside
the rootfs view, or fail with bogus ENOENT/EACCES. A dangling symlink
+ `O_CREAT` creates at the host-resolved target.

Fix shape: PARENT_CREATE should still resolve the leaf when it exists
and is a symlink (probe with the oracle; fall back to PARENT_CREATE
behavior only for a missing leaf). Mirror kernel `O_CREAT|O_EXCL`
(never follows) vs plain `O_CREAT` (follows existing).

Ready-made integration test (currently fails; uses the existing
`static_open_creat_argv1` fixture — add to test_prod_features.c once
fixed):

    rootfs: usr/, run/, etc/resolv.conf -> /run/resolv.conf (absolute)
    run: tawcroot -r ROOTFS -- /bin/static_open_creat_argv1 /etc/resolv.conf
    assert exit 42 and <ROOTFS>/run/resolv.conf exists

## 2. dirfd-relative paths bypass clamping entirely (medium)

`fetch_and_translate_at`: a relative path off a guest dirfd is only
lifted to guest-absolute (and thus clamped) when it contains a `..`
component. A dotdot-free relative path goes to the kernel verbatim
(`openat(dirfd, "alternatives/editor")`), and any intermediate or leaf
in-rootfs symlink with an absolute target is chased against the host
root. notes/tawcroot.md §"Translation rules" item 4 requires escapes
blocked "for both absolute and relative requests" — only the `..`
half is implemented for dirfd-relative paths.

Fix shape: lift ALL dirfd-relative paths through
`tawcroot_fd_to_guest_abs` (not just dotdot ones), falling back to
passthrough only for outside-view dirfds. Costs one /proc readlink
per *at call with a relative path — measure with perf/run-perf.sh.

## Related stage-ordering seam (low)

`path_orchestrate.c`: the bind pass runs before memo and after the
resolver, never between memo and resolver. A memo rewrite that lands
the path inside bind territory (`bind on /usr/lib`, memo
`lib → usr/lib`, input `/lib/x`) still walks the ROOTFS oracle for
`usr/lib/...` before the final bind pass routes it. If the rootfs's
own `usr/lib` shadow has conflicting symlinks (or is a file →
resolver errno), resolution happens against the wrong tree. A
`route_through_binds` check after the memo loop would close it.

## Severity

High for (1): silent writes outside the rootfs view on very common
paths. Medium for (2).
