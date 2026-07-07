# Dep-built artifacts can ship stale despite all-deps pin verification

Mostly fixed: every dep-artifact Gradle task now declares
`ensure-deps.sh --tree-state` (HEAD + tracked-edit hash per consumed
dep) as an input property, so discarded local edits and
deleted-while-drifted checkouts rebuild the artifact; `dep_reset` now
`git clean -fdx`s on an actual pin move. See notes/building.md
"Vendored repos". Remaining gaps, all minor:

1. **Untracked-file content is not fingerprinted.** A file *added*
   (not committed) to a dep tree feeds the artifact, but later content
   edits to it don't retrigger the Gradle task. Deliberate: untracked
   files survive `dep_reset` (no silent-discard hazard), and
   fingerprinting them would churn on in-tree build dirs. Iterating on
   one means running the build script directly — same contract as
   before.

2. **Tarball deps are never verified.** `talloc`/`libmd` extracts are
   reused as-is; hand edits or corruption are invisible (version bumps
   re-fetch by construction).

3. **Configuration-cache edge.** Building a termux-app-derived module
   alone (e.g. `:terminal-emulator:assembleDebug`) with a reused
   configuration cache skips both the settings-eval `dep_ensure
   termux-app` and `:app:verifyDeps`, so a drifted termux-app checkout
   compiles silently. Any `:app` build still catches it.
