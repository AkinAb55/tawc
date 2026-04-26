#!/bin/bash
# Source from host scripts to pick which adb device to talk to.
# Sets ANDROID_SERIAL (which adb honors natively).
#
# Selection rules:
#   TAWC_TARGET=device   -> first non-emulator
#   TAWC_TARGET=emulator -> first emulator-*
#   unset                -> read ./.tawctarget at the project root if present;
#                           otherwise if exactly one device, use it; if multiple, error
#
# ANDROID_SERIAL already set by the caller wins -- we don't override.

if [ -n "${ANDROID_SERIAL:-}" ]; then
    return 0 2>/dev/null || exit 0
fi

if [ -z "${TAWC_TARGET:-}" ]; then
    _script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    _tawctarget_file="$(dirname "$_script_dir")/.tawctarget"
    if [ -f "$_tawctarget_file" ]; then
        TAWC_TARGET=$(head -n1 "$_tawctarget_file" | tr -d '[:space:]')
    fi
fi

_devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
_emulators=$(echo "$_devices" | grep '^emulator-' || true)
_real=$(echo "$_devices" | grep -v '^emulator-' | grep -v '^$' || true)

case "${TAWC_TARGET:-}" in
    emulator)
        if [ -z "$_emulators" ]; then
            echo "ERROR: TAWC_TARGET=emulator but no emulator is connected" >&2
            echo "       see notes/emulator.md to start one" >&2
            return 1 2>/dev/null || exit 1
        fi
        export ANDROID_SERIAL=$(echo "$_emulators" | head -1)
        ;;
    device)
        if [ -z "$_real" ]; then
            echo "ERROR: TAWC_TARGET=device but no real device is connected" >&2
            return 1 2>/dev/null || exit 1
        fi
        export ANDROID_SERIAL=$(echo "$_real" | head -1)
        ;;
    "")
        _count=$(echo "$_devices" | grep -c -v '^$' || true)
        if [ "$_count" = "0" ]; then
            echo "ERROR: no adb devices connected" >&2
            return 1 2>/dev/null || exit 1
        elif [ "$_count" = "1" ]; then
            export ANDROID_SERIAL="$_devices"
        else
            echo "ERROR: multiple adb targets connected, set TAWC_TARGET=device or TAWC_TARGET=emulator" >&2
            echo "       (or set ANDROID_SERIAL=<serial> directly)" >&2
            adb devices >&2
            return 1 2>/dev/null || exit 1
        fi
        ;;
    *)
        echo "ERROR: unknown TAWC_TARGET=$TAWC_TARGET (expected 'device' or 'emulator')" >&2
        return 1 2>/dev/null || exit 1
        ;;
esac

if [ -n "${TAWC_VERBOSE_TARGET:-}" ]; then
    echo "==> using adb target: $ANDROID_SERIAL" >&2
fi
