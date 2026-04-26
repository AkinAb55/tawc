# Android Integration

## Wayland Socket Sharing

**With root (chroot):** The compositor creates a Unix socket at a known path and the
chroot client connects directly. Root bypasses SELinux MAC checks on `connect()`.
This is the current development approach.

**Without root (proot, future goal):** SELinux blocks cross-app `connect()` between
`untrusted_app` domains on Android 9+. Two viable solutions:

1. **Binder fd passing (preferred):** Compositor creates a `socketpair()`, passes one end
   to Termux via a ContentProvider or bound Service as a `ParcelFileDescriptor`. No
   `connect()` syscall occurs, so SELinux is never triggered.

2. **Shared UID:** `sharedUserId="com.termux"` makes both apps run as same UID/SELinux
   domain. Deprecated since API 33 but still functional. Limits distribution flexibility.

## Chroot Setup

Push and run the script:
```bash
adb push client/arch-chroot-run /data/local/tmp/
adb shell "/system/bin/sh /data/local/tmp/arch-chroot-run"
```

The script handles all bind mounts, su escalation, and profile setup. For
running a command:
```bash
adb shell "/system/bin/sh /data/local/tmp/arch-chroot-run '<command>'"
```

Generic tawc Wayland env vars are set automatically by `/etc/profile.d/01-tawc.sh`.

### Shell quoting through adb/su/chroot

Commands pass through multiple shell layers: host bash → adb → mksh → su → chroot bash.

**Critical quoting rule for `&&` / `||` in `su -c`:** When running compound
commands via adb, the outer shell (mksh) parses `&&` and `||` BEFORE `su` sees
them. This silently runs the second command as shell (uid 2000), not root:

```bash
# BROKEN: mksh splits at &&. cp runs as root, bash runs as shell user.
adb shell su -c "cp /tmp/foo /chroot/tmp/ && bash /chroot/build.sh"

# CORRECT: inner quotes protect && from mksh.
adb shell "su -c 'cp /tmp/foo /chroot/tmp/ && bash /chroot/build.sh'"
```

**arch-chroot-run handles su internally**, so scripts that only need to run
commands in the chroot should NOT wrap in an outer `su -c`:
```bash
# Preferred: no outer su, arch-chroot-run escalates internally.
adb shell "/system/bin/sh /data/local/tmp/arch-chroot-run 'build command'"

# Only use su -c for operations on the chroot filesystem itself.
adb shell "su -c 'cp /data/local/tmp/foo /data/local/arch-chroot/tmp/'"
```

Variable expansion like `$0` or `$KSH_VERSION` at any intermediate layer can
give misleading results. The `su` shell on Android is mksh (`/system/bin/sh`),
easily confused with the chroot's GNU bash.

## EGL Context and Surfaces

- An EGL context CAN move between threads (release on old, bind on new), but expensive
- One thread can render to multiple EGLSurfaces via `eglMakeCurrent` switches
- Each switch flushes the pipeline -- overhead per switch
- Recommended: single render thread, one context, switch surfaces per window
- `ASurfaceTransaction` + AHB avoids `eglMakeCurrent` overhead entirely (future opt)

## Multiple Activities

- All Activities in one app share the same process (single heap, static state, threads)
- One SurfaceView per Activity avoids Z-ordering issues
- Single background render thread maintains list of active surfaces
- Activity launch creates visual transitions -- suppress with
  `overridePendingTransition(0, 0)`
- Activities may be killed under memory pressure -- handle surface loss gracefully

## Audio (Out of Scope)

Linux desktop apps typically expect PulseAudio or PipeWire. Audio forwarding from the
chroot to Android is not addressed yet. Options include running PulseAudio over a Unix
socket (Termux already packages `pulseaudio`) or bridging PipeWire to Android's audio HAL.
