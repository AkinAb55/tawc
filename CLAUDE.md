Hi Claude, this project is Tess's Android Wayland Compositor (tawc)

## Notes
[notes.md](notes.md) contains architecture and implementation notes, and should be kept up to date with new choices and discoveries.

This is an agent-written project. Existing code/notes may be wrong in all sorts of ways. Stay vigilant, and always be prepared to surface/fix problems (even when working on something else).

## Workflow
- You often need to debug against a real Android phone, which is provided via adb
- Try to work autonomously when possible, figure out how to close the debugging loop. If you need one-off human help to get your dev loop set up, just ask
- When analyzing screenshots, use a sub-agent so the image doesn't end up in main context.
- If `su` is available on a phone you're testing on, use it instead of `adb root`
- When there's a script to do something (eg create/destroy the chroot) always use it instead of oneshotting commands
- If there's a problem with a script (or any file in the repo), feel free to edit it

## Wayland buffers
We support both hardware buffers and SHM buffers, but the goal is to make hardware buffers work for most apps in most cases without their pixels ever having to touch the CPU. To this end, we tint SHM buffers magenta so it's obvious when they're being used (which may or may not be what we want in a given context). Do not remove this magenta tinting unless explicitly asked to.

## Device Support
The goal of this project is to run on all modern Android phones without requiring firmware modifications. This means requiring open source GPU drivers or bionic patches is not viable. Requiring root is viable (especially for testing), but ideally it could even be run without root.

## Safety
I'm letting you play with my phone, try not to fuck it up.

## Organization
Avoid junking up devices (eg delete screenshots you take when you're done with them). On the phone things should generally stay in `/data/local/arch-chroot/`, `/data/local/claude-debug` (**NOT** `/data/local/tmp`)

## How-to
More details on each of these can be found in notes.md.

- **Build the APK:** `cd server && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug` (JDK 26 is broken with our Kotlin plugin version; must use 21)
- **Install & launch:** `adb install -r server/app/build/outputs/apk/debug/app-debug.apk && adb shell am force-stop me.phie.tawc && adb shell am start -n me.phie.tawc/.MainActivity`
- **Set up the chroot:** Push and run the script: `adb push client/arch-chroot-run /data/local/tmp/ && adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run"` (interactive shell). The script handles all bind mounts and profile setup. SELinux must be permissive: `adb shell su -c setenforce 0`
- **Run a Wayland app in the chroot:** `adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run '<command>'"` — generic tawc Wayland env vars are set automatically by the login profile.
- **Launch Firefox (GPU rendering):**
  ```
  adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run \
    'GDK_GL=disabled MOZ_ENABLE_WAYLAND=1 MOZ_ACCELERATED=1 \
     MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
     MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 \
     DISPLAY= firefox --no-remote'"
  ```
- **Take a screenshot:** `adb shell su -c "screencap -p /sdcard/screenshot.png" && adb pull /sdcard/screenshot.png /tmp/screenshot.png` — analyze with a sub-agent (per Workflow rules), then clean up: `adb shell rm /sdcard/screenshot.png && rm /tmp/screenshot.png`
- **Check compositor logs:** `adb logcat -s tawc-native` (Rust compositor) or `adb logcat -s tawc` (Kotlin app). Filter out frame spam with `grep -v renderer_gles2_frame`.
- **Kill Firefox:** `adb shell su -c "killall firefox"`
- **Restart compositor:** `adb shell am force-stop me.phie.tawc && adb shell am start -n me.phie.tawc/.MainActivity`
- **Simulate touch:** `adb shell input tap X Y` where X,Y are screen pixel coordinates (same coordinate space as screencap). The app runs in true immersive fullscreen, so screen coordinates map 1:1 to SurfaceView coordinates with no status bar offset.
- **Debug touch loop:** Screenshot → identify element coordinates → `adb shell input tap X Y` → screenshot again to verify. Be precise with coordinates — the compositor uses 2x scale, so Wayland logical coordinates are physical/2. Nearby UI elements (e.g. tab close "X" vs hamburger menu "≡") are easy to confuse.
