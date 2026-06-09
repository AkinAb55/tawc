# tawcroot usercopy requires process_vm_* even when policy blocks it

Host `tawcroot` rootfs tests currently depend on
`process_vm_readv`/`process_vm_writev` succeeding against the current
task. In this host environment that probe fails with `-EPERM`, so
`tawc_usercopy_works` stays false and every path-bearing handler
returns `-EFAULT`.

Repro:

```sh
tawcroot/test.sh --host rootfs_syscalls_smoke
```

Current failure starts with:

```text
usercopy probe rv = -1
usercopy_works = 0
[FAIL] openat("/etc/probe") -> translates inside rootfs
    fd = -14
```

A direct hosted probe also fails for both `getpid()` and `gettid()`:

```text
process_vm_readv(... getpid ...) -> -1 EPERM
process_vm_readv(... gettid ...) -> -1 EPERM
```

`notes/tawcroot.md` already says the fallback for targets that block
`process_vm_*` should be an arch-local fault-recovery path implemented
inside tawcroot. That fallback does not exist yet; `usercopy.c`
currently treats probe failure as a deployment problem and all guarded
copies fail.

Likely fix: add a libc-free, async-signal-safe fault-guarded copy path
for x86_64/aarch64 and keep `process_vm_*` as the preferred fast path
when available.
