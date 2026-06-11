#!/bin/bash
# Ensure one or more pinned git deps from deps/deps.list exist locally.
#
# This is non-mutating for existing checkouts: it clones missing deps and
# verifies HEAD matches the manifest pin, but it does not reset dirty trees.
# Use scripts/update-deps.sh when a checkout must be moved to a new pin.
#
# Usage:
#   scripts/ensure-deps.sh smithay
#   scripts/ensure-deps.sh smithay rutabaga_gfx
#   scripts/ensure-deps.sh --patches rutabaga_gfx deps/rutabaga-patches/rutabaga_gfx
#   scripts/ensure-deps.sh --verify-all   # check every existing checkout, clone nothing
#
# Any dep_ensure also verifies every *existing* checkout against its pin
# (see scripts/lib/deps.sh), so naming one dep still catches drift in all.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"

if [ "$#" -eq 0 ]; then
    sed -n '2,/^set -/p' "$0" | sed 's/^# \?//;$d'
    exit 2
fi

while [ "$#" -gt 0 ]; do
    case "$1" in
        --patches)
            if [ "$#" -lt 3 ]; then
                echo "ERROR: --patches requires <dep> <patch-dir>" >&2
                exit 2
            fi
            dep="$2"
            patch_dir="$3"
            case "$patch_dir" in
                /*) ;;
                *) patch_dir="$DEPS_REPO_DIR/$patch_dir" ;;
            esac
            dep_apply_patches "$dep" "$patch_dir"
            shift 3
            ;;
        --verify-all)
            deps_verify_all
            shift
            ;;
        -*)
            echo "ERROR: unknown option '$1'" >&2
            exit 2
            ;;
        *)
            dep_ensure "$1"
            shift
            ;;
    esac
done
