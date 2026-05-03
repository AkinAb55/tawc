# tawcroot: mmap-error checks use bare `rv < 0 && rv >= -4095` instead of helper

Several call sites (e.g. `tawcroot/src/loader_exec.c:108, 170`)
check whether a raw `mmap` returned an errno with the inline
pattern `if (rv < 0 && rv >= -4095) FAIL;`. We already have
`tawc_loader_mmap_is_err` and use it elsewhere — the bare numeric
form is inconsistent with the helper.

## Why it's fragile

A successful mmap that returns a high-bit-set address (only
possible if the kernel exposes the high half of userspace, e.g.
5-level paging on x86_64 with no opt-in cap) would slip through
the inline check. We don't run on such a config today, but the
inline form is a future-ABI hazard.

## Fix

Replace the inline checks with `tawc_loader_mmap_is_err(rv)` (or
the equivalent shared helper) at every call site. Mostly cosmetic
but normalizes the contract.

## Severity

Low. Cosmetic / future-proofing.
