# render-pattern draws black on x86_64 emulator

`scripts/run-integration-tests.sh rendering::test_shm_render_pattern_orientation_pixels`
currently fails on `.tawctarget=emulator`: the compositor sees one SHM
surface, but the sampled render-pattern block is black.

Manual repro on `emulator-5554`:

1. `scripts/app-build-install.sh --no-build`
2. `scripts/rootfs-run.sh '/usr/local/bin/wayland-debug-app render-pattern'`
3. `adb exec-out screencap` samples at the expected top-left block center
   return `(0, 0, 0, 255)`.

The compositor log shows the SHM buffer import and one rendered toplevel, so
this looks separate from buffer-type tint coordinate handling.
