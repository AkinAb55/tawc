# Shared dep-fetch + verify helper. Sourced by every build script that
# vendors a third-party repo. Single source of truth for pins lives in
# `deps/deps.list`. See AGENTS.md "Vendored deps" for the policy.
#
# Public API (after sourcing):
#   dep_dir <name>     -- echo absolute checkout path for <name>
#   dep_ensure <name>  -- clone if missing, verify HEAD == pinned commit.
#                         Errors on commit mismatch (uncommitted edits OK).
#   dep_apply_patches <name> <patch-dir> [sed-expr]
#                      -- ensure <name>, reset/clean it if the patch hash
#                         changed, then apply *.patch from <patch-dir>.
#                         Optional sed expression transforms patch contents
#                         before hashing/applying.
#   dep_reset <name>   -- fetch + `git reset --hard <commit>`. Wipes any
#                         per-dep `.tawc-patches-applied-*` sentinel so
#                         the build's apply_patches stage re-runs. Used
#                         exclusively by `scripts/update-deps.sh`.
#   deps_all_names     -- echo every dep name, one per line, in manifest order.
#
# Concurrency: callers serialise clone/checkout via flock on
# `$DEPS_REPO_DIR/build/.deps.lock` so two parallel `dep_ensure` calls
# (e.g. Gradle's per-ABI tasks running in parallel) can't race a clone.
#
# Sourcing this file requires DEPS_REPO_DIR to point at the repo root, OR
# leaves it derived from the location of this script.

# shellcheck shell=bash

if [ -z "${DEPS_REPO_DIR:-}" ]; then
    # scripts/lib/deps.sh -> ../..  is the repo root.
    DEPS_REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
DEPS_MANIFEST="$DEPS_REPO_DIR/deps/deps.list"
DEPS_LOCKFILE="$DEPS_REPO_DIR/build/.deps.lock"

# Walk deps.list emitting (name, repo, commit, ref, dest) tab-separated.
# Skips comments and blank lines. We only have ~30 lines; a pure-bash
# loop is fine.
_deps_emit() {
    local name repo commit ref dest
    while IFS=$'\t' read -r name repo commit ref dest; do
        case "$name" in ''|'#'*) continue ;; esac
        # Defensive: trim a stray CR from windows-edited files.
        name="${name%$'\r'}"; repo="${repo%$'\r'}"
        commit="${commit%$'\r'}"; ref="${ref%$'\r'}"; dest="${dest%$'\r'}"
        printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$repo" "$commit" "$ref" "$dest"
    done <"$DEPS_MANIFEST"
}

deps_all_names() {
    _deps_emit | cut -f1
}

# Look up <name> and populate DEP_NAME/DEP_REPO/DEP_COMMIT/DEP_REF/DEP_DEST.
# DEP_DEST is absolute. Returns non-zero if the name isn't in the manifest.
_dep_lookup() {
    local want="$1" name repo commit ref dest
    while IFS=$'\t' read -r name repo commit ref dest; do
        if [ "$name" = "$want" ]; then
            DEP_NAME="$name"
            DEP_REPO="$repo"
            DEP_COMMIT="$commit"
            DEP_REF="$ref"
            DEP_DEST="$DEPS_REPO_DIR/$dest"
            return 0
        fi
    done < <(_deps_emit)
    echo "ERROR: dep '$want' not in $DEPS_MANIFEST" >&2
    return 1
}

dep_dir() {
    _dep_lookup "$1" || return 1
    printf '%s\n' "$DEP_DEST"
}

# True iff $DEP_COMMIT exists locally as a reachable commit.
_dep_have_commit() {
    git -C "$DEP_DEST" rev-parse --verify --quiet "$DEP_COMMIT^{commit}" >/dev/null
}

# Fetch from origin until $DEP_COMMIT is present, or give up. Tries
# (cheapest first): the ref-hint, the commit by name, unshallow,
# fetch-everything.
_dep_fetch_commit() {
    _dep_have_commit && return 0
    if [ "$DEP_REF" != "-" ] && [ -n "$DEP_REF" ]; then
        git -C "$DEP_DEST" fetch --quiet origin "$DEP_REF" 2>/dev/null || true
        _dep_have_commit && return 0
    fi
    # Some servers (gitlab, github) accept fetch-by-sha when uploadpack.
    # allowReachableSHA1InWant or allowAnySHA1InWant is set.
    git -C "$DEP_DEST" fetch --quiet origin "$DEP_COMMIT" 2>/dev/null || true
    _dep_have_commit && return 0
    if [ -f "$DEP_DEST/.git/shallow" ]; then
        git -C "$DEP_DEST" fetch --quiet --unshallow origin 2>/dev/null || true
        _dep_have_commit && return 0
    fi
    git -C "$DEP_DEST" fetch --quiet origin 2>/dev/null || true
    _dep_have_commit
}

# Fresh clone of $DEP_REPO into $DEP_DEST. Uses --branch when we have a
# ref hint (cheaper for tags / single branches); otherwise full clone.
# A pre-existing non-git $DEP_DEST (e.g. left behind by Gradle's
# outputs.dir declaration) is wiped — git refuses non-empty targets.
_dep_clone() {
    [ -e "$DEP_DEST" ] && [ ! -d "$DEP_DEST/.git" ] && rm -rf "$DEP_DEST"
    mkdir -p "$(dirname "$DEP_DEST")"
    if [ "$DEP_REF" != "-" ] && [ -n "$DEP_REF" ]; then
        echo "==> cloning $DEP_NAME @ $DEP_REF -> ${DEP_DEST#$DEPS_REPO_DIR/}"
        # `--branch` accepts both branches and tags. We don't pass
        # --depth: the commit pin may not be at the ref tip, and a
        # shallow clone forces an --unshallow round-trip later. The
        # repos in deps.list are small enough (~tens of MB at most)
        # that a full clone is faster end-to-end than depth-juggling.
        git clone --quiet --branch "$DEP_REF" "$DEP_REPO" "$DEP_DEST"
    else
        echo "==> cloning $DEP_NAME -> ${DEP_DEST#$DEPS_REPO_DIR/}"
        git clone --quiet "$DEP_REPO" "$DEP_DEST"
    fi
}

