# Task Icons And Window Index

Status: design note. Not implemented yet.

## Goal

Make each Linux toplevel feel like a native Android task:

- the recents card label follows the Wayland title/app metadata;
- the recents card icon uses the app icon from inside the rootfs when possible;
- Kotlin keeps a live index of open windows so a future in-app window switcher
  can be built without re-discovering compositor state.

This builds on the existing multi-Activity model: each Wayland toplevel already
maps to one `CompositorActivity` task. Rust remains the source of truth for
Wayland state and host assignment. Kotlin mirrors the Android-facing metadata.

## Android API Choice

Do not target Android 17 / API 37 for this feature.

As of May 2026, API 37 is Android 17 beta-era API surface. TAWC currently
builds with `compileSdk = 36`, `targetSdk = 36`, and `minSdk = 29`. The main
path should use APIs available on API 29-36.

Use `Activity.setTaskDescription(...)` with the older
`ActivityManager.TaskDescription(label, bitmap)` path for dynamic recents icons.
It is deprecated in newer SDKs, but it is the compatible API for TAWC's current
SDK range. Add a newer `TaskDescription.Builder` branch later only if it gives
real value after TAWC moves to a newer compile SDK.

## Metadata Flow

Preferred flow:

```text
Wayland / Xwayland metadata
  -> Rust host/toplevel policy
  -> reverse-JNI metadata update
  -> Kotlin WindowRegistry
  -> CompositorActivity.setTaskDescription(...)
  -> future WindowSwitcher UI
```

Rust should send updates whenever any of these change:

- `activityId`
- window title
- Wayland `app_id` or Xwayland class
- resolved desktop-entry id
- resolved icon path
- focus/fullscreen state if useful for switcher UI

The update should be repeatable. Do not rely on setting metadata only during
`spawnActivity`: Wayland clients often set title/app_id after the Android
Activity already exists, and titles can change during normal use.

## Desktop Entry And Icon Matching

Reuse and generalize the existing launcher scanner in `compositor/src/launcher.rs`.
It already parses `.desktop` files and resolves `Icon=` to a PNG path inside the
rootfs for Kotlin to decode.

First-pass matching:

- match Wayland `app_id` against `.desktop` ids and common variants;
- match Xwayland `WM_CLASS` / class against desktop ids where available;
- fall back to title-only metadata and TAWC's default icon.

Later improvement:

- when launching from `LauncherActivity`, register a short-lived launch hint
  containing the selected desktop-entry id/name/icon;
- assign that hint to the first toplevel created by the launched process when
  app_id/class matching is weak.

SVG-only icons can stay out of scope for this feature. The launcher already
returns only PNG paths because Android `BitmapFactory` cannot decode SVG/XPM
natively.

## Reverse-JNI Shape

Keep explicit reverse-JNI methods. Do not replace them with a generic opcode or
JSON command dispatcher.

Good shape:

- Rust has one shared helper that attaches to the JVM, gets `NativeBridge`, and
  runs a closure;
- each semantic operation remains a small typed function:
  `spawnActivity`, `finishActivity`, `setActivityFullscreen`,
  `setTaskDescription` / `updateWindowMetadata`, etc.;
- Kotlin keeps explicit methods with normal argument types.

This keeps the boundary greppable and lets signature errors fail at the JNI call
site instead of inside ad hoc parsing.

Rust-side helper sketch:

```rust
fn with_native_bridge(
    context: &str,
    f: impl FnOnce(&mut JNIEnv, JClass) -> jni::errors::Result<()>,
) {
    // Get cached JavaVM and NativeBridge class.
    // Attach current thread.
    // Reinterpret cached NativeBridge GlobalRef as JClass.
    // Run f and log context on error.
}
```

Kotlin-side helper sketch:

```kotlin
private fun postForActivity(activityId: String, op: (CompositorActivity) -> Unit) {
    mainHandler.post {
        val activity = serviceRef?.get()?.getActivity(activityId) ?: return@post
        op(activity)
    }
}
```

`finishActivity`, `setActivityFullscreen`, and task-description updates can all
use this lookup pattern.

## Kotlin Window Registry

Add a small Kotlin-side registry owned near `CompositorService` / `NativeBridge`.
It is a mirror, not authority.

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

- `spawnActivity` or first metadata update creates/updates a record;
- metadata updates merge into the record;
- focus updates mark the focused window and update `lastFocusedAtMillis`;
- `finishActivity` / `nativeOnActivityDestroyed` removes the record;
- if Kotlin misses an Activity instance but still has metadata, keep the record
  only if Rust still reports the host as alive.

## Open Questions

- Whether task-description update should take `iconPath` and decode on Kotlin,
  or take pre-scaled PNG/bitmap bytes from Rust. Prefer `iconPath` first because
  Kotlin already decodes launcher icons from paths.
- Whether the registry should live in `NativeBridge` or `CompositorService`.
  Prefer service-owned state if the first consumer is a switcher Activity.
- How much dedupe belongs in Rust. Rust should avoid spamming unchanged metadata,
  but Kotlin should still tolerate repeated identical updates.
