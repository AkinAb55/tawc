#!/bin/bash
# Cross-compile gfxstream's host renderer (libgfxstream_backend.so) for
# Android via the NDK. Vulkan-only build — no GLES, no composer — to
# keep the dep surface minimal for the bridge bring-up.
#
# Output:
#   build/gfxstream-android/libgfxstream_backend.so
#
# This is the "host" side of the gfxstream protocol from the bridge's
# perspective: it decodes guest Vulkan command streams and dispatches
# them via dlopen("libvulkan.so") + VK_USE_PLATFORM_ANDROID_KHR. Linked
# into the kumquat server (rutabaga_gfx) which the compositor app runs
# in-process.
#
# See notes/gfxstream-bridge.md.
#
# Usage:
#   bash scripts/build-gfxstream-backend.sh           # incremental
#   bash scripts/build-gfxstream-backend.sh --clean   # wipe build tree first

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"
GFXSTREAM_DIR="$(dep_dir gfxstream)"
PATCH_DIR="$REPO_DIR/deps/gfxstream-patches/gfxstream"

CLEAN=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        *) echo "ERROR: unknown arg: $arg" >&2; exit 1 ;;
    esac
done

# ── NDK + API level ──
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
[ -d "$NDK_DIR" ] || { echo "ERROR: no NDK at $ANDROID_HOME/ndk/*"; exit 1; }
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
# API 28 is the floor for our app (compileSdk in app/build.gradle.kts);
# need ≥ 26 for AHardwareBuffer_*, ≥ 28 for vkGetMemoryAndroidHardwareBufferANDROID.
API=28
TRIPLE=aarch64-linux-android

CC="$NDK_BIN/${TRIPLE}${API}-clang"
CXX="$NDK_BIN/${TRIPLE}${API}-clang++"
[ -x "$CC" ] || { echo "ERROR: NDK clang missing at $CC"; exit 1; }

for tool in meson ninja pkg-config; do
    command -v "$tool" >/dev/null || { echo "ERROR: '$tool' not on PATH" >&2; exit 1; }
done

# ── Vendored gfxstream ──
dep_ensure gfxstream

# ── Patches (xwayland/mesa-style sentinel) ──
shopt -s nullglob
PATCH_FILES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
if [ "${#PATCH_FILES[@]}" -gt 0 ]; then
    PATCH_HASH="$(cat "${PATCH_FILES[@]}" | sha1sum | cut -c1-12)"
    PATCH_SENTINEL="$GFXSTREAM_DIR/.tawc-patches-applied-$PATCH_HASH"
    if [ ! -f "$PATCH_SENTINEL" ]; then
        rm -f "$GFXSTREAM_DIR"/.tawc-patches-applied-*
        git -C "$GFXSTREAM_DIR" reset --hard --quiet HEAD
        git -C "$GFXSTREAM_DIR" clean -fdx --quiet
        for p in "${PATCH_FILES[@]}"; do
            echo "==> patch gfxstream: $(basename "$p")"
            ( cd "$GFXSTREAM_DIR" && patch -p1 --no-backup-if-mismatch < "$p" >/dev/null )
        done
        touch "$PATCH_SENTINEL"
    fi
else
    echo "==> no patches in $PATCH_DIR (using working tree as-is)"
fi

# ── Build tree ──
OUT_DIR="$REPO_DIR/build/gfxstream-android"
BUILD_DIR="$GFXSTREAM_DIR/build-android"

if [ "$CLEAN" = "1" ]; then
    echo "==> wiping $OUT_DIR and $BUILD_DIR"
    rm -rf "$OUT_DIR" "$BUILD_DIR"
fi

mkdir -p "$OUT_DIR"

# ── Header shims ──
# NDK's sysroot doesn't ship cutils/native_handle.h (libcutils is part
# of AOSP, not the NDK). gfxstream's vk_android_native_buffer_gfxstream.h
# unconditionally includes it on Android. Make a shims dir holding only
# the files we need from third_party/android/include — we deliberately
# don't expose the android/, vndk/ subdirs there (they would shadow the
# NDK's own android/* headers).
SHIMS_DIR="$OUT_DIR/include-shims"
mkdir -p "$SHIMS_DIR/cutils"
cp -f "$GFXSTREAM_DIR/third_party/android/include/cutils/native_handle.h" \
      "$SHIMS_DIR/cutils/"

# ── Cross file ──
# meson sees host_machine.system='android'; the patches conditionalise
# off that. NDK clang does its own sysroot management — no -isystem needed.
cat >"$OUT_DIR/cross.txt" <<EOF
[binaries]
c = ['$CC', '-I$SHIMS_DIR']
cpp = ['$CXX', '-I$SHIMS_DIR']
ar = '$NDK_BIN/llvm-ar'
strip = '$NDK_BIN/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# ── Configure + build ──
if [ ! -f "$BUILD_DIR/build.ninja" ]; then
    meson setup "$BUILD_DIR" "$GFXSTREAM_DIR" \
        --cross-file "$OUT_DIR/cross.txt" \
        -Dgfxstream-build=host \
        -Ddecoders=auto \
        --buildtype release \
        -Ddefault_library=shared
fi
ninja -C "$BUILD_DIR" host/libgfxstream_backend.so

# ── Stage outputs ──
cp "$BUILD_DIR/host/libgfxstream_backend.so" "$OUT_DIR/"

echo "==> built libgfxstream_backend.so:"
ls -la "$OUT_DIR/libgfxstream_backend.so"
file "$OUT_DIR/libgfxstream_backend.so"
