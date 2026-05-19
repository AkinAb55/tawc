# systemd-tmpfiles fails on /dev/net/tun (path missing in rootfs)

During `systemd-tmpfiles --create` under tawcroot:

    Failed to open path /dev/net/tun: No such file or directory

The Arch tmpfile rule (`/usr/lib/tmpfiles.d/systemd.conf`) wants to
chmod/chown `/dev/net/tun`, but `/dev/net/` doesn't exist in the
rootfs. We don't bind-mount the host's `/dev` (we only stage
`/dev/null`, `/dev/random`, `/dev/urandom`, etc. — see the install
profile), and the host's `/dev/net/tun` would not be useful anyway
because `untrusted_app` can't open it.

## Repro

    scripts/uninstall-distro.sh manjaro
    scripts/install-distro.sh manjaro tawcroot \
        mirrorProxy=http://127.0.0.1:8080/proxy/ distro=manjaro

Search for `Failed to open path /dev/net/tun`.

## Severity

Low. The error is cosmetic — no guest workload we target uses
`/dev/net/tun`. Possible fix: stage an empty `/dev/net/` directory
during install so the chmod stops failing (the tmpfile rule then
silently no-ops on the missing tun node).
