#!/bin/bash
# Cross-build rootfs integration-test apps on the host. Outputs are copied
# into the target rootfs by scripts/install-test-deps.sh; no compiler runs
# on the device.
#
# Usage:
#   scripts/build-test-apps.sh --abi=aarch64 --distro=arch
#   scripts/build-test-apps.sh --abi=x86_64 --distro=arch --app=gtk4-debug-app
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ABI=""
DISTRO="${TAWC_SYSROOT_DISTRO:-arch}"
ONLY_APP=""
CLEAN=0
for arg in "$@"; do
    case "$arg" in
        --abi=aarch64|--abi=arm64) ABI="aarch64" ;;
        --abi=x86_64)              ABI="x86_64" ;;
        --distro=*)                DISTRO="${arg#--distro=}" ;;
        --app=*)                   ONLY_APP="${arg#--app=}" ;;
        --clean)                   CLEAN=1 ;;
        -h|--help)
            sed -n '2,/^set -/p' "$0" | sed 's/^# \?//;$d'
            exit 0
            ;;
        *) echo "ERROR: unknown arg: $arg" >&2; exit 1 ;;
    esac
done
[ -n "$ABI" ] || { echo "ERROR: pass --abi=aarch64 or --abi=x86_64" >&2; exit 1; }

SYSROOT="$REPO_DIR/build/sysroots/$DISTRO-$ABI"
if [ ! -d "$SYSROOT/usr" ] || ! grep -q " full " "$SYSROOT/.tawc-sysroot" 2>/dev/null; then
    "$SCRIPT_DIR/build-host-sysroot.sh" "--abi=$ABI" "--distro=$DISTRO" --profile=full
fi

case "$ABI" in
    aarch64)
        CC="${TAWC_AARCH64_CC:-aarch64-linux-gnu-gcc}"
        NDK_TRIPLE="aarch64-linux-android"
        NDK_API=29
        ;;
    x86_64)
        if command -v x86_64-linux-gnu-gcc >/dev/null; then
            CC="${TAWC_X86_64_CC:-x86_64-linux-gnu-gcc}"
        else
            CC="${TAWC_X86_64_CC:-gcc}"
        fi
        NDK_TRIPLE="x86_64-linux-android"
        NDK_API=29
        ;;
esac
command -v "$CC" >/dev/null || {
    echo "ERROR: $CC not on PATH. See notes/building.md." >&2
    exit 1
}
command -v pkg-config >/dev/null || { echo "ERROR: pkg-config not on PATH" >&2; exit 1; }
command -v wayland-scanner >/dev/null || { echo "ERROR: wayland-scanner not on PATH" >&2; exit 1; }

OUT_ROOT="$REPO_DIR/build/test-apps/$DISTRO-$ABI"
[ "$CLEAN" = "1" ] && rm -rf "$OUT_ROOT"
mkdir -p "$OUT_ROOT"

export PKG_CONFIG_SYSROOT_DIR="$SYSROOT"
export PKG_CONFIG_LIBDIR="$SYSROOT/usr/lib/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export PKG_CONFIG_PATH=""

CFLAGS_SYSROOT=("--sysroot=$SYSROOT" "-B$SYSROOT/usr/lib" "-L$SYSROOT/usr/lib" "-L$SYSROOT/lib" -O2 -g)
LDFLAGS_SYSROOT=("--sysroot=$SYSROOT" -Wl,-rpath-link,"$SYSROOT/usr/lib" -Wl,-rpath-link,"$SYSROOT/usr/lib/hybris")

pc_cflags() {
    pkg-config --cflags "$@"
}

pc_libs() {
    pkg-config --libs "$@"
}

