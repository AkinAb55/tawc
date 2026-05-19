#!/bin/bash
# Manual in-rootfs build. Normal integration flow uses
# scripts/build-test-apps.sh on the host.
# Requires: gcc, gtk4, pkg-config.
set -euo pipefail
cd "$(dirname "$0")"
gcc -o gtk4-debug-app gtk4-debug-app.c $(pkg-config --cflags --libs gtk4) -Wall -Wextra
echo "Built: gtk4-debug-app"
