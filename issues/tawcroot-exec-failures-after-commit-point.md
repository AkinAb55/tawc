# tawcroot: exec failures discovered after execveat destroy the calling process

`tawcroot_exec_handler_perform` commits via `execveat(self,
--exec-child)` BEFORE the loader has fully validated the target. Any
problem found after that point cannot be returned to the caller as an
execve errno — the calling program is already gone and the process
exits with a loader code (60–82).

The probe now catches the worst offenders (missing file, directory →
EISDIR, non-regular / no-x-bit → EACCES, since the 2026-06 review
pass), but these still die post-commit:

- **Executable non-ELF, non-shebang file** (e.g. a `chmod +x` text
  file without `#!`): real execve returns `-ENOEXEC` and shells then
  re-run the file as a script (`sh file` fallback — POSIX requires
  this). Under tawcroot the process dies with loader exit 61.
- **Shebang chain whose interpreter is missing/bad**: real execve
  returns ENOENT/ELOOP/ENOEXEC; tawcroot dies with exit 75.
- **Bad PT_INTERP / unloadable ld.so**: kernel returns ENOENT/EINVAL;
  tawcroot dies with 64–69.

## Fix shape

Move classification into the probe: read the first BINPRM_BUF_SIZE
bytes of the (already-open) probe fd; resolve shebang chains there
(the resolver logic in loader_exec.c is nearly reusable — it only
needs open_in_view + pread); require the final file to parse as
ET_EXEC/ET_DYN ELF for the right machine. Then the commit point only
fails for genuinely unpredictable races (file replaced between probe
and child open) — same TOCTOU window real execve doesn't have, but
narrow.

## Severity

Medium: `./notascript.txt` killing the invoking shell instead of
falling back to `sh` is a visible correctness break for interactive
use and Makefiles.
