tawcroot — fast rootless chroot via systrap

Layout:
- `src/`, `include/`              — production C sources (no libc, freestanding)
- `tests/{unit,handler,integration}/` — cleat-orchestrated test suite
- `tests/testhost/`               — sources for the test-driving twin binary
- `Makefile`                      — host-incremental build
- `build`, `build-fixtures`       — cross-ABI NDK build scripts (Android packaging too)
- `test`                          — runs the cleat orchestrator (host) or pushes
                                    the testhost binary to a device via adb

Design + plan: ../notes/tawcroot.md
