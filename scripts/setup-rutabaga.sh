#!/bin/bash
# Ensure deps/rutabaga_gfx is cloned at the pinned commit and our
# patches are applied. The compositor's `kumquat_virtio` Cargo dep
# resolves to `deps/rutabaga_gfx/kumquat/server`, so cargo refuses to
# build until both the checkout exists and the
# `03-kumquat-server-as-lib.patch` (which adds the `[lib]` target the
# compositor links against) has been applied.
#
# Mirrors setup-smithay.sh in shape, but with patch application —
# rutabaga is the only Rust dep we patch. Gradle invokes this from
# its `setupRutabaga` task ahead of `buildRustLibrary<Abi>`; for
# standalone `cd compositor && cargo build` use, run this once
# yourself (idempotent — sentinel-gated like the gfxstream + mesa
# patch flows).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"

dep_ensure rutabaga_gfx
RUTABAGA_DIR="$(dep_dir rutabaga_gfx)"
PATCH_DIR="$REPO_DIR/deps/rutabaga-patches/rutabaga_gfx"

shopt -s nullglob
PATCH_FILES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
if [ "${#PATCH_FILES[@]}" -eq 0 ]; then
    echo "==> no patches in $PATCH_DIR (rutabaga left untouched)"
    exit 0
fi

PATCH_HASH="$(cat "${PATCH_FILES[@]}" | sha1sum | cut -c1-12)"
PATCH_SENTINEL="$RUTABAGA_DIR/.tawc-patches-applied-$PATCH_HASH"
if [ -f "$PATCH_SENTINEL" ]; then
    exit 0
fi

rm -f "$RUTABAGA_DIR"/.tawc-patches-applied-*
git -C "$RUTABAGA_DIR" reset --hard --quiet HEAD
git -C "$RUTABAGA_DIR" clean -fdx --quiet
for p in "${PATCH_FILES[@]}"; do
    echo "==> patch rutabaga: $(basename "$p")"
    ( cd "$RUTABAGA_DIR" && patch -p1 --no-backup-if-mismatch < "$p" >/dev/null )
done
touch "$PATCH_SENTINEL"
