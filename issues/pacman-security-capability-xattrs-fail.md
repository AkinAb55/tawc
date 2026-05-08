# pacman cannot restore `security.capability` xattrs under tawcroot

During Manjaro install, package extraction prints warnings on every
file that ships a `security.capability` xattr, and the gstreamer
post-install scriptlet (which runs `setcap` itself) aborts with
`error: command failed to execute correctly`.

Symptoms in the install log:

    installing shadow...
    warning: warning given when extracting /usr/bin/newgidmap (Cannot restore extended attributes: security.capability security.capability)
    warning: warning given when extracting /usr/bin/newuidmap (Cannot restore extended attributes: security.capability security.capability)
    ...
    installing gstreamer...
    warning: warning given when extracting /usr/lib/gstreamer-1.0/gst-ptp-helper (Cannot restore extended attributes: security.capability security.capability)
    unable to set CAP_SETFCAP effective capability: Operation not permitted
    error: command failed to execute correctly

The root cause is that `untrusted_app` on Android is denied
`CAP_SETFCAP` by SELinux, and writing `security.capability` xattrs
needs that capability. The kernel returns EPERM, libarchive degrades
to a warning, and the install proceeds without the bit set.

Impact: setuid-via-capability binaries (`newuidmap`, `newgidmap`,
`gst-ptp-helper`, `ping`, …) lose their elevation. They still run,
but operations that depend on the capability fail at runtime. We
already work around the `newuidmap` case for the proot install
method; this is the same problem surfacing on tawcroot.

## Repro

    bash scripts/uninstall-distro.sh manjaro
    bash scripts/install-distro.sh manjaro tawcroot \
        mirrorProxy=http://127.0.0.1:8080/proxy/ distro=manjaro

Look for `Cannot restore extended attributes: security.capability`
and `unable to set CAP_SETFCAP effective capability` in the log.

## Possible workarounds

- Fake the xattr write in tawcroot (handle_setxattr returns 0 for
  `security.capability` instead of -EPERM). The xattr is then
  invisible to the kernel — fine for our use case (we don't actually
  honour Linux capabilities, since the app uid is fixed).
- Track per-file "intended capabilities" in tawcroot and enforce
  them at exec time (more invasive; only needed if a guest program
  actually checks `getcap` output).

## Severity

Low. Most affected binaries don't run inside our compositor anyway,
and the install completes. Worth tracking so we don't ship a distro
where a setuid-via-capability program is silently broken.
