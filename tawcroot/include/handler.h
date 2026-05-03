/* SIGSYS handler — phase 0 stub.
 *
 * In phase 0 the handler does the absolute minimum needed to prove the
 * filter/handler contract: record what it saw and return -ENOSYS via
 * ucontext rewrite. Path translation, fd-provenance tracking, and the
 * dispatch table all bolt onto the same shape later — the hot-path
 * constraints (no malloc, no stdio, no libc with hidden state) are
 * already enforced by the structure here. See notes/tawcroot.md
 * "SIGSYS handler" and "Why the handler is async-signal-safe".
 *
 * The smoke driver reads `tawcroot_handler_observe()` after triggering
 * a TRAP to validate that the handler ran and saw the right thing.
 */

#pragma once

#include <stdint.h>
#include <stddef.h>

typedef struct {
	uint64_t calls;
	long     last_nr;
	long     last_arg0;
	uintptr_t last_call_addr;   /* siginfo_t::si_call_addr */
	uintptr_t last_resume_pc;   /* mcontext_t resume PC    */
	int      last_si_code;
	int      last_si_arch;
} tawcroot_handler_obs;

/* Install the SIGSYS handler. Returns 0 / -errno (raw syscall). */
long tawcroot_install_handler(void);

/* Read the most-recent observation. Returns a snapshot — the caller may
 * inspect any field. Stable across handler invocations because we update
 * the slot in-place inside the handler (one writer, one reader, fenced). */
void tawcroot_handler_observe(tawcroot_handler_obs *out);
