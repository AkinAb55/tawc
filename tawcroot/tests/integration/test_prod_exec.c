/* Integration tests for production `tawcroot --exec`.
 *
 * Forks the freestanding production binary (built `-static -nostdlib
 * -no-pie -ffreestanding`, no glibc) and exercises its CLI end-to-end.
 * This proves the production raw_sys-backed loader I/O vtable
 * (loader_io_prod.c) works against real binaries — the unit tests
 * exercise the same loader logic but with a libc-forwarding I/O impl,
 * which doesn't catch raw_sys wiring bugs.
 *
 * Test fixtures live in tests/integration/programs/ (built by the
 * Makefile; see TAWCROOT_*_BIN defines).
 */

#include <cleat/test.h>
#include <cleat/subproc.h>
#include <stc/cstr.h>

#ifndef TAWCROOT_PROD_BIN
# error "TAWCROOT_PROD_BIN must be defined by the build"
#endif
#ifndef TAWCROOT_STATIC_EXIT42_BIN
# error "TAWCROOT_STATIC_EXIT42_BIN must be defined"
#endif
#ifndef TAWCROOT_DYNAMIC_EXIT42_BIN
# error "TAWCROOT_DYNAMIC_EXIT42_BIN must be defined"
#endif
#ifndef TAWCROOT_DYNAMIC_BIG_STACK_BIN
# error "TAWCROOT_DYNAMIC_BIG_STACK_BIN must be defined"
#endif

/* Run TAWCROOT_PROD_BIN with the given extra args (NULL-terminated).
 * Returns the wait-status code (exit code on normal exit, negative
 * on signal or framework failure). run_subproc takes ownership of
 * `cmd`, so the local needn't be dropped. */
static int run(const char *const *extra_args)
{
	VecStr cmd = c_init(vec_str, {TAWCROOT_PROD_BIN});
	for (const char *const *p = extra_args; *p; p++) {
		vec_str_push(&cmd, *p);
	}
	cstr out = cstr_init();
	cstr err = cstr_init();
	int rc = run_subproc(cmd, (SubprocArgs){
		.stdout = &out, .stderr = &err
	});
	cstr_drop(&out);
	cstr_drop(&err);
	return rc;
}

test(prod_exec_static_exit42)
{
	const char *args[] = { "--exec", TAWCROOT_STATIC_EXIT42_BIN, NULL };
	test_int_eq(run(args), 42);
}

test(prod_exec_dynamic_exit42)
{
	const char *args[] = { "--exec", TAWCROOT_DYNAMIC_EXIT42_BIN, NULL };
	test_int_eq(run(args), 42);
}

/* Regression: GCC `-fstack-clash-protection` page-probes a function's
 * frame as the prologue grows %rsp. coreutils 9.11 wc compiles its
 * read buffer (256 KiB) onto the stack frame, so the probe loop walks
 * 64+ pages before main work begins. Earlier the loader mmap'd a 256
 * KiB guest stack and the probe stepped one page past the bottom →
 * SEGV_MAPERR at exactly %rsp. The bin allocates 380 KiB > the old
 * 256 KiB stack, < the current 8 MiB stack, and touches every page;
 * exits 42 on success. If the loader stack is bumped down again, this
 * test reports the wait-status as -11 (or similar) instead of 42. */
test(prod_exec_dynamic_big_stack)
{
	const char *args[] = { "--exec", TAWCROOT_DYNAMIC_BIG_STACK_BIN, NULL };
	test_int_eq(run(args), 42);
}

test(prod_exec_system_bin_true)
{
	const char *args[] = { "--exec", "/bin/true", NULL };
	test_int_eq(run(args), 0);
}

test(prod_exec_no_args_prints_usage)
{
	const char *args[] = { NULL };
	test_int_eq(run(args), 2);
}

test(prod_exec_missing_path_prints_usage)
{
	/* Only `--exec` with no path → usage */
	const char *args[] = { "--exec", NULL };
	test_int_eq(run(args), 2);
}

test(prod_exec_nonexistent_guest_fails_clean)
{
	const char *args[] = {
		"--exec", "/this/path/does/not/exist/promise", NULL,
	};
	/* loader_exec.h: code 60 = open guest failed. */
	test_int_eq(run(args), 60);
}
