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
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run"
```

The script handles all bind mounts and profile setup. For running a command:
```bash
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run '<command>'"
```

Generic tawc Wayland env vars are set automatically by `/etc/profile.d/01-tawc.sh`.

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
