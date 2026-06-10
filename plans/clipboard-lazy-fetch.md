# Lazy Android clipboard fetch

Android 12+ shows a system toast ("tawc pasted from your clipboard")
whenever an app calls `ClipboardManager.getPrimaryClip()` on a clip
that originated in another app. tawc does that proactively: every
`CompositorActivity.onWindowFocusChanged(true)` runs
`ClipboardBridge.syncOnWindowFocusGained()` (a full clip read, retried
up to 3×250ms while access is still denied post-focus), and
`CompositorService` startup runs `syncCurrentTextToNative()`. AOSP
dedupes the toast per app+clip, but several OEM builds toast on every
read — hence "every time some screens open" on some devices only.

Two facts make a no-feature-loss fix possible:

- `getPrimaryClipDescription()` never triggers the toast and carries
  MIME types plus a millisecond timestamp (`getTimestamp()`, API 26;
  minSdk is 29).
- The Wayland side doesn't need the clipboard *content* to advertise a
  selection — only at `receive` time, i.e. when a client actually
  pastes. A toast at that moment is accurate and matches what
  Termux-style apps show.

## Design

Make the Android→Wayland direction announce-only; fetch content at
paste time.

**Kotlin announces, content-free.** All three current read points
(clip-changed listener, focus sync incl. retry loop, service-start
sync) stop calling `getPrimaryClip()`. Instead they read only the
description and, if it has a `text/*` MIME, call a new
`nativeOnAndroidClipAvailable(timestampMs, ownWrite)`. `ownWrite` is
true when the description label is `"tawc"` — the label
`setTextFromCompositor` writes — replacing today's text-matching
`suppressText`/`lastSyncedText` echo suppression. Gating on `text/*`
keeps the Gecko wrinkle covered (HTML-only descriptions still match
`text/html`); clips that carry text only in items with no `text/*`
description MIME are no longer caught — acceptable, rare.

**Native decides, in the event loop.** New
`ClipboardEvent::AndroidClipAvailable { ts, own_write }` replaces
`AndroidText(String)`. Policy: if a live selection exists and
(`own_write` or `ts == last_announced_ts`), skip; otherwise
`set_data_device_selection` with the usual text MIMEs and a payloadless
`SelectionUserData::Android`, notify xwm, remember `ts` (in
`TawcState`, not a static, so compositor restarts reset it). `ts == 0`
(OEM timestamp quirk) is treated as always-changed — announces are
idempotent so the worst case is a redundant selection reset.

The "skip only if a live selection exists" clause fixes a hole the
text-matching suppression has today: if the client that owned a
mirrored selection dies, the compositor now takes ownership on the
next announce instead of leaving paste broken.

**Fetch at paste.** Both `send_selection` sites
(`compositor/src/compositor.rs` SelectionHandler for Wayland clients,
`compositor/src/xwayland.rs` XwmHandler for X11 clients) match the
`Android` variant and spawn a `clipboard-fetch-android` thread (same
pattern as `write_text_to_fd`): reverse-JNI
`NativeBridge.fetchClipboardText(): String?` →
`ClipboardBridge.getTextForPaste()` does the real `getPrimaryClip()`
read — the toast now fires exactly once per actual paste — then the
thread writes the bytes to the fd. Null (no focus, non-text clip, or
over the 1MB cap, which moves here) → drop the fd: client sees EOF,
empty paste, one warn. Needs a `with_native_bridge` variant that
returns a value; `ClipboardManager` is a binder proxy and safe to call
off the main thread (make `ClipboardBridge.clipboard` `@Volatile`).

**Timestamp dedupe / paste cache.** `getTextForPaste()` keeps a
one-entry `(timestamp → text)` cache: check the description (toast-
free) first; if the timestamp matches the cached fetch, return the
cache without touching `getPrimaryClip()`. Kills repeat-paste toasts
on per-read-toast OEMs. Disabled when the timestamp is 0.

## Mechanics / cleanup

- Delete: `suppressText`, `suppressUntilMs`, `lastSyncedText`,
  `pushTextToNative`, `nativeOnAndroidClipboardText`,
  `clipboard::send_android_text`, `ClipboardEvent::AndroidText`,
  `SelectionUserData::AndroidText` (the xwayland.rs startup
  re-announce that matches it switches to the `Android` variant).
- The Wayland→Android direction (selection pulls,
  `set_android_clipboard_text`) is untouched.
- Dev actions: `clipboard-set-text` writes label `"tawc-dev"`, so it
  announces like a foreign app — integration tests keep working;
  `clipboard-get-text` stays a full read (debug-only path).
- Add a fetch counter to `nativeClipboardDebugState` so tests can
  assert the lazy path ran.
- Known regression: a client pasting while tawc has no input focus
  gets an empty paste instead of stale mirrored text (the read is
  denied). Acceptable; the pasting client normally has focus.
- Verify with the clipboard cases in
  `tests/integration/tests/apps.rs` (`scripts/run-integration-tests.sh`)
  on the `.tawctarget` device; manually confirm no toast on screen
  open and one toast on paste on a per-read-toast device.
