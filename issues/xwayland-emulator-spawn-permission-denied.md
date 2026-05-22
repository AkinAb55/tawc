# Xwayland spawn permission denied on emulator

Observed on the x86_64 emulator after reinstalling the debug APK:

```text
xwayland: failed to spawn Xwayland: Permission denied (os error 13)
```

The app had just extracted `files/xwayland/` and symlinked
`bin/Xwayland` to the APK native library dir. Pure Wayland clients still
worked; `GDK_BACKEND=wayland gtk3-demo` opened normally.

Reproduce with `.tawctarget=emulator`, reinstall/launch the app, then
check `adb logcat -s tawc-native` during compositor startup.
