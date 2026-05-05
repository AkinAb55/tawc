# tawcroot --exec-child leaks as an orphan when the parent dies

If the controlling parent of `tawcroot` is killed (e.g. host shell SIGPIPE
propagating through `adb shell`), the parent `tawcroot` exits but its forked
`tawcroot --exec-child <N>` worker is reparented to init and keeps running.
Result: stray processes pinned in the rootfs, holding `/proc/<pid>/root` and
sometimes file locks. Survives `tawc-chroot-run.sh` death, install-test-deps,
ad-hoc shells, etc.

## Reproducer

```bash
# proxy not relevant — any chroot entry triggers it
bash scripts/install-test-deps.sh 2>&1 | head -3   # SIGPIPE the script
adb shell 'su -c "ps -ef | grep tawcroot"'         # find an orphan --exec-child
```

## Root cause

`tawcroot` does not call `prctl(PR_SET_PDEATHSIG, SIGKILL)` in the
`--exec-child` path. `grep -rn 'PR_SET_PDEATHSIG\|prctl' tawcroot/` confirms
the only `prctl` references are seccomp tests and the guest-side prctl
shadow — nothing in the host fork.

When the wrapping `tawcroot` dies (SIGHUP / SIGTERM / SIGKILL), the kernel
re-parents `--exec-child <N>` to init and the child keeps running. Without
PDEATHSIG it has no way to know the parent is gone.

## Fix

In whichever `clone(2)` / `fork(2)` site spawns `--exec-child`, add a
PDEATHSIG-set immediately in the child path before any other work:

```c
prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
/* Race: parent may already be dead. Re-check by getppid() == 1
 * and exit if so. */
if (getppid() == 1) _exit(0);
```

Match the pattern proot uses (`tracer.c` initial setup) and bubblewrap
(`bubblewrap.c`'s child setup). The double-check is mandatory — there's
a window between fork and the prctl where the parent can die unnoticed.

## Workarounds in the meantime

- The InstallationService `runCancelKillScript` already sweeps `/proc` for
  chroot-rooted PIDs and SIGKILLs them. Host-side scripts could share that
  sweep but currently don't.
- Manual cleanup: `adb shell 'su -c "pkill -9 -f tawcroot"'` after killing
  any chroot session.

## Notes on impact

- Proot doesn't have this bug — it uses PDEATHSIG.
- chroot-method installs are also vulnerable (any non-PDEATHSIG fork is),
  but the chroot path's wrapper is `bash -c …` directly under su, so the
  process tree there typically gets SIGHUP'd cleanly.
- The orphan holds `/proc/<pid>/root` open, which is the same anchor
  `RootfsCleaner` uses to detect "things still in the rootfs", so an
  uninstall will fail loudly until the orphan is cleaned up.
