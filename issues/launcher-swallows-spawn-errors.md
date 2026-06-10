# Launcher silently swallows rootfs spawn failures

`LauncherActivity.launchEntry` fires the app launch on a detached scope
and maps any failure to a `Log.w`:

```kotlin
runCatching { UserRootfsSession.runInside(app, method, rootfs, cmd) }
    .onFailure { android.util.Log.w(TAG, "launch ${entry.id}: $it") }
```

The user taps an icon and nothing visibly happens. This predates
external binds, but binds make it user-reachable: a revoked all-files
grant or missing bind host dir now makes `startInside` fail closed with
an actionable `IOException` (notes/external-binds.md), which the
terminal (toast + finish), RunCommandOp, and the broker all surface —
the launcher is the one spawn surface that doesn't.

The activity `finish()`es before the failure arrives, so a toast from
the application context (or a notification for late failures) is
probably the right shape.
