#!/bin/bash
# Bring every vendored dep listed in deps/deps.list into sync with the
# pinned commit. Use this after pulling a manifest update, or after a
# build errors with "dep <name> is at the wrong commit".
#
# This is the only command that mutates dep checkouts behind your back —
# build scripts verify the pin and refuse to silently update. To ratchet
# a dep forward: edit deps/deps.list, then run `scripts/update-deps.sh`
# on every checkout that needs to follow.
#
# Usage:
#   scripts/update-deps.sh              # reset every dep to its pin
#   scripts/update-deps.sh libhybris    # reset just one (or a few) by name
#
# `git reset --hard` discards uncommitted edits in the dep tree. The
# build helper (`dep_ensure`) silently tolerates dirty trees as long as
# HEAD matches the pin — so the only time you need this is when the pin
# itself moved. If you have local work-in-progress in a dep, stash or
# branch it first.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPS_REPO_DIR="$REPO_DIR"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"

declare -a names=()
if [ "$#" -eq 0 ]; then
    while IFS= read -r n; do names+=("$n"); done < <(deps_all_names)
else
    names=("$@")
fi

failed=0
for name in "${names[@]}"; do
    if ! dep_reset "$name"; then
        failed=$((failed + 1))
    fi
done

if [ "$failed" -ne 0 ]; then
    echo "ERROR: $failed dep(s) failed to update" >&2
    exit 1
fi

echo "==> all deps at pinned commits"
