#!/bin/bash
# Build the GTK3 and GTK4 debug apps on the phone inside the chroot.
# Run from the host. Pushes sources, installs deps if needed, compiles.
#
# Usage:
#   testing/build-debug-app.sh            # build both
#   testing/build-debug-app.sh gtk3       # just gtk3
#   testing/build-debug-app.sh gtk4       # just gtk4
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

TARGET="${1:-all}"

build_one() {
    local app_name="$1"     # e.g. gtk3-debug-app
    local pkg="$2"          # e.g. gtk3 or gtk4
    local src_dir="$SCRIPT_DIR/$app_name"
    local build_dir="/data/local/arch-chroot/tmp/$app_name"

    echo "=== $app_name: pushing source ==="
    adb push "$src_dir/$app_name.c" "/data/local/tmp/$app_name.c" >/dev/null
    adb push "$src_dir/build.sh" "/data/local/tmp/$app_name-build.sh" >/dev/null
    adb shell "su -c 'mkdir -p $build_dir && cp /data/local/tmp/$app_name.c $build_dir/$app_name.c && cp /data/local/tmp/$app_name-build.sh $build_dir/build.sh'"

    echo "=== $app_name: ensuring build deps ($pkg, pkg-config) ==="
    adb shell "/system_ext/bin/bash /data/local/tmp/arch-chroot-run 'pacman -Q $pkg pkg-config >/dev/null 2>&1 || pacman -Sy --noconfirm $pkg pkg-config'"

    echo "=== $app_name: building ==="
    adb shell "/system_ext/bin/bash /data/local/tmp/arch-chroot-run '/bin/bash /tmp/$app_name/build.sh'"

    echo "=== $app_name: done ==="
    echo "Binary (inside chroot): /tmp/$app_name/$app_name"
}

case "$TARGET" in
    gtk3) build_one gtk3-debug-app gtk3 ;;
    gtk4) build_one gtk4-debug-app gtk4 ;;
    all)
        build_one gtk3-debug-app gtk3
        build_one gtk4-debug-app gtk4
        ;;
    *)
        echo "Usage: $0 [gtk3|gtk4|all]" >&2
        exit 1
        ;;
esac
