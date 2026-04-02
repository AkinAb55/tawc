#!/bin/bash
# Build gtk3-debug-app on the phone inside the chroot.
# Run from the host. Pushes source, installs deps if needed, compiles.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_DIR="$SCRIPT_DIR/gtk3-debug-app"
CHROOT_DIR="/data/local/arch-chroot"
BUILD_DIR="$CHROOT_DIR/tmp/gtk3-debug-app"

echo "=== Pushing source ==="
adb shell su -c "mkdir -p $BUILD_DIR"
adb push "$SOURCE_DIR/gtk3-debug-app.c" "/data/local/tmp/gtk3-debug-app.c"
adb push "$SOURCE_DIR/build.sh" "/data/local/tmp/build.sh"
adb shell su -c "cp /data/local/tmp/gtk3-debug-app.c /data/local/tmp/build.sh $BUILD_DIR/"

echo "=== Ensuring build deps ==="
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run 'pacman -Q gtk3 pkg-config >/dev/null 2>&1 || pacman -Sy --noconfirm gtk3 pkg-config'"

echo "=== Building ==="
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run '/bin/bash /tmp/gtk3-debug-app/build.sh'"

echo "=== Done ==="
echo "Binary: /tmp/gtk3-debug-app/gtk3-debug-app (inside chroot)"
echo "Run:    adb shell su -c \"/system_ext/bin/bash /data/local/tmp/arch-chroot-run '/tmp/gtk3-debug-app/gtk3-debug-app text-input'\""
