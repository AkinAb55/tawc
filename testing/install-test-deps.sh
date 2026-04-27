#!/bin/bash
# Install the chroot packages the integration test suite needs.
#
# Run once per chroot install. Idempotent (`pacman -S --needed`), so
# re-running is safe. The integration tests deliberately do NOT install
# anything at runtime — they assume these packages are present, so test
# runs aren't distro-specific and don't surprise you with package
# installs in the middle of a test.
#
# Prerequisites:
#   - Android device or emulator connected via adb (set TAWC_TARGET= if
#     multiple targets are connected)
#   - In-app Arch chroot installed at
#     /data/data/me.phie.tawc/installations/arch/ (install via the app's
#     Manage installations screen, or `am start --es autoAction install`)
#
# Usage:
#   bash testing/install-test-deps.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=../client/select-device.sh
source "$ROOT_DIR/client/select-device.sh"

# Keep grouped/commented so it's obvious what each package is for.
PKGS=(
    # gtk4-debug-app build (compiled in chroot by ensure_debug_app)
    gtk4 pkg-config
    # apps:: tests
    gtk3 gtk3-demos
    # graphics:: tests
    mesa-utils weston vulkan-tools
)

echo "=== Installing chroot test deps: ${PKGS[*]} ==="
"$ROOT_DIR/client/tawc-chroot-run" "pacman -S --noconfirm --needed ${PKGS[*]}"

echo "=== Done ==="
