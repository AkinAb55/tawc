# Building and Deploying

## Environment

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk  # JDK 26 crashes Kotlin Gradle plugin 2.1.20
export ANDROID_NDK_HOME=/home/ai/android-sdk/ndk/27.2.12479018
export ANDROID_HOME=/home/ai/android-sdk
```

## Build the APK

```bash
cd server && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
```

The Gradle build invokes `cargo ndk` for the Rust compositor automatically.

## Install and Launch

```bash
adb install -r server/app/build/outputs/apk/debug/app-debug.apk && \
adb shell am force-stop me.phie.tawc && \
adb shell am start -n me.phie.tawc/.compositor.CompositorActivity
```

After reinstalling: the compositor restarts with a new Wayland socket. Any running
chroot clients (Firefox, etc.) will be connected to the old socket and show black
screens. Kill them and relaunch.

## Full Build and Deploy

```bash
cd /home/ai/tawc/server/compositor && \
cargo ndk --target arm64-v8a --platform 29 -- build --release && \
cd ../.. && \
server/gradlew -p server assembleDebug && \
adb install -r server/app/build/outputs/apk/debug/app-debug.apk && \
adb shell am force-stop me.phie.tawc && \
adb shell am start -n me.phie.tawc/.compositor.CompositorActivity
```

## Device Setup

SELinux enforcing mode is supported. `ChrootMounter` applies the needed
SELinux policy rule (`type_transition magisk tmpfs file appdomain_tmpfs`)
via `magiskpolicy --live` on every chroot entry.

## Chroot package gotchas

- **Always `pacman -Syu` before installing GTK4 (or anything else recent).**
  Plain `pacman -S gtk4` installs the current gtk4 package but does **not**
  upgrade already-installed deps like `glib2`. GTK4 4.22 references
  `g_get_monotonic_time_ns`, which only exists in `glib2` >= 2.88 — if the
  chroot still has an older glib2 (e.g. 2.86.4), `gtk4-demo` will fail with
  `symbol lookup error: /usr/lib/libgtk-4.so.1: undefined symbol:
  g_get_monotonic_time_ns` on the first lazy PLT resolution. `pacman -Syu`
  (or `pacman -Sy gtk4` to at least pull a fresh package db) fixes it.
  This is not a tawc or ALARM bug; it's just how pacman works when you
  skip the sync step.

## Vendored xkb data

The compositor needs xkeyboard-config data files for xkbcommon to load keymaps.
These are vendored in `server/app/src/main/assets/xkb/` and extracted to the
app's data dir (`files/xkb`) by `CompositorService.onCreate` before
`nativeStartCompositor` runs — must happen before xkbcommon's keymap lookup,
otherwise `seat.add_keyboard(XkbConfig::default(), ...)` returns a NULL
keymap and the compositor SIGSEGVs. The extractor is idempotent
(versioned via `files/xkb/.version`), so it's a no-op after the first run.
The data came from the chroot's `/usr/share/xkeyboard-config-2/` (Arch
Linux ARM `xkeyboard-config` package).

To update from the chroot:
```bash
adb shell "su -c 'cd /data/data/me.phie.tawc/installations/arch/rootfs/usr/share/xkeyboard-config-2 && tar cf /data/local/tmp/xkb-data.tar .'"
adb pull /data/local/tmp/xkb-data.tar /tmp/xkb-data.tar
rm -rf server/app/src/main/assets/xkb
mkdir -p server/app/src/main/assets/xkb
tar xf /tmp/xkb-data.tar -C server/app/src/main/assets/xkb/
rm /tmp/xkb-data.tar
adb shell "rm /data/local/tmp/xkb-data.tar"
```

## Build System Details

- Rust compositor cross-compiled for `aarch64-linux-android` via `cargo-ndk`
- Gradle invokes cargo-ndk, copies `.so` into `jniLibs/arm64-v8a/`
- Target API level: 29 (Android 10) minimum
- libxkbcommon cross-compiled from source with NDK toolchain (run
  `bash client/build-libxkbcommon` once after fresh clone; clones a
  pinned upstream tag into `./libxkbcommon/`, no patches)
- wayland-rs uses pure Rust backend (no libwayland dependency)

Client-side libraries (built inside the chroot, not cross-compiled):
- libhybris provides EGL/GLES by loading Android's GPU drivers
- GL shims (libgl-shim.c, libglesv2-shim.c) stub out GLX so Mesa is never probed

## libhybris

Our fork lives at `./libhybris` (clone with `git clone https://github.com/wmww/libhybris.git ./libhybris`).

Build and install to the phone's chroot:
```bash
bash client/build-libhybris          # incremental build
bash client/build-libhybris --clean   # full reconfigure
```

This tars the local `./libhybris` source, pushes it to the phone, and builds inside the chroot. Edit `./libhybris` locally, then re-run the script to deploy.

## libxkbcommon

We do not patch xkbcommon. The checkout at `./libxkbcommon/` (gitignored)
is pure upstream at a pinned tag plus two Android meson cross-files
generated at build time. The static `libxkbcommon.a` it produces gets
linked into `libcompositor.so` via `compositor/build.rs`.

Build for one or both Android ABIs (host-side, NDK toolchain):
```bash
bash client/build-libxkbcommon                  # aarch64 (default — real device)
bash client/build-libxkbcommon --abi=x86_64     # emulator
bash client/build-libxkbcommon --abi=both
bash client/build-libxkbcommon --clean          # reconfigure from scratch
```

The script clones the pinned tag (see `LIBXKB_TAG` near the top of
`client/build-libxkbcommon`) into `./libxkbcommon/` if missing. Bumping
the version means changing `LIBXKB_TAG` and rerunning with `--clean`.
NDK location is auto-detected via `$ANDROID_NDK_HOME` or the SDK install
under `$ANDROID_HOME/ndk/` (matching the rest of the host scripts).

## Debug App & Integration Tests

See [testing.md](testing.md) for full details.

```bash
# Build debug app on phone:
bash testing/build-debug-app.sh

# Run integration tests (from host):
cd testing/integration && cargo test -- --nocapture --test-threads=1
```
