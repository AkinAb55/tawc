#!/bin/bash
# Dev-time caching reverse proxy for distro mirrors. See
# notes/cache-proxy.md.
#
# Subcommands:
#   run                   Foreground the nginx proxy + adb-reverse poller.
#   list [<regex>]        Without args: per-host sizes. With a regex:
#                         matching URLs + sizes.
#   wipe <regex>          Delete cache entries whose URL matches <regex>.
#   help|--help|-h        Show usage.
#
# The cache itself is preserved across `run` invocations — nginx is
# idempotent against re-runs (a previous instance is killed via its
# pidfile before we bind 127.0.0.1:8080 ourselves).

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="$REPO_ROOT/build/cache-proxy"
CONFIG="$REPO_ROOT/scripts/cache-proxy.conf"

show_help() {
    cat <<EOF
Usage: $(basename "$0") <command> [args]

Commands:
  run                   Start the caching reverse proxy (foreground; Ctrl-C to stop).
  list                  List unique hostnames in the cache with sizes + total.
  list <regex>          List cached URLs matching <regex> (ERE) with sizes + total.
  wipe <regex>          Delete cache entries whose URL matches <regex> (ERE).
  help, --help, -h      Show this help.

Cache lives at: $PREFIX/cache
EOF
}

# Print TSV "<bytes>\t<url>\t<path>" for every cached entry. URL comes
# from the nginx cache file's KEY: header line.
collect_urls() {
    [ -d "$PREFIX/cache" ] || return 0

    declare -A SIZE
    local p s
    while IFS=$'\t' read -r p s; do
        SIZE[$p]=$s
    done < <(find "$PREFIX/cache" -maxdepth 1 -type f -printf '%p\t%s\n')

    [ "${#SIZE[@]}" -gt 0 ] || return 0

    local line path url
    while IFS= read -r line; do
        path=${line%%:KEY: *}
        url=${line#*:KEY: }
        [ "$path" = "$line" ] && continue
        printf '%s\t%s\t%s\n' "${SIZE[$path]:-0}" "$url" "$path"
    done < <(printf '%s\0' "${!SIZE[@]}" \
        | xargs -0 -r grep -aom1 -H 'KEY: [^[:cntrl:]]*' 2>/dev/null)
}

# Extract hostname from a URL (keeps :port if present).
host_of() {
    local u=$1
    u=${u#http://}
    u=${u#https://}
    u=${u%%/*}
    printf '%s' "$u"
}

human() { numfmt --to=iec -- "$1"; }

list_hosts() {
    declare -A HSIZE HCOUNT
    local size url path host total=0 entries=0
    while IFS=$'\t' read -r size url path; do
        host=$(host_of "$url")
        HSIZE[$host]=$(( ${HSIZE[$host]:-0} + size ))
        HCOUNT[$host]=$(( ${HCOUNT[$host]:-0} + 1 ))
        total=$(( total + size ))
        entries=$(( entries + 1 ))
    done < <(collect_urls)

    if [ $entries -eq 0 ]; then
        echo "cache is empty"
        return 0
    fi

    local h
    for h in "${!HSIZE[@]}"; do
        printf '%s\t%s\t%s\n' "${HSIZE[$h]}" "${HCOUNT[$h]}" "$h"
    done | sort -rn | while IFS=$'\t' read -r size count host; do
        printf '%8s  %5d  %s\n' "$(human "$size")" "$count" "$host"
    done

    printf -- '----\n%8s  %5d  total (%d hosts)\n' \
        "$(human "$total")" "$entries" "${#HSIZE[@]}"
}

list_matching() {
    local regex=$1
    local size url path total=0 count=0
    while IFS=$'\t' read -r size url path; do
        if [[ $url =~ $regex ]]; then
            printf '%8s  %s\n' "$(human "$size")" "$url"
            total=$(( total + size ))
            count=$(( count + 1 ))
        fi
    done < <(collect_urls)

    if [ $count -eq 0 ]; then
        echo "no matches"
        return 0
    fi
    printf -- '----\n%8s  %d entries\n' "$(human "$total")" "$count"
}

wipe_cmd() {
    local regex=$1
    local size url path total=0 count=0
    local -a paths=()
    while IFS=$'\t' read -r size url path; do
        if [[ $url =~ $regex ]]; then
            printf '%8s  %s\n' "$(human "$size")" "$url"
            paths+=("$path")
            total=$(( total + size ))
            count=$(( count + 1 ))
        fi
    done < <(collect_urls)

    if [ $count -eq 0 ]; then
        echo "no matches" >&2
        exit 1
    fi

    rm -f -- "${paths[@]}"
    printf -- '----\nwiped %d entries (%s)\n' "$count" "$(human "$total")"
}

run_proxy() {
    mkdir -p "$PREFIX"/{cache,logs,tmp}

    # If a previous instance is still around (orphaned shell, ^Z'd parent),
    # clean it up before binding 127.0.0.1:8080 ourselves.
    if [ -f "$PREFIX/logs/nginx.pid" ]; then
        local old_pid
        old_pid=$(cat "$PREFIX/logs/nginx.pid" 2>/dev/null || true)
        if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
            echo "==> killing stale nginx pid=$old_pid"
            kill "$old_pid" 2>/dev/null || true
            for _ in 1 2 3 4 5; do
                kill -0 "$old_pid" 2>/dev/null || break
                sleep 0.2
            done
        fi
        rm -f "$PREFIX/logs/nginx.pid"
    fi

    ADB="${ADB:-adb}"
    command -v "$ADB" >/dev/null || { echo "ERROR: adb not on PATH" >&2; exit 1; }
    command -v nginx >/dev/null || { echo "ERROR: nginx not installed" >&2; exit 1; }

    echo "==> starting nginx (prefix=$PREFIX, config=$CONFIG)"
    echo "    proxy URL: http://127.0.0.1:8080/proxy/<scheme>/<host>/<path>"
    echo "    cache:     $PREFIX/cache"
    echo "    logs:      $PREFIX/logs"

    # Bring nginx up in the foreground so the trap kills it on Ctrl-C / parent
    # exit. Background it ourselves so the adb-reverse loop can run alongside.
    nginx -p "$PREFIX/" -c "$CONFIG" -g 'daemon off;' &
    NGINX_PID=$!

    trap 'kill $NGINX_PID 2>/dev/null; wait 2>/dev/null; exit 0' EXIT INT TERM

    # Wait for nginx to bind before announcing readiness.
    for _ in $(seq 1 20); do
        if ss -ltn 2>/dev/null | grep -q '127\.0\.0\.1:8080\b' \
           || netstat -ltn 2>/dev/null | grep -q '127\.0\.0\.1:8080\b'; then
            break
        fi
        kill -0 "$NGINX_PID" 2>/dev/null || { echo "ERROR: nginx died — see $PREFIX/logs/error.log" >&2; exit 1; }
        sleep 0.1
    done

    # adb reverse is idempotent (re-applying when already in place is a
    # no-op), so we just re-apply once per second. Cheap, and cuts out the
    # version-skew minefield around `adb track-devices` — modern adb
    # (platform-tools 35+) emits binary protobuf by default that's not
    # parseable in shell. Polling tracks new connects, reboots, AVD
    # (re)starts, and unplug→replug cycles uniformly.
    #
    # We log a one-liner only on **state transitions** (first time a device
    # appears, or when it disappears) so the terminal isn't spammed with
    # the same line every second.
    echo "==> watching for devices (poll interval=1s)"
    (
        declare -A seen=()
        while sleep 1; do
            cur=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
            for serial in $cur; do
                "$ADB" -s "$serial" reverse tcp:8080 tcp:8080 >/dev/null 2>&1 || {
                    echo "WARN: adb reverse failed for $serial" >&2
                    continue
                }
                if [ -z "${seen[$serial]:-}" ]; then
                    echo "==> adb reverse tcp:8080 -> $serial"
                    seen[$serial]=1
                fi
            done
            for serial in "${!seen[@]}"; do
                if ! echo "$cur" | grep -qx "$serial"; then
                    echo "==> $serial disconnected"
                    unset 'seen[$serial]'
                fi
            done
        done
    ) &
    POLL_PID=$!
    trap 'kill $NGINX_PID $POLL_PID 2>/dev/null; wait 2>/dev/null; exit 0' EXIT INT TERM

    echo "==> ready (Ctrl-C to stop)"
    wait "$NGINX_PID"
}

cmd=${1:-}
case "$cmd" in
    ''|help|--help|-h)
        show_help
        ;;
    run)
        [ $# -eq 1 ] || { echo "ERROR: run takes no arguments" >&2; exit 1; }
        run_proxy
        ;;
    list)
        shift
        if [ $# -eq 0 ]; then
            list_hosts
        elif [ $# -eq 1 ]; then
            list_matching "$1"
        else
            echo "ERROR: list takes at most one regex argument" >&2
            exit 1
        fi
        ;;
    wipe)
        shift
        [ $# -eq 1 ] || { echo "ERROR: wipe requires exactly one regex argument" >&2; exit 1; }
        wipe_cmd "$1"
        ;;
    *)
        echo "ERROR: unknown command: $cmd" >&2
        echo >&2
        show_help >&2
        exit 1
        ;;
esac