pkg_var_path() {
    local pkg="$1" var="$2" value
    value="$(pkg-config --variable="$var" "$pkg")"
    case "$value" in
        "$SYSROOT"/*) echo "$value" ;;
        /*) echo "$SYSROOT$value" ;;
        *)  echo "$value" ;;
    esac
}

app_enabled() {
    [ -z "$ONLY_APP" ] || [ "$ONLY_APP" = "$1" ]
}

prepare_app_dir() {
    local app="$1"
    local out="$OUT_ROOT/$app"
    rm -rf "$out"
    mkdir -p "$out"
    echo "$out"
}

build_gtk4_debug_app() {
    local app=gtk4-debug-app src="$REPO_DIR/tests/apps/gtk4-debug-app" out
    out="$(prepare_app_dir "$app")"
    # shellcheck disable=SC2046
    "$CC" "${CFLAGS_SYSROOT[@]}" -o "$out/$app" "$src/gtk4-debug-app.c" \
        $(pc_cflags gtk4) $(pc_libs gtk4) "${LDFLAGS_SYSROOT[@]}" -Wall -Wextra
}

build_wayland_debug_app() {
    local app=wayland-debug-app src="$REPO_DIR/tests/apps/wayland-debug-app" out
    out="$(prepare_app_dir "$app")"
    local xdg text
    xdg="$(pkg_var_path wayland-protocols pkgdatadir)/stable/xdg-shell/xdg-shell.xml"
    text="$(pkg_var_path wayland-protocols pkgdatadir)/unstable/text-input/text-input-unstable-v3.xml"
    wayland-scanner client-header "$xdg" "$out/xdg-shell-client-protocol.h"
    wayland-scanner private-code  "$xdg" "$out/xdg-shell-protocol.c"
    wayland-scanner client-header "$text" "$out/text-input-unstable-v3-client-protocol.h"
    wayland-scanner private-code  "$text" "$out/text-input-unstable-v3-protocol.c"
    # shellcheck disable=SC2046
    "$CC" "${CFLAGS_SYSROOT[@]}" -I"$out" -o "$out/$app" \
        "$src/wayland-debug-app.c" "$out/xdg-shell-protocol.c" "$out/text-input-unstable-v3-protocol.c" \
        $(pc_cflags wayland-client cairo) $(pc_libs wayland-client cairo) \
        "${LDFLAGS_SYSROOT[@]}" -std=c11 -Wall -Wextra -Werror
}

build_eglx11_test() {
    local app=eglx11-test src="$REPO_DIR/tests/apps/eglx11-test" out
    out="$(prepare_app_dir "$app")"
    # shellcheck disable=SC2046
    "$CC" "${CFLAGS_SYSROOT[@]}" -o "$out/$app" "$src/eglx11-test.c" \
        $(pc_cflags x11) $(pc_libs x11) \
        -lEGL -lGLESv2 -L"$SYSROOT/usr/lib/hybris" -Wl,-rpath,/usr/lib/hybris \
        -ldl "${LDFLAGS_SYSROOT[@]}" -Wall -Wextra
}

ensure_libhybris_for_link() {
    if [ "$ABI" != "aarch64" ]; then
        return 1
    fi
    if [ ! -f "$SYSROOT/usr/lib/hybris/libhybris-common.so" ]; then
        "$SCRIPT_DIR/build-libhybris.sh"
        mkdir -p "$SYSROOT/usr/lib/hybris"
        cp -a "$REPO_DIR/build/libhybris-aarch64/install/usr/lib/hybris/." "$SYSROOT/usr/lib/hybris/"
    fi
}

build_tawc_dri_test() {
    ensure_libhybris_for_link || return 0
    local app=tawc-dri-test src="$REPO_DIR/tests/apps/tawc-dri-test" out
    out="$(prepare_app_dir "$app")"
    # shellcheck disable=SC2046
    "$CC" "${CFLAGS_SYSROOT[@]}" -o "$out/$app" "$src/tawc-dri-test.c" \
        $(pc_cflags xcb) $(pc_libs xcb) \
        -L"$SYSROOT/usr/lib/hybris" -Wl,-rpath,/usr/lib/hybris -lhybris-common \
        -ldl "${LDFLAGS_SYSROOT[@]}" -Wall -Wextra
}

find_ndk_clang() {
    local ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk}}"
    if [ -x "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/${NDK_TRIPLE}${NDK_API}-clang" ]; then
        echo "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/${NDK_TRIPLE}${NDK_API}-clang"
        return
    fi
    local ndk_versioned
    ndk_versioned=$(ls -d "$ndk_root"/*/toolchains/llvm/prebuilt/linux-x86_64/bin 2>/dev/null | sort -V | tail -n1)
    [ -n "$ndk_versioned" ] || {
        echo "ERROR: cannot locate Android NDK under $ndk_root" >&2
        exit 1
    }
    echo "$ndk_versioned/${NDK_TRIPLE}${NDK_API}-clang"
}

build_libhybris_tls_repro() {
    ensure_libhybris_for_link || return 0
    local app=libhybris-tls-repro src="$REPO_DIR/tests/apps/libhybris-tls-repro" out
    out="$(prepare_app_dir "$app")"
    "$CC" "${CFLAGS_SYSROOT[@]}" -O0 -g -o "$out/$app" "$src/repro.c" \
        -L"$SYSROOT/usr/lib/hybris" -lhybris-common -Wl,-rpath,/usr/lib/hybris \
        -pthread "${LDFLAGS_SYSROOT[@]}" -Wall -Wextra
    local ndk_clang
    ndk_clang="$(find_ndk_clang)"
    "$ndk_clang" -fPIC -shared -o "$out/tls_lib.so" "$src/tls_lib.c"
    "$ndk_clang" -fPIC -shared -o "$out/weak_lib.so" "$src/weak_lib.c"
}

build_adreno_struct_varying() {
    local app=adreno-struct-varying src="$REPO_DIR/tests/apps/adreno-struct-varying" out
    out="$(prepare_app_dir "$app")"
    local xdg
    xdg="$(pkg_var_path wayland-protocols pkgdatadir)/stable/xdg-shell/xdg-shell.xml"
    wayland-scanner client-header "$xdg" "$out/xdg-shell-client-protocol.h"
    wayland-scanner private-code  "$xdg" "$out/xdg-shell-protocol.c"
    # shellcheck disable=SC2046
    "$CC" "${CFLAGS_SYSROOT[@]}" -I"$out" -Wl,--no-as-needed -o "$out/$app" \
        "$src/adreno-struct-varying.c" "$out/xdg-shell-protocol.c" \
        $(pc_cflags wayland-client wayland-egl) \
        -L"$SYSROOT/usr/lib/hybris" -Wl,-rpath,/usr/lib/hybris \
        -lwayland-egl -lwayland-client -lEGL -lGLESv2 -ldl \
        "${LDFLAGS_SYSROOT[@]}" -Wall -Wextra
}

APPS=(gtk4-debug-app wayland-debug-app eglx11-test)
if [ "$ABI" = "aarch64" ]; then
    APPS+=(tawc-dri-test libhybris-tls-repro)
fi

for app in "${APPS[@]}"; do
    app_enabled "$app" || continue
    echo "==> [$DISTRO/$ABI] building $app"
    case "$app" in
        gtk4-debug-app)       build_gtk4_debug_app ;;
        wayland-debug-app)    build_wayland_debug_app ;;
        eglx11-test)          build_eglx11_test ;;
        tawc-dri-test)        build_tawc_dri_test ;;
        libhybris-tls-repro)  build_libhybris_tls_repro ;;
    esac
done

if [ "$ONLY_APP" = "adreno-struct-varying" ]; then
    echo "==> [$DISTRO/$ABI] building adreno-struct-varying"
    build_adreno_struct_varying
fi

echo "==> test app outputs: $OUT_ROOT"
