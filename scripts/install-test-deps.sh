#!/bin/bash
# Install integration-test runtime packages and copy host-cross-built test apps.
#
# Usage: scripts/install-test-deps.sh
# Set TAWC_INSTALL_ID=<id> when more than one install exists. Re-run after
# editing tests/apps/**.
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

TAWC_DISTROS_DIR="/data/data/me.phie.tawc/distros/$TAWC_INSTALL_ID"

# Read the distro key out of metadata.json via the broker (runs as app
# uid, can read the private data dir directly — works for every install
# method, no root needed).
DISTRO_KEY=$("$TAWC_EXEC" /system/bin/cat "$TAWC_DISTROS_DIR/metadata.json" \
    | tr -d '\r' \
    | sed -n 's/.*"distro"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    | head -n1)
if [ -z "$DISTRO_KEY" ]; then
    echo "ERROR: could not read distro key from $TAWC_DISTROS_DIR/metadata.json" >&2
    exit 1
fi
echo "=== Detected distro: $DISTRO_KEY ==="

case "$DISTRO_KEY" in
    arch|manjaro)
        # Pacman package set. Comments name the test cases each item
        # exists for. Test app build dependencies are intentionally not
        # installed here; scripts/build-test-apps.sh cross-builds them
        # on the host against build/sysroots/<distro>-<abi>/.
        PKGS=(
            # Runtime libs for host-cross-built test apps.
            gtk4 cairo wayland libx11 libxcb libglvnd
            # apps:: tests — gtk3-demos provides gtk3-demo-application,
            # gtk4-demos provides gtk4-widget-factory; firefox + supertuxkart
            # are real-app tests on hardware buffers.
            gtk3 gtk3-demos gtk4-demos firefox supertuxkart
            # hybris:: / gfxstream:: / cpu_graphics:: tests
            mesa-utils weston vulkan-tools
            # xwayland::test_xwayland_xclock_renders_via_shm — pure-X11 client
            # exercising our bionic-built Xwayland (see notes/xwayland.md).
            xorg-xclock
            # xwayland::test_es2gears_x11_renders_via_ahb — real-app GLES-on-X11
            # client driving the libhybris X11 EGL platform plugin.
            mesa-demos
            # `lxterminal` for apps::test_lxterminal_input_and_exit is
            # in [DEFAULT_BASE_PACKAGES] — no need to list it here.
        )
        # `-Syu` (instead of plain `-S`): refresh local DB in the same
        # transaction we install in, so we never reference a `pkg.tar.xz`
        # the mirror has already rolled forward of. The package cache
        # is wiped automatically by the `tawc-clear-cache.hook`
        # installed at chroot-configure time (see ArchPacmanCommon).
        INSTALL_CMD="pacman -Syu --noconfirm --needed ${PKGS[*]}"
        ;;
    void)
        # Void package set. Logical match for the pacman list:
        #   gtk4              -> gtk4
        #   gtk3              -> gtk+3
        #   gtk3-demos        -> gtk+3-demo (gtk3-demo, gtk3-widget-factory)
        #   gtk4-demos        -> gtk4-demo  (gtk4-demo, gtk4-widget-factory)
        #   mesa-utils        -> glxinfo    (Void splits this out of mesa-demos)
        #   mesa-demos        -> mesa-demos (es2gears_x11 lives here)
        #   vulkan-tools      -> Vulkan-Tools
        #   xorg-xclock       -> xclock
        # supertuxkart, weston, firefox keep their names.
        #
        # `-devel` packages are intentionally absent: test app headers
        # and `.pc` files live in the host sysroot built by
        # scripts/build-host-sysroot.sh.
        #
        # `mesa-dri` is the open-source GL stack (DRI drivers). On a
        # real ARM device it normally sits dormant because libhybris
        # shadows GL/EGL via `/usr/lib/hybris/gl-shims:/usr/lib/hybris`.
        # On x86_64 the default GPU path is gfxstream, and CPU fallback
        # still needs the distro Mesa stack available.
        # Without `mesa-dri` it has no driver to load, the GL init path
        # NULL-branches, and gtk4 segfaults inside libharfbuzz on the
        # first text reshape. Bundling it unconditionally costs ~50 MB
        # on a real device (which won't ever load it) — cheaper than
        # branching the test-deps list by ABI.
        #
        # `dejavu-fonts-ttf` covers the second NULL-deref: the Void
        # bootstrap ships fontconfig + freetype but no actual font
        # files, and harfbuzz NULL-derefs trying to shape with no
        # usable face. Arch's bootstrap already has Bitstream / Liberation.
        PKGS=(
            # Runtime libs for host-cross-built test apps.
            gtk4 cairo wayland libX11 libxcb libglvnd
            gtk+3 gtk+3-demo gtk4-demo firefox supertuxkart
            glxinfo weston Vulkan-Tools
            xclock
            mesa-demos mesa-dri
            dejavu-fonts-ttf
            # `lxterminal` for apps::test_lxterminal_input_and_exit is
            # in [DEFAULT_BASE_PACKAGES] — no need to list it here.
        )
        # xbps quirk: `xbps-install -uy <pkgs>` updates only the listed
        # packages + their deps, NOT the whole system. Without an
        # in-between sysupgrade, fresh packages we install can pull a
        # new libuuid/libblkid that's SONAME-incompatible with the
        # already-installed util-linux, and xbps aborts with "in
        # transaction breaks installed pkg" (caught in the wild between
        # base install and a later test-deps run). So: full sysupgrade
        # first, then install the test packages.
        #
        # No cache wipe here — VoidCommon.installBasePackages already
        # cleared the cache at chroot-install time, and re-running this
        # script after a partial failure is a lot cheaper if the per-
        # package `.xbps` files haven't been blown away.
        INSTALL_CMD="xbps-install -Suy && xbps-install -y ${PKGS[*]}"
        ;;
    *)
        echo "ERROR: unsupported distro '$DISTRO_KEY' (expected arch / manjaro / void)" >&2
        exit 1
        ;;
