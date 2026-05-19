#!/bin/bash
# Build gtk4-debug-app inside the chroot.
# Requires: gcc, gtk4, pkg-config (installed by scripts/install-test-deps.sh)
set -euo pipefail
cd "$(dirname "$0")"
gcc -o gtk4-debug-app gtk4-debug-app.c $(pkg-config --cflags --libs gtk4) -Wall -Wextra
echo "Built: gtk4-debug-app"
