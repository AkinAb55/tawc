/* In-process `--exec` driver: opens a guest binary, parses it,
 * (recursively for ld.so), maps everything, builds an initial stack,
 * and jumps. Never returns on success.
 *
 * This is the one-piece function the production CLI needs to actually
 * run a guest. The full phase-2.6 plan also has the SIGSYS-handler-
 * triggered re-exec dance (so the guest's `execve(2)` re-routes
 * through tawcroot) — that lands later. Today the only entry point
 * is `tawcroot --exec <path> [args...]` which calls this directly,
 * giving us host-side end-to-end coverage of the production loader
 * pipeline.
 *
 * On any failure we exit_group with a small numeric status (60..79
 * range) so callers can distinguish "loader failed" from "guest ran
 * and exited N" without conflating exit codes.
 */

#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Production loader I/O vtable, defined in src/loader_io_prod.c. */
extern const struct tawc_loader_io tawcroot_loader_io_prod;

/* Stash kernel-supplied auxv values for forwarding to the synthesized
 * guest stack. Call once per tawcroot incarnation (production main and
 * --exec-child both, since the kernel rebuilds auxv on each execve).
 * Caller must walk past argv and envp to find the auxv array; missing
 * entries should be passed as 0 and will be omitted from the synth. */
void tawcroot_loader_set_host_auxv(uint64_t hwcap, uint64_t hwcap2,
                                   uintptr_t sysinfo_ehdr,
                                   uint64_t clktck, uint64_t flags);

/* Arguments to forward to the guest as argv/envp. Passed directly to
 * the stack synthesizer.  `argv[argc]` must be NULL; `envp` must be
 * NULL-terminated. */
struct tawc_loader_exec_args {
	const char  *guest_path;       /* host-fs absolute path to guest */
	int          argc;
	const char *const *argv;        /* size argc + 1 (NULL term) */
	const char *const *envp;        /* NULL-terminated */
	const char  *platform;         /* AT_PLATFORM (e.g. "x86_64") */
};

/* Open + load + jump. Never returns on success (guest takes over).
 * On failure, calls tawc_exit_group(60..79) directly:
 *
 *   60   open guest failed
 *   61   ehdr read / parse failed
 *   62   phdrs read / parse failed
 *   63   guest is not ET_EXEC or ET_DYN
 *   64   PT_INTERP path read failed
 *   65   open ld.so failed
 *   66   ld.so ehdr/phdrs parse failed
 *   67   ld.so isn't ET_DYN, has its own PT_INTERP, etc
 *   68   guest binary mmap failed
 *   69   ld.so mmap failed
 *   70   stack region mmap failed
 *   71   getrandom for AT_RANDOM failed
 *   72   stack synth failed
 *
 * Caller-distinguishable from guest exit codes (which are typically
 * 0..127). The 60..79 range is reserved for loader-driver self-failure.
 */
__attribute__((noreturn))
void tawcroot_loader_exec(const struct tawc_loader_exec_args *args);

/* `--exec-child <fd>` mode driver: read a serialized exec_state from
 * `state_fd` (which must be open and readable, typically a memfd) and
 * call `tawcroot_loader_exec` with the parsed args. Never returns on
 * success.
 *
 * Failure exit codes (disjoint from `tawcroot_loader_exec`'s 60..79):
 *
 *   80   fstat on state_fd failed
 *   81   mmap of state_fd failed
 *   82   exec_state header invalid (magic / version / sizes)
 *   83   close(state_fd) failed (cleanup; not fatal in practice)
 */
__attribute__((noreturn))
void tawcroot_loader_exec_child(int state_fd, const char *platform);

#ifdef __cplusplus
}
#endif
