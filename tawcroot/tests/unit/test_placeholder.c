/* Placeholder unit-test module.
 *
 * Real unit tests land here once we extract pure functions out of
 * tawcroot/src/ -- first candidates: `path_translate` (currently tangled
 * into path.c alongside `tawc_*` raw-syscall calls), the BPF program
 * generator in filter.c, and bind-table longest-prefix match. Until
 * those are extracted, this file just verifies the cleat runner is
 * wired up correctly.
 *
 * cleat / STC / `nullptr` / `[[maybe_unused]]` are fine here -- this is
 * the host-only test orchestrator, never linked into `libtawcroot.so`.
 * See notes/tawcroot.md "Testing strategy".
 */

#include <cleat/test.h>

test(cleat_runner_is_alive)
{
	test_int_eq(2 + 2, 4);
	test_str_eq("foo", "foo");
}
