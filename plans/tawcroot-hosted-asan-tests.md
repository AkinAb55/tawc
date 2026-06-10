# tawcroot: hosted ASan test build (plan)

Goal: get as much tawcroot code as possible compiled into the hosted
cleat `tests` binary so it runs under AddressSanitizer (+ UBSan), make
sanitizer errors fail the test run, and clean up the test suite along
the way.

## Why this is cheap: one seam, not per-file vtables

Every raw syscall in the production tree funnels through a single
extern, `tawcroot_raw_syscall(nr, a..f)`, defined only in
`src/arch/{x86_64,aarch64}_stub.S` (`raw_sys.h` wraps it; the loader
additionally has its own io vtable). Files are excluded from
`PROD_C_FOR_TESTS` today only because linking the asm stub (and its
`_start`) into a glibc binary clashes — not because the C code can't
compile hosted.

So the cutover is: provide a **hosted implementation of
`tawcroot_raw_syscall`** in the tests build, and nearly all of
`PROD_C` becomes hosted-compilable with zero changes to handler code.

Hosted shim design (`tests/hosted/raw_syscall_host.c` or similar):

- Implement the syscall with inline asm (same register protocol as the
  stub, `-errno` return convention preserved exactly). Do **not** use
  glibc `syscall(2)` — its `-1`/`errno` convention would need lossy
  re-conversion and adds libc state to the hot path under test.
- Add a test-installable interceptor hook in front:
  `tawcroot_test_raw_hook(long nr, long args[6], long *ret)` — a
  single function pointer, default NULL → real syscall. Tests use it
  to: deny `exit_group` (would kill the orchestrator), fake `chroot`
  (needs root), fake failures (ENOSPC/EINTR injection), and observe
  syscall sequences when a test genuinely cares. This replaces any
  need for per-file dependency-injection refactors.
- The hook lives in the hosted shim only; production keeps the bare
  asm stub. No production code changes.

What stays freestanding-only (cannot meaningfully run hosted):
`arch/*.S` (`_start`, raw stub, loader-jump trampolines). Everything
else should at least compile into the tests binary. `main.c` /
`filter.c` (seccomp install) / `handler.c` (SIGSYS entry) compile
fine; the constraint is that hosted tests must not *install* a real
seccomp filter or rely on SIGSYS delivery in-process — that stays in
the fork-based handler/integration layers against the real binaries.

Note `usercopy.c` works unmodified in-process: it targets the current
task id via `process_vm_readv/writev`, so "guest memory" in hosted
tests is just test-owned buffers. Combined with a real tmpdir rootfs,
this means whole syscall handlers (decode → copy path from guest →
translate → real `openat` → write result back) can run in-process
under ASan.

## Phases

### 0. Sanitize what exists today

- Add `-fsanitize=address,undefined -fno-sanitize-recover=all` to
  `TESTS_CFLAGS`/`TESTS_LDFLAGS` in `tawcroot/Makefile`. Host-only;
  the NDK/device build in `build.sh` is untouched.
- Always-on for the host tests binary, not a knob. Rationale: the
  Makefile's incremental build does not rebuild on flag changes (see
  its own comment), so an env toggle silently mixes object files. If
  an unsanitized host build is ever needed, add it as a separate
  `OBJ`/`OUT` suffix, not a flag on the same tree.
- Set defaults in `test.sh` host mode:
  `ASAN_OPTIONS=detect_stack_use_after_return=1:strict_string_checks=1`.
  Leak checking stays on (default); cleat or fixture leaks that
  surface get fixed or suppressed explicitly, not by disabling LSan.
- Validate the two risky test families:
  - `test_loader_smoke*` fork and map ELF images at fixed addresses
    (`HOST_BASE` 0x10000000, fixture bases). Both are below ASan's
    x86_64 low-shadow boundary so they should coexist; confirm, and
    wrap in `test_capture` forks if an ASan-runtime collision shows
    up.
  - Failure model: cleat runs tests in-process, so the first ASan
    error aborts the whole orchestrator. That still fails the run
    loudly (exit code), which is the requirement; per-test isolation
    via `test_capture` is available case-by-case if one bad test
    masking the rest becomes annoying.

### 1. Hosted raw-syscall shim + move the source list

- Implement the shim + hook as above.
- Move files from `PROD_C`-only into `PROD_C_FOR_TESTS` in dependency
  order, fixing what surfaces: globals are fine (all `tawcroot_`-
  prefixed), but watch for hosted/freestanding header drift
  (`tawc_string.h` pattern) and any symbol that collides with glibc.
  Suggested order (leaf → core): `io.c`, `identity.c`, `usercopy.c`,
  `path.c`, `dispatch.c`, `syscalls_fd.c`, `syscalls_fs.c`,
  `proc_shadow.c`, `syscalls_socket.c`, `syscalls_control.c`,
  `chroot.c`, `shm.c`, `exec_handler.c`, `syscalls_exec.c`,
  `loader_io_prod.c`, `loader_exec.c`, `supervisor.c`, `filter.c`,
  `handler.c`, `main.c`.
