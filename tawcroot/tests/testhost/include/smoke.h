/* Foundation-smoke helpers shared between parent and --exec-child paths.
 * Phase 1 will replace most of this with real handler/path-translation
 * unit tests. Until then this is the "is everything alive" surface. */

#pragma once

#include <stdint.h>

#include "handler.h"

/* Phase-0 smoke handoff blob. Distinct from the production
 * `tawcroot_exec_state` typedef in tawcroot/include/exec_state.h (the
 * real loader handoff). The names previously collided; renamed per
 * review finding D7 to keep the testhost out of the production
 * namespace. */
#define TAWC_SMOKE_MAGIC 0x7AC470074EE15770ULL  /* tAcr00tEEi5p70 -- pun. */

struct tawcroot_smoke_state {
	uint64_t magic;
	uint64_t parent_pid;
	uint64_t parent_handler_calls;
	uint64_t round;
};

/* Issue a stub-path getpid (must ALLOW) and an inline-asm getpid (must
 * TRAP into our handler and return -ENOSYS). Returns failure count.
 * Used by parent_main and child_main to validate the trap contract on
 * each side of the re-exec. */
int tawcroot_smoke_trap_contract(const char *label_prefix);

/* Run a sweep of every raw syscall the handler / bootstrap will use,
 * asserting none of them are TRAPped under our smoke filter. */
int tawcroot_smoke_exercise_raw(void);
