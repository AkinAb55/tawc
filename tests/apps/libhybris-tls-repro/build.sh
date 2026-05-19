#!/bin/bash
# Manual in-rootfs build of the glibc-side repro binary. Normal
# integration flow uses scripts/build-test-apps.sh on the host.
#
# tls_lib.so (the bionic-built TLS library that repro loads) is
# cross-compiled with the Android NDK on the host by
# scripts/install-test-deps.sh and installed under /usr/local/lib/.
set -euo pipefail
cd "$(dirname "$0")"
gcc -O0 -g repro.c -o libhybris-tls-repro \
    -L/usr/lib/hybris -lhybris-common -Wl,-rpath,/usr/lib/hybris \
    -Wall -Wextra -pthread
echo "Built: libhybris-tls-repro"
