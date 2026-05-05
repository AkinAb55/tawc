#!/bin/bash
# Build the host-side tawc-exec helper.
#
# Small Rust binary that adb-forwards the device-side dev exec broker
# socket and multiplexes local stdio. See notes/exec-broker.md.
#
# Output: build/tawc-exec/tawc-exec (release build, stripped).
# Invoked automatically by scripts/lib/tawc-exec.sh on first use; you
# rarely need to run this by hand. `--clean` forces a fresh build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$ROOT_DIR/tools/tawc-exec"
OUT_DIR="$ROOT_DIR/build/tawc-exec"

if [ "${1:-}" = "--clean" ]; then
    rm -rf "$OUT_DIR" "$SRC_DIR/target"
fi

mkdir -p "$OUT_DIR"
# Use a separate target/ inside build/ so it isn't entangled with the
# compositor's target/ tree.
cargo build --release \
    --manifest-path "$SRC_DIR/Cargo.toml" \
    --target-dir "$OUT_DIR/target"

cp "$OUT_DIR/target/release/tawc-exec" "$OUT_DIR/tawc-exec"
echo "built: $OUT_DIR/tawc-exec"
