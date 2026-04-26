# SELinux enforcing mode: SHM buffers fail due to memfd labels

- Chroot clients run under the `magisk` SELinux context, which isn't in `appdomain`
- Their memfds get labeled `tmpfs:s0` instead of `appdomain_tmpfs:s0`
- The compositor (`untrusted_app`) is denied `{ read write }` on `tmpfs:s0` memfds under enforcing SELinux
- Our LD_PRELOAD shim (`memfd-selinux-shim`) relabels memfds via `fsetxattr`, but GDK/GLib bypasses it by calling `syscall(SYS_memfd_create, ...)` directly
- Hardware buffer (AHB/wlegl) path is unaffected — only SHM buffers break
- dmesg: `avc: denied { read write } for path=memfd:gdk-wayland dev="tmpfs" scontext=u:r:untrusted_app:... tcontext=u:object_r:tmpfs:s0`
- Workaround: `adb shell "su -c 'setenforce 0'"` (resets on reboot)

## Fix direction

If chroot clients ran in an `appdomain` SELinux context, their memfds would automatically get labeled `appdomain_tmpfs` — no shim needed at all. Approach: after chroot setup (which needs root), drop to the compositor's UID and SELinux context before exec'ing the client. Need to verify `runcon`/context transitions work and that GPU/hwbinder access survives the switch.

## See also
- notes/rendering.md "SELinux and Memfd Sharing"
- notes/firefox.md "Known Issues"
