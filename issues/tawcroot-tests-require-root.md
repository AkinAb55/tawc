# tawcroot test suite requires root on device

`bash tawcroot/test --device` (and therefore the
`tawcroot::test_tawcroot_device_suite` integration test) shells the
on-device test orchestrator via `su -c`, twice
(`tawcroot/test:172` and `tawcroot/test:190`). Every other on-device
action in the integration suite goes through the dev-exec broker as
the app uid — this is the lone path that still needs root.

Background. The cleat-driven orchestrator forks production tawcroot
and the testhost twin, then drives them through the four-layer suite.
The blockers, per the comment block at `tawcroot/test:163-171`:

1. **`mknod`/`mknodat` privilege** — some handler tests create FIFOs
   to verify production tawcroot intercepts the syscall correctly.
   Android's SELinux denies `mknod` on `shell_data_file` to the
   `shell` domain, so running as shell doesn't work.
2. **W^X for app data** — `untrusted_app` (the broker's domain) can't
   exec freshly-pushed binaries out of app data, so routing through
   the broker doesn't work either.
3. **Prior-run tree ownership** — root-owned rootfs trees from the
   previous run can only be cleaned up as root, so the cleanup `rm
   -rf` in the same script also needs `su`.

Why this is wrong even as testing infrastructure: requiring root on
the device means CI / contributors without a rooted phone can't run
the tawcroot integration suite; on the user's Pixel 4a (which has
root via Magisk) it works, but it's an undocumented prereq the user
hits when first running the suite. It also means
`tawcroot::test_tawcroot_device_suite` is uniquely fragile to phone
state (e.g. Magisk reauthorising su on Android update).

Production tawcroot is rootless. The test infrastructure should be
too.

## Approaches

Several plausible directions; none costed yet, all listed for the
person who picks this up:

1. **Avoid the `mknod` tests on device** — keep handler-layer FIFO
   tests host-only (they already run in `--host` mode without su),
   and trim the device-mode suite to integration tests that don't
   need privileged syscalls. Loses some signal on whether the
   handler's mknod intercept works under the device's real kernel /
   SELinux policy; gains rootless device tests.
2. **Push test binaries somewhere exec'able by `shell`/app** — e.g.
   `/data/local/tmp/tawc-dev/` is already shell-writable (see
   `scripts/lib/tawc-scratch.sh`), but `untrusted_app` exec there is
   the actual block, not the push. Investigate whether the broker
   can extract pushed binaries into app data with the executable bit
   preserved, since app-data exec is allowed for files inside
   `nativeLibraryDir`.
3. **Stage test binaries in the APK** — same trick we use for
   tawcroot / libhybris / gfxstream-bridge: ship the test
   orchestrator + testhost + production binary + fixtures in
   `jniLibs/<abi>/`, extract via the broker on suite entry. APK
   bloat is real (cleat-built tests + fixtures are several MB).
4. **Use the existing tawcroot install + chroot** — the orchestrator
   already pushes binaries into `$TAWC_SCRATCH`; could instead run
   them inside an installed rootfs (where exec is fine) and let
   tawcroot itself handle the privileged operations. Circular —
   tests for tawcroot running inside tawcroot — but might be the
   cleanest fit.

Punted for now; user noticed during a run-integration-tests
session and asked it be filed rather than fixed.
