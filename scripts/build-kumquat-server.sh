#!/bin/bash
# Cross-compile the kumquat server (rutabaga_gfx/kumquat/server) for
# Android. Links against libgfxstream_backend.so built by
# scripts/build-gfxstream-backend.sh.
#
# This is the host-side endpoint of the gfxstream protocol from the
# guest's perspective: it accepts AF_UNIX SEQPACKET kumquat-protocol
# connections (default /tmp/kumquat-gpu-0), instantiates a rutabaga
# context backed by libgfxstream_backend, and dispatches guest Vulkan
# commands to the device's real Vulkan stack.
#
# Output:
#   build/kumquat-server-android/kumquat (aarch64 dynamic ELF executable)
#
# See notes/gfxstream-bridge.md.
#
# Usage:
#   bash scripts/build-kumquat-server.sh           # incremental
#   bash scripts/build-kumquat-server.sh --clean   # wipe build tree first

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"
RUTABAGA_DIR="$(dep_dir rutabaga_gfx)"

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
API=28
TRIPLE=aarch64-linux-android
RUST_TARGET=aarch64-linux-android

CC="$NDK_BIN/${TRIPLE}${API}-clang"
CXX="$NDK_BIN/${TRIPLE}${API}-clang++"
AR="$NDK_BIN/llvm-ar"
[ -x "$CC" ] || { echo "ERROR: NDK clang missing at $CC"; exit 1; }

command -v cargo >/dev/null || { echo "ERROR: cargo not on PATH"; exit 1; }
rustup target list --installed 2>/dev/null | grep -q "^${RUST_TARGET}\$" || {
    echo "ERROR: rustup target ${RUST_TARGET} missing." >&2
    echo "       Install with: rustup target add ${RUST_TARGET}" >&2
    exit 1
}

# ── Vendored deps + patches ──
dep_ensure rutabaga_gfx

PATCH_DIR="$REPO_DIR/deps/rutabaga-patches/rutabaga_gfx"
shopt -s nullglob
PATCH_FILES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
if [ "${#PATCH_FILES[@]}" -gt 0 ]; then
    PATCH_HASH="$(cat "${PATCH_FILES[@]}" | sha1sum | cut -c1-12)"
    PATCH_SENTINEL="$RUTABAGA_DIR/.tawc-patches-applied-$PATCH_HASH"
    if [ ! -f "$PATCH_SENTINEL" ]; then
        rm -f "$RUTABAGA_DIR"/.tawc-patches-applied-*
        git -C "$RUTABAGA_DIR" reset --hard --quiet HEAD
        git -C "$RUTABAGA_DIR" clean -fdx --quiet
        for p in "${PATCH_FILES[@]}"; do
            echo "==> patch rutabaga_gfx: $(basename "$p")"
            ( cd "$RUTABAGA_DIR" && patch -p1 --no-backup-if-mismatch < "$p" >/dev/null )
        done
        touch "$PATCH_SENTINEL"
    fi
fi

# Need libgfxstream_backend.so for the gfxstream feature.
GFXSTREAM_LIB_DIR="$REPO_DIR/build/gfxstream-android"
[ -f "$GFXSTREAM_LIB_DIR/libgfxstream_backend.so" ] || {
    echo "ERROR: libgfxstream_backend.so missing at $GFXSTREAM_LIB_DIR." >&2
    echo "       Run: bash scripts/build-gfxstream-backend.sh" >&2
    exit 1
}

# ── Build tree ──
OUT_DIR="$REPO_DIR/build/kumquat-server-android"
CARGO_DIR="$OUT_DIR/cargo"

if [ "$CLEAN" = "1" ]; then
    echo "==> wiping $OUT_DIR"
    rm -rf "$OUT_DIR"
fi

mkdir -p "$OUT_DIR" "$CARGO_DIR/.cargo"

