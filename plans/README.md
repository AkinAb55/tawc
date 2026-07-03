# Plans Index

Future work and speculative implementation plans live here. Keep current-state
design, build, and operational notes in [`../notes/`](../notes/).

- [ando-toggle.md](ando-toggle.md) - per-distro toggle for ando (default off); per-distro sockets + conditional bind so disabling closes the socket path, not just the CLI.
- [audio.md](audio.md) - planned PipeWire/PulseAudio bridge to Android audio.
- [desktop-gl-dispatch.md](desktop-gl-dispatch.md) - older desktop-GL dispatcher design, likely superseded by libhybris-zink unless GLES-over-Zink overhead is unacceptable.
- [gfxstream-bridge-remaining-work.md](gfxstream-bridge-remaining-work.md) - remaining GL/GLES and x86_64 AVD work for the gfxstream bridge backend.
- [tawcroot-future-work.md](tawcroot-future-work.md) - deferred tawcroot syscall, `/proc`, and performance work.
- [tawcroot-readonly-binds.md](tawcroot-readonly-binds.md) - future read-only fake bind support in tawcroot.
- [tawcroot-virtual-identity.md](tawcroot-virtual-identity.md) - stateful fake uid/gid tracking so privilege-dropping daemons (stock sshd) work; replaces the reverted passwd/group workarounds.
- [verify-libhybris-ahb-alpha.md](verify-libhybris-ahb-alpha.md) - verify sampled-alpha AHB rendering on device after removing the force-opaque workaround.
- [xwayland-server-side-gl.md](xwayland-server-side-gl.md) - parked Xwayland server-side GL acceleration plan.
- [xwayland-glibc-alternative.md](xwayland-glibc-alternative.md) - parked glibc-built Xwayland approach and seccomp patching notes.
