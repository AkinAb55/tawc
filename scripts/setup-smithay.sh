#!/bin/bash
# Ensure deps/smithay is cloned at the pinned commit.
#
# smithay is referenced by relative path from compositor/Cargo.toml
# (`[patch.crates-io] smithay = { path = "../deps/smithay" }`), so
# cargo refuses to build until the checkout exists. Gradle's
# `setupSmithay` task invokes this script ahead of `buildRustLibrary`;
# for standalone `cd compositor && cargo build` use, run this once
# yourself or via `bash scripts/update-deps.sh smithay`.
#
# Idempotent: dep_ensure clones if missing and verifies HEAD against
# the pin in deps/deps.list otherwise.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/deps.sh
source "$SCRIPT_DIR/lib/deps.sh"
dep_ensure smithay
