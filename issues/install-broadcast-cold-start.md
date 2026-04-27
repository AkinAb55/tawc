- `me.phie.tawc.install.INSTALL` and `UNINSTALL` broadcasts are
  half-broken from a cold app on Android 14+: the receiver tries
  `startActivity(ManageInstallationsActivity)` to dodge the
  `mAllowStartForeground=false` rule that blocks `startForegroundService`
  from background broadcast receivers, but the activity launch is
  itself BAL-blocked when the app has no foreground privileges.
- Documented in `notes/installation.md` Android-14-FGS-gotcha section,
  with a fallback (`am start -n …/.ManageInstallationsActivity --es
  autoAction install`).
- The asymmetry is awkward — same logical operation, two different
  CLI invocations depending on app state. Options:
  - Drop the broadcasts entirely; tell people to use `am start`.
  - Have the receiver do the same `am start`-style activity launch
    we already document, then surface a cleaner error if BAL_BLOCKs it.
  - Use WorkManager `OneTimeWorkRequest.setExpedited()` with a
    foreground info — explicitly carved out of the Android 14
    restriction.
- Not blocking anything today, but worth picking one shape so the
  CLI surface is uniform.
