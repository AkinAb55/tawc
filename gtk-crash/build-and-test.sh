#!/bin/bash
# Build and run the GL crash reproducer.
# Requires: phone connected via adb, tawc compositor app installed.
# Usage: bash gtk-crash/build-and-test.sh [iterations]
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
N=${1:-30}

echo "=== Pushing source and building ==="
adb push "$REPO_DIR/client/arch-chroot-run" /data/local/tmp/
# Push repro.c into the chroot's /tmp (visible inside chroot as /tmp)
adb push "$SCRIPT_DIR/repro.c" /data/local/tmp/
adb shell "su -c 'mkdir -p /data/local/arch-chroot/root/gl-crash-repro && cp /data/local/tmp/repro.c /data/local/arch-chroot/root/gl-crash-repro/'"
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run \
    'gcc -o /root/gl-crash-repro/repro /root/gl-crash-repro/repro.c \
     -L/tmp/tawc-wsi -lEGL -lGLESv2 \
     -lwayland-client -ldl -Wall -g && echo BUILD_OK'"

echo ""
echo "=== Ensuring compositor is running ==="
adb shell am start -n me.phie.tawc/.MainActivity 2>&1 | tail -1
sleep 3

echo ""
echo "=== Without GTK ($N iterations) ==="
crashes=0
for i in $(seq 1 "$N"); do
    if adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run \
        '/root/gl-crash-repro/repro'" >/dev/null 2>&1; then
        echo -n "."
    else echo -n "X"; crashes=$((crashes + 1)); fi
done
echo "  $crashes/$N crashed"

echo "=== With GTK ($N iterations) ==="
crashes=0
for i in $(seq 1 "$N"); do
    if adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run \
        '/root/gl-crash-repro/repro gtk'" >/dev/null 2>&1; then
        echo -n "."
    else echo -n "X"; crashes=$((crashes + 1)); fi
done
echo "  $crashes/$N crashed"
