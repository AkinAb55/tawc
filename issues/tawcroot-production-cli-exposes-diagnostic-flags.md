# tawcroot: production CLI exposes diagnostic argv branches

`tawcroot/src/main.c:71-130` accepts `--exec`, `--exec-via-handler`,
and `--exec-child` from any argv, including production builds. The
plan in `notes/tawcroot.md` (L1791-1798, L2491-2497) is explicit
that production "must NOT contain test-only argv branches."

The three flags split into two cases:

- **`--exec-child`** is genuinely needed in production for the
  SIGSYS-handler re-exec dance. It's not test-only and shouldn't
  be removed; the plan should be updated to document it as part
  of the production CLI.
- **`--exec` and `--exec-via-handler`** are diagnostic. They
  shouldn't be reachable in production builds — gate them behind
  `-DTAWCROOT_TESTHOST` so they only compile into
  `tawcroot-testhost`.

## Why it matters

Anything reachable from production argv is part of the supported
surface and gets used by accident in scripts. The diagnostic flags
also bypass parts of the normal entry path, which means a script
relying on them sees subtly different behavior from the real
launch path.

## Fix

`#ifdef TAWCROOT_TESTHOST` around the `--exec` and
`--exec-via-handler` branches in `main.c`; document `--exec-child`
in `notes/tawcroot.md` as part of the production CLI contract.
