# tawcroot: get*id is faked to root but set*id passes through and fails EPERM

`identity.c` fakes `getuid`/`geteuid`/`getresuid`/`getgid`/... to 0,
and the stat/chown handlers fake root ownership consistently. But
`setuid`/`setgid`/`seteuid`/`setresuid`/`setresgid`/`setgroups`/
`setreuid`/`setregid` are not in the dispatch table at all: the kernel
sees them from an unprivileged app uid and returns real `-EPERM`.

A guest that believes it is root and calls `setuid(0)`/`setgroups()`
— daemons dropping privileges, `su`-style tools, dpkg/pacman
maintainer scripts using `runuser` — gets EPERM and typically aborts.
proot's `-0` fakes these to success for exactly this reason.

Also a doc/code drift: notes/tawcroot.md §"Which syscalls need
trapping" lists the set*id family in the MVP trap set ("return 0 for
root-like transitions"); the code never grew the handlers.

## Fix shape

One `fake_zero` handler installed for the whole family (mirroring
`fake_eperm` in syscalls_control.c). Arguable refinement: only return
0 when the target uid/gid is 0 or matches the faked credentials;
return EINVAL/EPERM otherwise — proot returns success
unconditionally and that's been fine.

## Severity

Medium: blocks privilege-dance daemons and package maintainer scripts.
