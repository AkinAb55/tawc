# Xwayland Glibc Alternative

This records the old glibc-built Xwayland approach. It is not the current path;
the shipping Xwayland stack is bionic-built because it can call Android AHB APIs
directly and avoids glibc seccomp traps.

## Toolchain Shape

Cross-compile Xwayland and its deps with `aarch64-linux-gnu-gcc`, not the NDK.
Use `CFLAGS=-D_GNU_SOURCE`, drop the bionic-only patches, and keep only the
libx11 include fix that pulls `<sys/ioctl.h>` in outside the `XTHREADS` branch.

Bundle a small glibc sysroot inside the APK:

- `ld-linux-aarch64.so.1`
- `libc.so.6`
- `libstdc++.so.6`
- `libgcc_s.so.1`
- `libpthread.so.0`, `libdl.so.2`, `libm.so.6`, `libresolv.so.2`, `librt.so.1`

After stripping, the glibc sysroot was about 11 MB on top of the otherwise
bionic-sized X11 dependency tree.

Patch the ELF interpreter and rpaths for `Xwayland`, `xkbcomp`, and shipped
libraries:

- `PT_INTERP` -> bundled glibc loader
- `DT_RUNPATH` -> bundled Xwayland libs, bundled glibc libs, and libhybris libs

## Seccomp Blocker

A glibc Xwayland child spawned by the compositor inherits Android's app seccomp
filter and dies very early with `SIGSYS`. The app path is neither Magisk `su`
nor proot, so there is no fresh non-app context and no SIGSYS tracer.

The empirical offending syscalls were:

| syscall | when |
| --- | --- |
| `set_robust_list` | glibc startup and `_Fork` |
| `rseq` | glibc startup and dynamic loader startup |
| `clone3` | first `pthread_create` |
| `accept` | X11 client accept |

The old fix was a build-time binary patch over the bundled glibc loader and
libc:

- replace `set_robust_list` calls with success;
- replace `rseq` and `clone3` calls with `-ENOSYS` so glibc uses fallbacks;
- retarget glibc's `accept()` wrapper to `accept4(..., flags=0)`.

That was seven 4-byte patches across `libc.so.6` and
`ld-linux-aarch64.so.1`, located via `aarch64-linux-gnu-objdump` and guarded by
expected-byte checks.

## Rejected Alternatives

- `LD_PRELOAD` accept shim: polluted child process env and broke bionic children.
- Rebuilding glibc: much larger maintenance surface than the binary patch.
- proot-style SIGSYS tracer: general, but adds a tracer and syscall-stop cost.
- pre-init DT_NEEDED SIGSYS handler: cannot catch loader startup syscalls and
  created loader/libc state divergence.

If a future workload genuinely needs glibc Xwayland in the APK, re-derive from
this shape rather than reviving the old build blindly.
