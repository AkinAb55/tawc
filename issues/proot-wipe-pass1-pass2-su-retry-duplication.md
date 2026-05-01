# `ProotMethod.wipe`'s two passes each open-code their own su-retry block

After the cancel-uninstall split (one wipe, two `find -xdev -depth -delete`
passes — rootfs subtree first, then `metadata.json` + container) landed in
`ProotMethod.wipe`, both passes now duplicate the same pattern:

1. Run the delete via `runShell` as the app uid.
2. If it fails (or the target still exists), retry once via `Su.run`.
3. If even the su-retry fails, throw `IOException`.

Pass 1 is at `ProotMethod.kt` ~`287-307` (rootfs subtree); pass 2 is at
`ProotMethod.kt` ~`310-326` (container + metadata). The two blocks share
the "try app-uid, retry via su, throw on residual" shape but are
written out twice with slightly different log strings and slightly
different shell scripts.

The chroot-side `RootfsCleaner.wipe` doesn't have this duplication —
it has a single su-only delete path, so each pass is one `Su.run` call
with one `IOException` throw on failure.

## The action

Refactor the proot-side wipe into a small helper, e.g.:

```kotlin
private fun deleteWithSuRetry(
    cmd: String,           // shell command (string, not argv) e.g. "find … -delete" or "rmdir …; rm …"
    label: String,         // log prefix
    expectGone: () -> Boolean, // post-condition (`!File(rootfsPath).exists()` or `!installDir.exists()`)
    log: (String) -> Unit,
)
```

and call it once per pass. Cuts ~25 lines of near-duplicate code and
makes it harder for future edits to drift the two passes apart (e.g.
fixing the log format in one and forgetting the other).

This is a **pure refactor** — no behaviour change. The pre-existing
"is the su-retry path even still needed?" question is tracked
separately in [`proot-wipe-su-retry-legacy.md`](proot-wipe-su-retry-legacy.md);
that issue is about whether to keep the su-retry at all, this one
is about the shape of the code if we keep it.

## Why we didn't fix it inline

Surfaced during a code review of the cancel-uninstall work.
Refactoring beyond the cancel-uninstall scope wasn't justified for
that change, but it's worth doing on its own so a future edit
(e.g. the legacy-su-retry decision in the linked issue) starts from
a clean shape.

## References

- `server/app/src/main/java/me/phie/tawc/install/ProotMethod.kt` — `wipe()` body, both passes
- `server/app/src/main/java/me/phie/tawc/install/RootfsCleaner.kt` — chroot-side reference (no duplication to mimic)
- [`issues/proot-wipe-su-retry-legacy.md`](proot-wipe-su-retry-legacy.md) — separate question of whether the su-retry should exist at all
