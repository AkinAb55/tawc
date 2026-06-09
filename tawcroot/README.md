tawcroot — fast rootless chroot via systrap

Layout:
- `src/`, `include/`              — production C sources (no libc, freestanding)
- `tests/{unit,handler,integration}/` — cleat-orchestrated test suite
- `tests/testhost/`               — sources for the test-driving twin binary
- `Makefile`                      — host-incremental build
- `build.sh`, `build-fixtures.sh` — cross-ABI NDK build scripts (Android packaging too)
- `test.sh`                       — runs the cleat orchestrator on the host, or
                                    cross-builds and pushes the orchestrator +
                                    tawcroot + testhost + fixtures to a device
                                    and runs it there (`--device`)
- `perf/`                         — micro-benchmark harness comparing backends

Design + plan: ../notes/tawcroot.md
