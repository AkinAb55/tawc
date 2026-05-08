# systemd-tmpfiles can't set chattr-style file attributes on /var/log/journal

During post-install of `systemd` under tawcroot, `systemd-tmpfiles
--create` emits:

    Cannot set file attributes for '/var/log/journal', maybe due to incompatibility in specified attributes, previous=0x10001800, current=0x10001800, expected=0x10801800, ignoring.
    Cannot set file attributes for '/var/log/journal/remote', maybe due to incompatibility in specified attributes, previous=0x10001800, current=0x10001800, expected=0x10801800, ignoring.

The tmpfile rule for these dirs requests `FS_NOCOW_FL` (`+C`); the
underlying filesystem (the app's data dir, ext4 on Android) doesn't
support setting that flag via `FS_IOC_SETFLAGS`, so the
`ioctl(FS_IOC_SETFLAGS, …)` returns EOPNOTSUPP. systemd already says
`ignoring`, but it surfaces as `error: command failed to execute
correctly` in pacman because tmpfiles exits non-zero overall.

## Repro

    bash scripts/uninstall-distro.sh manjaro
    bash scripts/install-distro.sh manjaro tawcroot \
        mirrorProxy=http://127.0.0.1:8080/proxy/ distro=manjaro

Search for `Cannot set file attributes for '/var/log/journal'`.

## Severity

Low. The journal directory is created and writable; only the COW
hint is missing. Possible fixes: stub `FS_IOC_SETFLAGS` in tawcroot
to return 0 (lying), or filter the `+C` rule out of the journald
tmpfile config in our default rootfs profile.