# ── Cargo + NDK glue ──
# cargo needs to know how to link for the android target, and rutabaga's
# build.rs needs GFXSTREAM_PATH_RELEASE to find libgfxstream_backend.so
# (skips its pkg-config probe for aemu_*; gfxstream is built with
# GFXSTREAM_UNSTABLE which is the only-gfxstream-needed mode).
CARGO_TARGET_TRIPLE_UPPER="$(echo "$RUST_TARGET" | tr 'a-z-' 'A-Z_')"
cat >"$CARGO_DIR/.cargo/config.toml" <<EOF
[target.${RUST_TARGET}]
linker = "$CC"
ar = "$AR"
EOF

echo "==> cargo build kumquat (target=${RUST_TARGET}, release, gfxstream feature)"
RUST_TARGET_UNDERSCORE="${RUST_TARGET//-/_}"
RUST_TARGET_UPPER="$(echo "$RUST_TARGET_UNDERSCORE" | tr 'a-z' 'A-Z')"
# Belt + braces: cargo config.toml's [target.…].linker should be enough,
# but in cross-compile setups CC_<target> / CARGO_TARGET_<UPPER>_LINKER /
# RUSTFLAGS=-Clinker get conflated. Set all three so the NDK clang
# definitely owns the link step (not /usr/bin/ld via cc-rs).
( cd "$RUTABAGA_DIR" && env \
    CARGO_TARGET_DIR="$CARGO_DIR/target" \
    CARGO_HOME="$CARGO_DIR" \
    "CC_${RUST_TARGET_UNDERSCORE}=$CC" \
    "CXX_${RUST_TARGET_UNDERSCORE}=$CXX" \
    "AR_${RUST_TARGET_UNDERSCORE}=$AR" \
    "CARGO_TARGET_${RUST_TARGET_UPPER}_LINKER=$CC" \
    RUSTFLAGS="-C linker=$CC" \
    GFXSTREAM_PATH_RELEASE="$GFXSTREAM_LIB_DIR" \
    USE_CLANG=1 \
    cargo build -p kumquat_virtio --target="$RUST_TARGET" --release --features gfxstream )

KUMQUAT_BIN="$CARGO_DIR/target/${RUST_TARGET}/release/kumquat"
[ -f "$KUMQUAT_BIN" ] || { echo "ERROR: cargo did not produce $KUMQUAT_BIN" >&2; exit 1; }

cp "$KUMQUAT_BIN" "$OUT_DIR/kumquat"

# Stage as jniLib so PackageManager extracts it into nativeLibraryDir
# under the apk_data_file SELinux label — the only place an
# untrusted_app process is allowed to execute a binary on Android 10+.
# The kumquat server has to run in the *same* SELinux context as the
# in-rootfs client (untrusted_app:s0:c<...>) for SCM_RIGHTS fd-passing
# to work; if it ran as `magisk` (under `su`) the kernel silently drops
# the FD half of the response and the guest sees -EINVAL on the very
# first ResourceCreateBlob (the AddressSpaceStream blob). Same trick
# tawcroot uses (libtawcroot.so).
JNILIBS_DIR="$REPO_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"
cp "$KUMQUAT_BIN" "$JNILIBS_DIR/libkumquat.so"

# libgfxstream_backend.so + libc++_shared.so ride alongside as real .so
# files in the same dir, so when the broker spawns kumquat with
# LD_LIBRARY_PATH=<nativeLibraryDir> the dynamic linker resolves both.
cp "$GFXSTREAM_LIB_DIR/libgfxstream_backend.so" "$JNILIBS_DIR/libgfxstream_backend.so"
LIBCPP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
cp "$LIBCPP" "$JNILIBS_DIR/libc++_shared.so"

echo "==> built kumquat:"
ls -la "$OUT_DIR/kumquat"
file "$OUT_DIR/kumquat"
echo "==> staged jniLibs:"
ls -la "$JNILIBS_DIR/libkumquat.so" "$JNILIBS_DIR/libgfxstream_backend.so" "$JNILIBS_DIR/libc++_shared.so"
