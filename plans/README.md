# Plans Index

Future work and speculative implementation plans live here. Keep current-state
design, build, and operational notes in [`../notes/`](../notes/).

- [audio.md](audio.md) - planned PipeWire/PulseAudio bridge to Android audio.
- [desktop-gl-dispatch.md](desktop-gl-dispatch.md) - older desktop-GL dispatcher design, likely superseded by libhybris-zink unless GLES-over-Zink overhead is unacceptable.
- [external-storage-binds.md](external-storage-binds.md) - plan for persistent shared-storage rootfs binds via all-files access, plus a later SAF-backed virtual directory bridge.
- [gfxstream-bridge-remaining-work.md](gfxstream-bridge-remaining-work.md) - remaining GL/GLES and x86_64 AVD work for the gfxstream bridge backend.
- [integration-test-host-transport.md](integration-test-host-transport.md) - plan to remove per-test adb/logcat/tawc-exec process churn from integration tests.
- [lazy-init-compositor.md](lazy-init-compositor.md) - defer compositor GPU/Wayland initialization until the first client connects.
- [lazy-init-kumquat.md](lazy-init-kumquat.md) - defer gfxstream/kumquat GPU initialization until the first gfxstream client connects.
- [smithay-desktop-refactor.md](smithay-desktop-refactor.md) - speculative plan for adopting more Smithay desktop/window abstractions.
- [tawcroot-future-work.md](tawcroot-future-work.md) - deferred tawcroot syscall, `/proc`, and performance work.
- [tawcroot-hosted-asan-tests.md](tawcroot-hosted-asan-tests.md) - compile (nearly) all tawcroot sources into the hosted cleat tests binary via a host raw-syscall shim, run them under ASan/UBSan, add hosted handler-level tests, and trim low-value tests.
- [tawcroot-readonly-binds.md](tawcroot-readonly-binds.md) - future read-only fake bind support in tawcroot.
- [xwayland-server-side-gl.md](xwayland-server-side-gl.md) - parked Xwayland server-side GL acceleration plan.
- [xwayland-glibc-alternative.md](xwayland-glibc-alternative.md) - parked glibc-built Xwayland approach and seccomp patching notes.
