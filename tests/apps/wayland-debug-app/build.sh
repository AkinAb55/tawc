#!/bin/bash
# Manual in-rootfs build. Normal integration flow uses
# scripts/build-test-apps.sh on the host.
# Requires: gcc, wayland-client, wayland-protocols, wayland-scanner,
# cairo, pkg-config.
set -euo pipefail
cd "$(dirname "$0")"

XDG_PROTO="$(pkg-config --variable=pkgdatadir wayland-protocols)/stable/xdg-shell/xdg-shell.xml"
TEXT_PROTO="$(pkg-config --variable=pkgdatadir wayland-protocols)/unstable/text-input/text-input-unstable-v3.xml"

wayland-scanner client-header "$XDG_PROTO" xdg-shell-client-protocol.h
wayland-scanner private-code  "$XDG_PROTO" xdg-shell-protocol.c
wayland-scanner client-header "$TEXT_PROTO" text-input-unstable-v3-client-protocol.h
wayland-scanner private-code  "$TEXT_PROTO" text-input-unstable-v3-protocol.c

gcc -o wayland-debug-app \
    wayland-debug-app.c \
    xdg-shell-protocol.c \
    text-input-unstable-v3-protocol.c \
    $(pkg-config --cflags --libs wayland-client cairo) \
    -std=c11 -Wall -Wextra -Werror

echo "Built: wayland-debug-app"
