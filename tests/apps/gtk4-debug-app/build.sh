#!/bin/bash
# Build gtk4-debug-app inside the chroot.
# Requires: gcc (base-devel), gtk4
set -e
cd "$(dirname "$0")"
gcc -o gtk4-debug-app gtk4-debug-app.c $(pkg-config --cflags --libs gtk4) -Wall -Wextra
echo "Built: gtk4-debug-app"
