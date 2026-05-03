/* End-to-end test of the SIGSYS-handler-side execve interception:
 * production tawcroot --exec-via-handler exercises
 * `tawcroot_exec_handler_perform()`, which builds an exec_state in a
 * memfd, opens /proc/self/exe, and execveats into us with
 * --exec-child <fd> — the same dance the real SIGSYS handler will
 * perform when it traps the guest's `execve(2)`.
 *
 * Success of these tests means front + back halves of the phase-2.6
 * dance are wired correctly. The remaining phase-2.6 work (hooking
 * the handler into the dispatch table for actual SIGSYS-driven
 * traps) is a small wrapper that reads guest memory via usercopy.c
 * and calls into this same code path.
 */

#include <cleat/test.h>
#include <cleat/subproc.h>
#include <stc/cstr.h>

#ifndef TAWCROOT_PROD_BIN
# error "TAWCROOT_PROD_BIN must be defined"
#endif
#ifndef TAWCROOT_STATIC_EXIT42_BIN
# error "TAWCROOT_STATIC_EXIT42_BIN must be defined"
#endif
#ifndef TAWCROOT_DYNAMIC_EXIT42_BIN
# error "TAWCROOT_DYNAMIC_EXIT42_BIN must be defined"
#endif

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

test(exec_via_handler_static_exit42)
{
	const char *args[] = { "--exec-via-handler",
	                       TAWCROOT_STATIC_EXIT42_BIN, NULL };
	test_int_eq(run(args), 42);
}

test(exec_via_handler_dynamic_exit42)
{
	const char *args[] = { "--exec-via-handler",
	                       TAWCROOT_DYNAMIC_EXIT42_BIN, NULL };
	test_int_eq(run(args), 42);
}

test(exec_via_handler_system_bin_true)
{
	const char *args[] = { "--exec-via-handler", "/bin/true", NULL };
	test_int_eq(run(args), 0);
}

test(exec_via_handler_nonexistent_returns_50)
{
	/* `--exec-via-handler` reports any handler-perform negative
	 * return code via main and exits 50. Probe of nonexistent path
	 * fails at the open() step inside the handler. */
	const char *args[] = {
		"--exec-via-handler",
		"/this/path/does/not/exist/promise", NULL,
	};
	test_int_eq(run(args), 50);
}
