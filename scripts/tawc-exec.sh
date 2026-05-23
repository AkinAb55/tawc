#!/bin/bash
# Build the host-side exec helper if needed, then run it.
#
# Wrapper-only flag:
#   --clean   rebuild the cached helper and exit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BIN="$ROOT_DIR/build/tawc-exec/tawc-exec"
SRC="$ROOT_DIR/tests/integration"
OUT_DIR="$ROOT_DIR/build/tawc-exec"
DO_CLEAN=0

if [ "${1:-}" = "--clean" ]; then
    DO_CLEAN=1
    shift
    if [ "$#" -ne 0 ]; then
        echo "ERROR: --clean cannot be combined with tawc-exec args" >&2
        exit 2
    fi
fi

needs_build() {
    [ ! -x "$BIN" ] && return 0
    [ -n "$(find "$SRC/src" "$SRC/Cargo.toml" "$SRC/Cargo.lock" \
        -newer "$BIN" -print -quit 2>/dev/null)" ]
}

build_helper() {
    if [ "$DO_CLEAN" = "1" ]; then
        rm -rf "$OUT_DIR"
    fi
    mkdir -p "$OUT_DIR"
    cargo build --release \
        --manifest-path "$SRC/Cargo.toml" \
        --bin tawc-exec \
        --target-dir "$OUT_DIR/target"
    cp "$OUT_DIR/target/release/tawc-exec" "$BIN"
    echo "built: $BIN"
}

if [ "$DO_CLEAN" = "1" ]; then
    build_helper
    exit 0
elif needs_build; then
    build_helper >&2
fi

need_target=1
if [ $# -eq 0 ]; then
    need_target=0
else
    for arg in "$@"; do
        case "$arg" in
            -h|--help|help) need_target=0 ;;
        esac
    done
fi

case "$need_target" in
    0) ;;
    1)
        # shellcheck source=lib/select-device.sh
        . "$SCRIPT_DIR/lib/select-device.sh"
        ;;
esac

exec "$BIN" "$@"
