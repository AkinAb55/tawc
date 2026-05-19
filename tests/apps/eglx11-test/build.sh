#!/bin/bash
# Manual in-rootfs build. Normal integration flow uses
# scripts/build-test-apps.sh on the host.
# Requires: gcc, libX11, EGL/GLES headers.
set -euo pipefail
cd "$(dirname "$0")"
gcc -o eglx11-test eglx11-test.c \
    $(pkg-config --cflags --libs x11) \
    -lEGL -lGLESv2 \
    -L/usr/lib/hybris -Wl,-rpath,/usr/lib/hybris \
    -ldl -Wall -Wextra
echo "Built: eglx11-test"
