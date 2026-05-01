#!/bin/bash
# Build tawc-dri-test inside the chroot.
# Requires: gcc (base-devel), libxcb (provided transitively by gtk3).
set -e
cd "$(dirname "$0")"
gcc -o tawc-dri-test tawc-dri-test.c \
    $(pkg-config --cflags --libs xcb) \
    -L/usr/local/lib -Wl,-rpath,/usr/local/lib -lhybris-common \
    -ldl -Wall -Wextra
echo "Built: tawc-dri-test"
