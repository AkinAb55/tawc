# tawcroot: exec argv/env limits are inconsistent and far below kernel limits

Three related sizing problems in the guest `execve` path:

1. **Collection vs serialization mismatch.** `syscalls_exec.c` accepts
   up to 64 KB argv + 256 KB envp (the 256 KB sizing comment cites
   bash/LS_COLORS/BASH_FUNC environments), but
   `exec_handler.c::EXEC_STATE_BUF_SIZE` is 64 KB total and the
   exec_state header alone eats ~2.7 KB. Any argv+envp total over
   ~61 KB makes `tawcroot_exec_state_write` return `-ENOSPC`, which
   the guest sees as `execve() == ENOSPC` ("No space left on device")
   — a nonsensical errno, reachable by exactly the environments the
   collection layer was sized for. (Comment in exec_handler.c points
   here.)

2. **MAX_ARGS 256 is too small.** The kernel allows ~2 MB of argv
   strings (thousands of args). Shell globs (`rm *`, `cp dir/* …`),
   linker invocations, and pacman hooks routinely exceed 256 entries;
   each such exec fails `-E2BIG` even though the strings would fit.
   64 KB of argv strings is similarly low (kernel: MAX_ARG_STRLEN =
   32 pages PER string).

3. errno shape: an oversized single string returns `-ENAMETOOLONG`
   (from `tawc_copy_string_from_guest`) where the kernel returns
   `-E2BIG`.

## Fix direction

Size the exec_state buffer to hold whatever collection accepts
(header + 64 KB + 256 KB ≈ 324 KB static, or write argv/env straight
into the memfd with `ftruncate`+`mmap` instead of staging in BSS —
the memfd approach also removes the static-buffer thread-safety issue,
see tawcroot-exec-static-buffers-not-thread-safe.md). Bump MAX_ARGS to
at least 4096 (offset arrays in the exec_state header grow 16 B/entry).

## Repro

Guest: `env $(seq 1 300 | sed 's/^/A/') /bin/true` → E2BIG today.
Or export ~100 KB of environment and exec anything → ENOSPC.

## Severity

Medium: breaks glob-heavy shell use, the core target workload
(pacman, build scripts).