# After cloning or resetting, check the resolved tip matches the pin. If
# the user has uncommitted edits on top, we don't care — only the parent
# commit matters.
_dep_verify_head() {
    local actual
    actual="$(git -C "$DEP_DEST" rev-parse HEAD)"
    if [ "$actual" != "$DEP_COMMIT" ]; then
        cat >&2 <<EOF
ERROR: dep '$DEP_NAME' is at the wrong commit.
       expected: $DEP_COMMIT  (from deps/deps.list)
       got:      $actual      (HEAD of ${DEP_DEST#$DEPS_REPO_DIR/})
        Run \`scripts/update-deps.sh $DEP_NAME\` to align, OR — if you
       deliberately moved the dep — bump the commit in deps/deps.list
       to $actual and re-run.
EOF
        return 1
    fi
}

# Run a function while holding an exclusive flock over $DEPS_LOCKFILE so
# parallel build-script invocations (e.g. Gradle's per-ABI tasks) can't
# race a clone or checkout. The lock is reentrant within the same shell
# (FD 9 already open) so `dep_reset` calling `dep_reset` doesn't deadlock.
_dep_with_lock() {
    if [ "${_DEP_LOCK_HELD:-}" = "1" ]; then
        "$@"
        return
    fi
    mkdir -p "$(dirname "$DEPS_LOCKFILE")"
    : >>"$DEPS_LOCKFILE"
    (
        flock 9
        _DEP_LOCK_HELD=1 "$@"
    ) 9>"$DEPS_LOCKFILE"
}

_dep_ensure_inner() {
    if [ ! -d "$DEP_DEST/.git" ]; then
        _dep_clone || return 1
        if ! _dep_have_commit; then
            _dep_fetch_commit || {
                echo "ERROR: dep '$DEP_NAME': commit $DEP_COMMIT unreachable from origin." >&2
                return 1
            }
        fi
        # Detached checkout — we don't track upstream branches.
        git -C "$DEP_DEST" -c advice.detachedHead=false checkout --quiet "$DEP_COMMIT"
    fi
    _dep_verify_head
}

dep_ensure() {
    _dep_lookup "$1" || return 1
    _dep_with_lock _dep_ensure_inner
}

_dep_apply_patches_inner() {
    local patch_dir="$1" sed_expr="${2:-}"
    _dep_ensure_inner || return 1
    shopt -s nullglob
    local patch_files=("$patch_dir"/*.patch)
    shopt -u nullglob
    [ "${#patch_files[@]}" -gt 0 ] || return 0

    local hash sentinel
    if [ -n "$sed_expr" ]; then
        hash="$(sed "$sed_expr" "${patch_files[@]}" | sha1sum | cut -c1-12)"
    else
        hash="$(cat "${patch_files[@]}" | sha1sum | cut -c1-12)"
    fi
    sentinel="$DEP_DEST/.tawc-patches-applied-$hash"
    [ -f "$sentinel" ] && return 0

    rm -f "$DEP_DEST"/.tawc-patches-applied-*
    git -C "$DEP_DEST" reset --hard --quiet HEAD
    git -C "$DEP_DEST" clean -fdx --quiet
    local p
    for p in "${patch_files[@]}"; do
        echo "==> patch $DEP_NAME: $(basename "$p")"
        if [ -n "$sed_expr" ]; then
            sed "$sed_expr" "$p" | ( cd "$DEP_DEST" && patch -p1 --no-backup-if-mismatch )
        else
            ( cd "$DEP_DEST" && patch -p1 --no-backup-if-mismatch < "$p" >/dev/null )
        fi
    done
    touch "$sentinel"
}

dep_apply_patches() {
    _dep_lookup "$1" || return 1
    _dep_with_lock _dep_apply_patches_inner "$2" "${3:-}"
}

_dep_reset_inner() {
    if [ ! -d "$DEP_DEST/.git" ]; then
        _dep_clone || return 1
    fi
    if ! _dep_have_commit; then
        _dep_fetch_commit || {
            echo "ERROR: dep '$DEP_NAME': commit $DEP_COMMIT unreachable from origin." >&2
            return 1
        }
    fi
    local before
    before="$(git -C "$DEP_DEST" rev-parse HEAD 2>/dev/null || echo none)"
    if [ "$before" != "$DEP_COMMIT" ]; then
        echo "==> reset $DEP_NAME ${before:0:12} -> ${DEP_COMMIT:0:12}"
    fi
    git -C "$DEP_DEST" -c advice.detachedHead=false reset --quiet --hard "$DEP_COMMIT"
    # Patch sentinels (`.tawc-patches-applied-<sha>`) live in the working
    # tree as untracked files, so `reset --hard` doesn't remove them.
    # Drop any so the build's apply_patches stage re-runs.
    rm -f "$DEP_DEST"/.tawc-patches-applied*
    _dep_verify_head
}

dep_reset() {
    _dep_lookup "$1" || return 1
    _dep_with_lock _dep_reset_inner
}
