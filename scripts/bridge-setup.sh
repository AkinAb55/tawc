#!/bin/bash
# Set up the gfxstream-bridge end-to-end on the device for development
# and integration testing.
#
# Splits cleanly into "build/install bits" (does both kumquat and the
# guest-side libvulkan_gfxstream.so) and "start/stop the daemon" (which
# uses the broker, so kumquat runs as the app uid + untrusted_app
# SELinux context — see notes/gfxstream-bridge.md "Why kumquat must run
# as untrusted_app", and BridgeActions.kt for the SCM_RIGHTS reasoning).
#
# Usage:
#   bash scripts/bridge-setup.sh start    # default; build, install, start
#   bash scripts/bridge-setup.sh stop
#   bash scripts/bridge-setup.sh status
#   bash scripts/bridge-setup.sh restart

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

ACTION="${1:-start}"

ROOTFS_DIR="/data/data/me.phie.tawc/distros/$TAWC_INSTALL_ID/rootfs"
SOCKET_GUEST_PATH="/tmp/kumquat-gpu-0"

GFXSTREAM_LIB="$REPO_DIR/build/gfxstream-android/libgfxstream_backend.so"
KUMQUAT_BIN="$REPO_DIR/build/kumquat-server-android/kumquat"
MESA_LIB="$REPO_DIR/build/mesa-aarch64/install/usr/local/lib/libvulkan_gfxstream.so"
MESA_ICD="$REPO_DIR/build/mesa-aarch64/install/usr/local/share/vulkan/icd.d/gfxstream_vk_icd.aarch64.json"

case "$ACTION" in
    start|restart)
        # Build artefacts (idempotent — each script's incremental).
        for f in "$GFXSTREAM_LIB" "$KUMQUAT_BIN" "$MESA_LIB" "$MESA_ICD"; do
            if [ ! -f "$f" ]; then
                echo "==> missing $f — building everything"
                bash "$SCRIPT_DIR/build-mesa-gfxstream.sh"
                bash "$SCRIPT_DIR/build-gfxstream-backend.sh"
                bash "$SCRIPT_DIR/build-kumquat-server.sh"
                break
            fi
        done

        # libvulkan_gfxstream.so + ICD JSON live inside the rootfs at
        # /usr/local/. Until [BridgeInstallProvider] (Phase 1.2) ships,
        # drop them in via the broker (runs as the app uid, owns the
        # rootfs tree).
        echo "==> staging libvulkan_gfxstream.so + ICD into rootfs"
        DEVICE_STAGE_DIR="$TAWC_SCRATCH/bridge-stage"
        adb shell "mkdir -p $DEVICE_STAGE_DIR" >/dev/null
        adb push "$MESA_LIB" "$MESA_ICD" "$DEVICE_STAGE_DIR/" >/dev/null
        "$TAWC_EXEC_BIN" /system/bin/sh -c "
            mkdir -p $ROOTFS_DIR/usr/local/lib $ROOTFS_DIR/usr/local/share/vulkan/icd.d &&
            cp $DEVICE_STAGE_DIR/libvulkan_gfxstream.so $ROOTFS_DIR/usr/local/lib/ &&
            cp $DEVICE_STAGE_DIR/gfxstream_vk_icd.aarch64.json $ROOTFS_DIR/usr/local/share/vulkan/icd.d/
        " >/dev/null

        if [ "$ACTION" = "restart" ]; then
            echo "==> stopping existing daemon"
            "$TAWC_EXEC_BIN" --action stop-bridge-daemon || true
        fi

        echo "==> starting kumquat (socket=$SOCKET_GUEST_PATH inside rootfs)"
        "$TAWC_EXEC_BIN" --action start-bridge-daemon --arg "install=$TAWC_INSTALL_ID"
        ;;

    stop)
        echo "==> stopping kumquat"
        "$TAWC_EXEC_BIN" --action stop-bridge-daemon
        ;;

    status)
        adb shell "pidof kumquat 2>/dev/null && echo RUNNING || echo NOT_RUNNING"
        if "$TAWC_EXEC_BIN" /system/bin/sh -c "test -S $ROOTFS_DIR$SOCKET_GUEST_PATH" >/dev/null 2>&1; then
            echo "socket present at $ROOTFS_DIR$SOCKET_GUEST_PATH"
        else
            echo "socket missing"
        fi
        ;;

    *)
        echo "Usage: $0 {start|stop|status|restart}" >&2
        exit 1
        ;;
esac
