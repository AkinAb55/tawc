#!/bin/bash
# Manual in-rootfs build. Normal integration flow uses
# scripts/build-test-apps.sh on the host.
# Requires: gcc, libxcb.
set -euo pipefail
cd "$(dirname "$0")"
gcc -o tawc-dri-test tawc-dri-test.c \
    $(pkg-config --cflags --libs xcb) \
    -L/usr/lib/hybris -Wl,-rpath,/usr/lib/hybris -lhybris-common \
    -ldl -Wall -Wextra
echo "Built: tawc-dri-test"
