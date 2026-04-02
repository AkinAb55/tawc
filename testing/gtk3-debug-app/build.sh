#!/bin/bash
# Build gtk3-debug-app inside the chroot.
# Requires: gcc (base-devel), gtk3
set -e
cd "$(dirname "$0")"
gcc -o gtk3-debug-app gtk3-debug-app.c $(pkg-config --cflags --libs gtk+-3.0) -Wall -Wextra
echo "Built: gtk3-debug-app"
