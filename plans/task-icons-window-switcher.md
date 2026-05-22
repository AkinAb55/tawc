# Task Icons Window Switcher Plan

The base recents-card path is implemented. This plan tracks the remaining
window-index and switcher work.

## Launch Hints

When launching from `LauncherActivity`, register a short-lived launch hint
containing the selected desktop-entry id/name/icon. Assign that hint to the
first toplevel created by the launched process when app_id/class matching is
weak.

## Kotlin Window Registry

Add a small Kotlin-side registry owned near `CompositorService` /
`NativeBridge`. It is a mirror, not authority.

Suggested data:

```kotlin
data class OpenWindow(
    val activityId: String,
    val title: String,
    val appId: String,
    val desktopId: String,
    val iconPath: String,
    val focused: Boolean,
    val fullscreen: Boolean,
    val lastFocusedAtMillis: Long,
)
```

Expose it as a `StateFlow<List<OpenWindow>>` or equivalent observable. The
future window switcher can render this directly, including decoded/cached icons,
without asking Rust for a fresh snapshot.

Lifecycle:

- `spawnActivity` or first metadata update creates/updates a record.
- Metadata updates merge into the record.
- Focus updates mark the focused window and update `lastFocusedAtMillis`.
- `finishActivity` / `nativeOnActivityDestroyed` removes the record.
- If Kotlin misses an Activity instance but still has metadata, keep the record
  only if Rust still reports the host as alive.

Open question: whether the registry should live in `NativeBridge` or
`CompositorService`. Prefer service-owned state if the first consumer is a
switcher Activity.
