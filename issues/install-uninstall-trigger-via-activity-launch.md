# Install / uninstall mutations fire as a side effect of opening an activity

`InstallActivity` and `UninstallActivity` host the live progress UI
for installs and uninstalls, but they're also the *trigger* for the
mutation: opening either activity with an `autoStart=true` intent
extra causes its `onCreate`/`onNewIntent` to call into
`InstallationService.startInstall` / `startUninstall`. "Open the
viewer" and "perform the mutation" are conflated into one intent.

This is the wrong shape for two reasons:

1. **Conceptually wrong.** Opening a screen should never wipe a
   rootfs. The activity-launch path is a UI affordance; the mutation
   path is a service call. Mixing them means every code change has
   to think about whether *displaying* the page is now also an
   *action*.
2. **Concretely fragile.** Android remembers launch intents on the
   recents card. Tapping the card resurrects the activity with the
   stored intent — which still carries `autoStart=true` — and the
   action re-fires. We patched this on 2026-05-04 by teaching
   `requestsAutoStart()` to ignore intents flagged
   `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, but that's a bandage on
   the wrong abstraction. Any future caller that copies an intent's
   flags, or any system path that drops `savedInstanceState` while
   keeping the recents entry, can break the gate again.

## Why we ended up here

Two callers, both routed through activity launch:

- **In-process — `DistroInfoActivity` Delete dialog**
  (`server/app/src/main/java/me/phie/tawc/install/DistroInfoActivity.kt:167-170`):
  builds an Intent for `UninstallActivity` with
  `EXTRA_AUTO_START=true` and `startActivity()`s it. Pure
  in-process work that round-trips through Android's intent system
  for no reason — the service already exposes
  `InstallationService.startUninstall(context, id)` as a companion-
  object helper.

- **adb / CLI** (`testing/run-integration-tests.sh:101`,
  `client/tawc-install-id.sh`, `notes/installation.md`,
  `CLAUDE.md` Quick Reference, several other notes): uses
  `am start -n …/InstallActivity --es autoStart true --es id <id> …`
  as a "headless trigger" because there isn't a non-activity
  entry point exposed to adb. The activity opens, runs the install,
  and the test scrapes `adb logcat -s tawc-install` for progress.

## Proposed redesign

Activities become pure viewers; mutations only ever go to the
service.

1. **Drop `AutoStart.kt` and the autoStart code paths in both
   activities.** No `requestsAutoStart()` checks, no
   `started`-flag UI gymnastics, no recents-history-flag bandage.

2. **`DistroInfoActivity` Delete dialog:** call
   `InstallationService.startUninstall(this, id)` directly, then
   `startActivity(UninstallActivity)` to view. Two lines, no
   intent-extras contract.

3. **adb / CLI:** add `InstallationCommandReceiver` registered for
   `me.phie.tawc.INSTALL` and `me.phie.tawc.UNINSTALL` broadcasts
   (same pattern as the existing `me.phie.tawc.TEXT_INPUT` receiver
   in `CompositorActivity`). The receiver calls the service
   helpers. CLI becomes:
   ```
   adb shell am broadcast -a me.phie.tawc.INSTALL --es id arch --es method proot
   ```
   No activity launched. Logs go to `adb logcat -s tawc-install` as
   today. If a human wants to watch, they `am start` the activity
   separately.

4. **Activity-as-viewer polish:** `InstallActivity` currently
   always lands on the form. After the redesign, opening it while
   the service has an active install job for the relevant id
   should land on the panel; opening on an idle service shows the
   form. Cheap to implement — the panel already binds to the
   service; we just key form-vs-panel visibility off
   `currentKind`/`progress.stage` instead of a local `started`
   flag. `UninstallActivity` is already panel-only; no UI change.

## Callers to update

- `testing/run-integration-tests.sh:101` — switch to broadcast.
- `client/tawc-install-id.sh` — switch to broadcast.
- `CLAUDE.md` Quick Reference — update the "Install proot/chroot"
  one-liners.
- `notes/installation.md` — rewrite the "trigger contract"
  section; the current `InstallActivity.kt` /
  `UninstallActivity.kt` table rows describe `autoStart` as the
  contract.
- `notes/android.md`, `notes/emulator.md`, `notes/testing.md` —
  update example commands.
- `testing/integration/src/lib.rs:62` — update doc comment that
  cites the `am start --es autoStart` recipe.
- `issues/firefox-test-flaky-on-cold-rootfs.md` — update repro
  commands.

## Out of scope

This issue does not propose changing the in-process service
machinery (`InstallationService` gate, job state, log replay). The
service surface is already broadcast/in-process-friendly via the
companion-object helpers; only the *callers* need to change.

## Related

- 2026-05-04 fix: `requestsAutoStart()` now refuses
  `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`-tagged intents
  (`server/app/src/main/java/me/phie/tawc/install/AutoStart.kt`).
  That patch should be deleted alongside `AutoStart.kt` itself
  when this redesign lands.
