# Source from a host script to get $TAWC_EXEC_BIN, an absolute path to
# the host-side helper that drives the in-app dev exec broker. Auto-
# builds the binary on first use.
#
# Caller must already have ANDROID_SERIAL set (source
# scripts/lib/select-device.sh first).
#
# Usage:
#   source scripts/lib/tawc-exec.sh
#   "$TAWC_EXEC_BIN" /system/bin/echo hello
#   "$TAWC_EXEC_BIN" --cwd /data/local/tmp /system/bin/sh -c 'pwd'
#   exec "$TAWC_EXEC_BIN" --env PATH=/usr/bin /system/bin/sh ...   # works
#
# We expose only the env var, not a shell function: shell functions
# can't be `exec`'d, and several callers want exec semantics so the
# host-side process tree stays flat.
#
# The broker runs commands in the app's `untrusted_app` SELinux domain
# — same domain as user-launched runs. No `run-as`, no `su`. Children
# of the broker get SIGKILLed cleanly when the host disconnects (the
# broker walks /proc to reap descendants too). See notes/exec-broker.md.

# Resolve repo root from this lib's own location; callers' $SCRIPT_DIR
# convention varies.
_TAWC_EXEC_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_TAWC_EXEC_REPO_ROOT="$(cd "$_TAWC_EXEC_LIB_DIR/../.." && pwd)"
TAWC_EXEC_BIN="$_TAWC_EXEC_REPO_ROOT/build/tawc-exec/tawc-exec"

# Build on first use. Idempotent — cargo no-ops when nothing changed.
if [ ! -x "$TAWC_EXEC_BIN" ]; then
    bash "$_TAWC_EXEC_REPO_ROOT/scripts/build-tawc-exec.sh" >&2
fi
