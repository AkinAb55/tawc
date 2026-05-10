#!/bin/bash
# Stage the chroot-side gfxstream-bridge bits into a rootfs:
# `libvulkan_gfxstream.so` + the Vulkan ICD JSON. Both come from
# scripts/build-mesa-gfxstream.sh (host-side aarch64 cross-build of
# Mesa's gfxstream-vk driver with the kumquat transport enabled) and
# need to live under `/usr/local/` inside the rootfs so the chroot's
# vulkan-icd-loader picks them up via the `VK_ICD_FILENAMES` env var
# RootfsEnv sets in the gfxstream branch.
#
# The kumquat *server* runs in the compositor process — see
# notes/gfxstream-bridge.md and compositor/src/bridge.rs. There is no
# daemon to start/stop here. This script's only job is the file
# staging (eventually replaced by an in-app BridgeInstallProvider that
# lays the same files down at install time, like LibhybrisInstallProvider).
#
# Usage:
#   bash scripts/bridge-setup.sh [install-id]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/select-device.sh
source "$SCRIPT_DIR/lib/select-device.sh"
# shellcheck source=lib/tawc-scratch.sh
source "$SCRIPT_DIR/lib/tawc-scratch.sh"
# shellcheck source=lib/tawc-exec.sh
source "$SCRIPT_DIR/lib/tawc-exec.sh"
# shellcheck source=lib/tawc-install-id.sh
source "$SCRIPT_DIR/lib/tawc-install-id.sh"

ROOTFS_DIR="/data/data/me.phie.tawc/distros/$TAWC_INSTALL_ID/rootfs"
MESA_LIB="$REPO_DIR/build/mesa-aarch64/install/usr/local/lib/libvulkan_gfxstream.so"
MESA_ICD="$REPO_DIR/build/mesa-aarch64/install/usr/local/share/vulkan/icd.d/gfxstream_vk_icd.aarch64.json"

if [ ! -f "$MESA_LIB" ] || [ ! -f "$MESA_ICD" ]; then
    echo "==> building mesa-gfxstream (libvulkan_gfxstream.so + ICD)"
    bash "$SCRIPT_DIR/build-mesa-gfxstream.sh"
fi

echo "==> staging libvulkan_gfxstream.so + ICD into $ROOTFS_DIR"
DEVICE_STAGE_DIR="$TAWC_SCRATCH/bridge-stage"
adb shell "mkdir -p $DEVICE_STAGE_DIR" >/dev/null
adb push "$MESA_LIB" "$MESA_ICD" "$DEVICE_STAGE_DIR/" >/dev/null
"$TAWC_EXEC_BIN" /system/bin/sh -c "
    mkdir -p $ROOTFS_DIR/usr/local/lib $ROOTFS_DIR/usr/local/share/vulkan/icd.d &&
    cp $DEVICE_STAGE_DIR/libvulkan_gfxstream.so $ROOTFS_DIR/usr/local/lib/ &&
    cp $DEVICE_STAGE_DIR/gfxstream_vk_icd.aarch64.json $ROOTFS_DIR/usr/local/share/vulkan/icd.d/
" >/dev/null

echo "==> done. The kumquat server runs in the compositor process; nothing else to start."
