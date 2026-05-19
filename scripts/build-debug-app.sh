#!/bin/bash
# Build the GTK4 debug app on the host and copy it into the rootfs.
#
# Runtime deps are normally installed by scripts/run-integration-tests.sh.
#
# Usage:
#   scripts/build-debug-app.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TAWC_EXEC="${TAWC_EXEC:-$ROOT_DIR/scripts/tawc-exec.sh}"

# shellcheck source=../scripts/lib/select-device.sh
source "$ROOT_DIR/scripts/lib/select-device.sh"
# shellcheck source=../scripts/lib/tawc-scratch.sh
source "$ROOT_DIR/scripts/lib/tawc-scratch.sh"
# shellcheck source=../scripts/lib/tawc-install-id.sh
source "$ROOT_DIR/scripts/lib/tawc-install-id.sh"

app_name="gtk4-debug-app"
chroot_root="/data/data/me.phie.tawc/distros/$TAWC_INSTALL_ID/rootfs"
bin_dir="$chroot_root/usr/local/bin"
host_arch=$("$TAWC_EXEC" /system/bin/uname -m | tr -d '\r\n')
case "$host_arch" in
    aarch64) build_abi=aarch64 ;;
    x86_64)  build_abi=x86_64 ;;
    *) echo "ERROR: unsupported rootfs arch '$host_arch'" >&2; exit 1 ;;
esac
distro_key=$("$TAWC_EXEC" /system/bin/cat "/data/data/me.phie.tawc/distros/$TAWC_INSTALL_ID/metadata.json" \
    | tr -d '\r' \
    | sed -n 's/.*"distro"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    | head -n1)
build_distro="${TAWC_SYSROOT_DISTRO:-$distro_key}"

echo "=== $app_name: cross-building ($build_distro/$build_abi) ==="
make -C "$ROOT_DIR/tests/apps" -j"$(nproc)" "DISTRO=$build_distro" "ABI=$build_abi" "$app_name"

echo "=== $app_name: copying ==="
"$TAWC_EXEC" /system/bin/sh -c "mkdir -p $TAWC_SCRATCH"
adb shell rm -rf "$TAWC_SCRATCH/$app_name-out" >/dev/null
adb push "$ROOT_DIR/build/test-apps/$build_distro-$build_abi/$app_name" "$TAWC_SCRATCH/$app_name-out" >/dev/null
"$TAWC_EXEC" /system/bin/sh -c "mkdir -p $bin_dir && cp $TAWC_SCRATCH/$app_name-out/$app_name $bin_dir/$app_name && chmod a+rx $bin_dir/$app_name"

echo "=== $app_name: done ==="
echo "Binary (inside chroot): /usr/local/bin/$app_name"
