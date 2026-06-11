# Dep-built artifacts can ship stale despite all-deps pin verification

The all-deps verify (deps.sh `deps_verify_all`, Gradle `verifyDeps`)
checks checkout HEADs against `deps/deps.list`. It does not make the
built *artifacts* track checkout *content*. Remaining gaps, worst first:

1. **Discarded local edits keep shipping.** Most dep-artifact tasks
   (`buildLibhybris`, `buildXwayland*`, `buildProot*`,
   `buildLibxkbcommon*`, `buildMesaGfxstream*`, `buildGfxstreamBackend*`)
   track only the script + `deps.list` + `deps.sh` as Gradle inputs —
   not the dep source tree (unlike smithay/rutabaga, which use
   fileTree inputs). `dep_ensure` tolerates dirty trees. So: edit
   `deps/libhybris`, build (artifact embodies the edits), later
   `update-deps.sh` discards the edits — same pin, no input change,
   output dir untouched — and every subsequent build keeps shipping the
   discarded edits until the script inputs change or `build/` is wiped.
   The reverse direction (edit dep, Gradle skips, APK ships pre-edit
   artifact) is the same hole, though that one is semi-intentional
   ("iteration is the script directly").
   Fix sketch: declare the dep trees as fileTree inputs
   (exclude `.git`/build dirs), or hash `git status --porcelain` +
   HEAD into an `inputs.property`.

2. **Deleting a checkout hides drift.** `deps_verify_all` skips missing
   checkouts (they're cloned on demand). Drift a dep, build its
   artifact, delete the checkout: verification passes and the stale
   artifact ships. Obscure, but silent.

3. **`dep_reset` doesn't `git clean`.** In-tree build dirs (e.g.
   `deps/libxkbcommon/builddir`) survive a pin move; the rebuild is
   incremental from the old configure state. ninja/meson usually get
   this right, but configure-time decisions (feature probes, versions)
   can go stale across large bumps. `dep_apply_patches` does clean on
   patch-hash change, so patched deps are mostly covered.

4. **Tarball deps are never verified.** `talloc`/`libmd` extracts are
   reused as-is; hand edits or corruption are invisible (version bumps
   re-fetch by construction).

5. **Configuration-cache edge.** Building a termux-app-derived module
   alone (e.g. `:terminal-emulator:assembleDebug`) with a reused
   configuration cache skips both the settings-eval `dep_ensure
   termux-app` and `:app:verifyDeps`, so a drifted termux-app checkout
   compiles silently. Any `:app` build still catches it.