- Done means: every `.c` under `src/` except nothing (target) or a
  short documented exception list compiles into `tests`.

### 2. Hosted handler-level tests (the actual payoff)

New tests that drive real handler logic in-process under ASan. These
are "unit" tests only in the sense that they live in the cleat binary
— multi-step, stateful, high-level scenarios are explicitly in scope.
Put them in a new `tests/hosted/` cleat module dir to keep the
pure-function `tests/unit/` files focused.

- A small harness: build a real rootfs tree in a tmpdir (reuse/port
  `build_rootfs` from the integration helpers), open it as the rootfs
  fd, populate the bind/memo tables, then call syscall handlers
  directly with synthesized args; "guest" buffers are plain test
  arrays (usercopy self-targets).
- Priority targets = biggest uncovered, most guest-input-facing code:
  `syscalls_fs.c` (1.5k lines: openat/stat/rename/unlink families,
  trailing-slash and dirfd-anchor semantics), `path.c` (bind table
  add/memo build), `proc_shadow.c` (`/proc` rewrites, maps shadow —
  see issues/tawcroot-maps-shadow-size-limits.md for known cases),
  `syscalls_fd.c` (reserved-fd EBADF paths), `dirent_filter.c`
  against real `getdents64` output.
- Use the hook for fault injection that fork-based layers can't do:
  EINTR/ENOSPC mid-handler, short reads, EMFILE on `F_DUPFD`.
- Existing handler-layer tests (`tests/handler/`, forked testhost)
  stay, but new logic-coverage tests should default to hosted; the
  fork layers' job narrows to what genuinely needs the real
  freestanding artifact: filter install + SIGSYS delivery, stub IP
  allowlisting, `_start`/bootstrap, on-device behavior.

### 3. Test-suite cleanup

The unit suite is largely high-value (kernel-semantics oracles,
adversarial escape/filter tests, regression pins with rationale) —
this is a trim, not a purge:

- Delete `tests/unit/test_placeholder.c`.
- Re-home or fold "documents current behavior, not a contract" pins
  (`tawc_parse_long("--5")`, `int_to_str` truncation note, memo
  8-hop landing value) — keep the termination/no-overflow assertions,
  drop assertions on incidental outcomes.
- Audit `tests/handler/` for tests whose substance is handler *logic*
  (not filter/SIGSYS mechanics) and port them to hosted so they gain
  ASan coverage; leave a comment pointing at the hosted twin where a
  fork-based copy is kept for artifact fidelity.
- While auditing: ensure no test asserts on buffers larger than it
  initializes (ASan's strict_string_checks will find these anyway).

### 4. UBSan on the freestanding binaries (optional, last)

Hosted UBSan ships in phase 0 for free. Separately, the production
and testhost binaries can take trap-mode UBSan with no runtime:
`-fsanitize=undefined -fsanitize-trap=all` (+ likely
`-fno-sanitize=alignment` for ELF parsing). A UB hit becomes SIGILL →
forked test fails. Diagnostics are poor (trap PC + addr2line only),
so:

- Test builds only, never the shipped binary.
- Do it only if it doesn't destabilize: if alignment or
  pointer-arith checks fire on intentional patterns and exemptions
  get fiddly, drop the phase — hosted ASan+UBSan already covers the
  same C code; this only adds coverage for codegen/arch differences.
- Real bugs it finds are wins, not "problems".

## Other improvements worth bundling

- **fd-leak check per test**: snapshot `/proc/self/fd` in a cleat
  fixture/wrapper and diff after each hosted test. The fd table is
  the product's main "leakable" resource (no heap) and neither
  sanitizer tracks it.
- **TSan follow-on** (separate run, not mixed with ASan):
  `signal_shadow.c`'s lock-free paths already have pthread-hammer
  tests hosted; a `-fsanitize=thread` variant of the tests binary
  would check them properly. Cheap once phase 1 lands; optional.
- Update `notes/tawcroot.md` "Testing strategy" + `notes/building.md`
  when the layers/flags change (CLAUDE.md rule), and
  `tawcroot/test.sh`'s header comment (it documents the four layers).

## Risks / open questions

- ASan shadow vs fixed-base loader smokes (phase 0 validates; worst
  case those tests run in `test_capture` forks or stay exec-based).
- `supervisor.c`/`main.c` hosted compile may hit symbols or include
  assumptions not visible from this survey; the exception list is
  allowed to be nonzero, just documented and small.
- LSan + heavy `fork()` in existing tests: children that `_exit`
  skip leak checks (good); watch for false leak reports from cleat
  itself on first enable.
- Device mode is deliberately out of scope: sanitizers don't apply
  cleanly to the NDK freestanding build, and host coverage of the
  same sources is the point of this plan.
