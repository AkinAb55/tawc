# tawcroot prod-env device tests

Add a device test suite that runs production tawcroot in the **real
production sandbox** — app uid, `untrusted_app` SELinux domain, the
zygote-installed seccomp filter — by spawning it through the exec
broker, the same way production launches work.

**Status: plan, not started.**

## Why

`tawcroot/test.sh --device` runs the cleat orchestrator as adb shell:
`shell` domain (uid 2000) on the physical device, policy-permissive
`su` (uid 0) on the rooted emulator. Neither is where production
tawcroot runs, and the variance between them has cost us real
debugging time (the rooted-adbd euid-0 skips and either-errno
acceptance from `de5f95d`; see notes/tawcroot/testing.md
"Device-environment sensitivities"). Meanwhile no test today executes
tawcroot under the production sandbox except full-distro integration
workloads.

The blocker to running the *whole* suite as the app is structural:
`untrusted_app` (targetSdk 36) cannot execve app-data or
`/data/local/tmp` files, so the orchestrator/testhost binaries can't
run there without APK packaging (rejected). But the layer-3 shape —
`tawcroot -r <fake-rootfs> -- <guest-program>` — needs **no test
binary on the device**: production `libtawcroot.so` is already in the
APK's `nativeLibraryDir` (execve-able), and guest programs + fake
rootfs are pure data that tawcroot's manual ELF loader mmaps.

## Design

Host-side Rust integration tests (new module beside
`tests/integration/tests/tawcroot.rs`) that:

1. Stage a minimal fake rootfs plus the cross-built guest programs
   (`build/tawcroot-<abi>/programs/`, built by
   `tawcroot/build-fixtures.sh`; tree shape mirrors
   `tawcroot/tests/integration/rootfs_helpers.c`) into app-private
   data, e.g. `/data/data/me.phie.tawc/cache/tawcroot-prodtest/`.
   Delivery goes through the broker (ARGV `sh -c 'cat > …'` per file,
   or `toybox tar` over stdin) — files must be app-owned/readable;
   app-private location means uninstall cleans up.
2. Run `ARGV <nativeLibraryDir>/libtawcroot.so -r <staged-rootfs> --
   /bin/<prog>` through the broker. The child forks from the app
   process, inheriting uid, domain, and the real zygote seccomp
   filter — bit-for-bit the production launch condition, without the
   RUNINSIDE distro/graphics machinery.
3. Assert on relayed stdout/exit, like the host layer-3 tests.

Open detail: the host needs `nativeLibraryDir`. Cleanest is a tiny
broker addition (report it from `query-state` or a one-line dev
action) rather than parsing `pm dump`.

## What this newly covers

- **Ground truth for the synthesized androidfilter.** The
  `androidfilter` wrap encodes what we *believe* zygote installs;
  this suite runs under the filter zygote actually installs, on both
  targets. Drift between synth and reality becomes a test failure.
- **Production SELinux interactions in the production domain.**
  e.g. `link(2)` denial → v1 rename+symlink fallback exercised as
  `untrusted_app` on both targets (today only the physical device's
  `shell` domain hits it, incidentally).
- **No adbd-mode variance.** Broker children don't care whether adbd
  is rooted; the euid-0 skip class cannot recur here.

## Test placement rule

**Anything fully testable in prod-env is tested in prod-env.** Do not
duplicate a check between prod-env and the adb-shell cleat device
suite unless the duplicate genuinely adds coverage (and say why where
the duplicate lives). Concretely:

- New device-relevant checks that a guest program can observe
  (path translation results, errno surfaces, identity illusion,
  linkat/O_TMPFILE behavior) go here, not into testhost smoke steps.
- Existing smoke steps whose real target is "how does Android policy
  treat this syscall" migrate to guest programs and are removed from
  the device-relevant expectations of the testhost run where covered.
- What stays testhost-only, by construction: trap contract, filter
  install, stub IP allowlisting, `--exec-child` re-exec plumbing,
  loader diagnostics — anything that must *be* the supervisor or
  issue pre-filter raw syscalls. `test.sh --device` remains as-is
  for those (its documented environment sensitivities included).

## Steps

1. Broker: expose `nativeLibraryDir` to the host.
2. Rust: rootfs staging helper (broker-based file delivery, cleanup).
3. Rust: prod-env test module running an initial slice of the
   existing guest programs; wire into `run-integration-tests.sh`
   (fixtures are already cross-built for the device path).
4. Port the environment-sensitive checks (linkat fallback family,
   identity EPERM) as guest programs where guest-observable.
5. Docs: update notes/tawcroot/testing.md (new layer + placement
   rule) and notes/exec-broker.md (new allowed usage) in the same
   change; delete this plan.
