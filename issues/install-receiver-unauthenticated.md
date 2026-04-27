- `me.phie.tawc.install.InstallationCommandReceiver` is declared
  `android:exported="true"` with no `android:permission` and no caller
  UID check. Any installed app on the device can fire e.g.

      am broadcast -n me.phie.tawc/.install.InstallationCommandReceiver \
          -a me.phie.tawc.install.RUN --es id arch --es cmd '<anything>'

  …and get arbitrary command execution as **root** inside the chroot
  (which has `/system`, `/vendor`, `/dev`, `/data/data/me.phie.tawc`,
  etc. bind-mounted). That's a privilege-escalation surface for any
  installation that has Magisk root granted.
- INSTALL / UNINSTALL are similarly reachable; less catastrophic but
  still unauthenticated control over a long-running root operation.
- Possible fixes:
  - Define a signature-level permission and gate the receiver on it
    (only same-signing-cert apps + adb shell can invoke; adb shell uid
    is normally allowed to use signature perms granted to the package).
  - Or check `Binder.getCallingUid()` against `Process.myUid()`,
    `Process.SHELL_UID`, `Process.ROOT_UID` at the top of `onReceive`.
  - The simple-and-obvious version of the latter was prepared but the
    user wants to think about the right shape first (signature
    permission vs. uid check vs. something else).
