/* Build + install the tawcroot seccomp filter. Single source of truth for
 * the trap set; the dispatch table indexes from the same list (later phase).
 *
 * Phase-0 surface: install_smoke_filter() installs a minimal filter for the
 * foundation smoke (one TRAP syscall + IP allowlist for the stub). Phase 1
 * grows this into the full path-bearing trap set with a generated
 * jump-table. Keep the API small so refactor doesn't ripple.
 */

#pragma once

#include <stddef.h>
#include <stdint.h>

/* Returns 0 on success, -errno on failure (raw syscall semantics). */
long tawcroot_install_smoke_filter(int trap_syscall_nr);

/* Phase-1 filter: build BPF from a list of syscall numbers to TRAP, plus
 * the IP allowlist. Same shape as the smoke filter; the trap set comes
 * from the caller so the dispatch table and the filter share one source
 * of truth. Returns 0 / -errno. */
long tawcroot_install_filter(const int *trap_nrs, size_t n_traps);

/* Sets PR_SET_NO_NEW_PRIVS, the prerequisite for unprivileged seccomp. */
long tawcroot_set_no_new_privs(void);
