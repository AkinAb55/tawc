# render-pattern draws black on x86_64 emulator

`scripts/run-integration-tests.sh rendering::test_shm_render_pattern_orientation_pixels`
currently fails on `.tawctarget=emulator`: the compositor sees one SHM
surface, but the sampled render-pattern block is black.

## Root cause (verified 2026-07-06)

SELinux enforcing on the emulator. The compositor still composites the
SHM surface (magenta tint fade band is visible at the output edges) but
the texture content samples `(0,0,0)` everywhere — even the pattern's
dark-gray background. With `su 0 setenforce 0` and nothing else changed,
the same tawcroot + `--graphics cpu` render-pattern run shows all four
blocks with the expected colors at the expected corners. This is the
known SHM/SELinux limitation in notes/emulator.md, not a compositor
coordinate/rendering bug.

Two reasons the documented `setenforce 0` baseline wasn't in effect:

- The running AVD was `tawc-rootless`, and `scripts/emulator.sh start`
  deliberately skips `setenforce` there ("won't render SHM client
  surfaces" is its documented limitation). But the google_apis x86_64
  image is userdebug and ships AOSP `/system/xbin/su`, so root *is*
  available: `su 0 setenforce 0` works. The "rootless can't do this"
  assumption is wrong for this image.
- Independently, `emulator.sh` uses `su -c "setenforce 0"`. AOSP su
  rejects that syntax (`su: invalid uid/gid '-c'`); it wants
  `su 0 setenforce 0`. Magisk su accepts both. So even if the rootless
  branch ran the command, it would fail on this image.

Not yet captured: the concrete AVC denial for the tawcroot path
(client and compositor share the untrusted_app domain, so it is not
obviously the chroot memfd `appdomain_tmpfs` case from
notes/emulator.md). A repro under enforcing with `dmesg` watching for
`avc: denied` on tmpfs/memfd would pin it down.

## Candidate fixes

1. Make `emulator.sh start` apply `setenforce 0` on both AVD flavors
   using su syntax that works for AOSP and Magisk su, verify with
   `getenforce`, and warn loudly on failure; update notes/emulator.md.
2. Alternatively (or additionally) have the integration-test harness
   fail fast on emulator targets when `getenforce` reports Enforcing,
   so SHM pixel tests fail with an actionable message instead of
   sampling black.

If the rootless AVD's stock-ness matters more than SHM rendering,
option 2 alone keeps it stock.