esac

echo "=== Installing chroot test deps: ${PKGS[*]} ==="

# Glycin (pulled in by GTK/Adwaita to sandbox image loaders) execs
# bwrap; on Android without CONFIG_USER_NS bwrap fails fast with
# "Creating new namespace failed: Operation not permitted", which
# glycin's autodetect (≥ 2.0.1) recognises and falls back to
# NotSandboxed. tawcroot synthesises /proc/sys/kernel/overflow{uid,gid}
# (otherwise SELinux-blocked, which would knock bwrap out earlier with a
# substring glycin doesn't recognise) so the autodetect actually fires.
# See syscalls_fs.c:open_proc_overflow_id_shadow + notes/tawcroot.md.
TAWC_OP_TITLE="install test deps ($DISTRO_KEY)" \
    "$ROOT_DIR/scripts/rootfs-run.sh" "$INSTALL_CMD"

# --- Build/copy phase ---
# Cross-compile each test program on the host from tests/apps/<name>/,
# then copy outputs into the rootfs so the integration suite's path
# checks find /usr/local/bin/<name>. Tests never compile on the device.
copy_test_app() {
    local name="$1"
    local out_dir="$ROOT_DIR/build/test-apps/$BUILD_DISTRO-$BUILD_ABI/$name"
    local staging="$TAWC_SCRATCH/$name-out"
    local rootfs="$TAWC_DISTROS_DIR/rootfs"
    local bin_dir="$rootfs/usr/local/bin"
    local lib_dir="$rootfs/usr/local/lib"

    [ -d "$out_dir" ] || { echo "ERROR: missing $out_dir" >&2; exit 1; }

    echo "=== Copying $name ==="
    "$TAWC_EXEC" /system/bin/sh -c "mkdir -p $TAWC_SCRATCH"
    adb shell rm -rf "$staging" >/dev/null
    adb push "$out_dir" "$staging" >/dev/null
    # cp + chmod via the broker — runs as the app uid which owns the
    # rootfs tree (no su / no run-as / no ownership-flip dance).
    if [ "$name" = "libhybris-tls-repro" ]; then
        "$TAWC_EXEC" /system/bin/sh -c "\
            mkdir -p $bin_dir $lib_dir && \
            cp $staging/$name $bin_dir/$name && \
            cp $staging/tls_lib.so $staging/weak_lib.so $lib_dir/ && \
            chmod a+rx $bin_dir/$name $lib_dir/tls_lib.so $lib_dir/weak_lib.so"
    else
        "$TAWC_EXEC" /system/bin/sh -c "\
            mkdir -p $bin_dir && \
            cp $staging/$name $bin_dir/$name && \
            chmod a+rx $bin_dir/$name"
    fi
}

# Apps that integration tests actually consume — keep this list in sync
# with `tests/integration/src/rootfs.rs::ensure_*`. `adreno-struct-varying`
# under `tests/apps/` is debug-only and intentionally not built here.
#
# `tawc-dri-test` and `libhybris-tls-repro` link against
# `-lhybris-common`, which only exists on aarch64 (libhybris isn't
# shipped for x86_64 — see notes/emulator.md). Skip them on the
# emulator; the integration tests that consume them already fail there
# (`tests/integration/tests/apps.rs:9`,
#  `tests/integration/tests/libhybris.rs`).
HOST_ARCH=$("$TAWC_EXEC" /system/bin/uname -m | tr -d '\r\n')
case "$HOST_ARCH" in
    aarch64) BUILD_ABI=aarch64 ;;
    x86_64)  BUILD_ABI=x86_64 ;;
    *) echo "ERROR: unsupported rootfs arch '$HOST_ARCH'" >&2; exit 1 ;;
esac
BUILD_DISTRO="${TAWC_SYSROOT_DISTRO:-$DISTRO_KEY}"

APPS=(gtk4-debug-app wayland-debug-app eglx11-test)
if [ "$HOST_ARCH" = "aarch64" ]; then
    APPS+=(tawc-dri-test libhybris-tls-repro)
else
    echo "=== Skipping tawc-dri-test, libhybris-tls-repro on $HOST_ARCH (need libhybris, aarch64-only) ==="
fi
echo "=== Cross-building test apps on host ($BUILD_DISTRO/$BUILD_ABI) ==="
"$ROOT_DIR/scripts/build-test-apps.sh" "--distro=$BUILD_DISTRO" "--abi=$BUILD_ABI"
for app in "${APPS[@]}"; do
    copy_test_app "$app"
done

echo "=== Done ==="
