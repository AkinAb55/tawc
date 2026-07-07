# tawcroot: AF_UNIX sun_path budget fix needs device verification

The 108-byte sun_path overflow under long install prefixes is fixed
on host: `syscalls_socket.c` now falls back from the host-absolute
rendering to `/proc/self/fd/<base_fd>/<suffix>`, then to an O_PATH
parent-dir anchor `/proc/self/fd/<parent_fd>/<leaf>`, with bind's
parent fd reserved so getsockname/getpeername reverse-translate.
Design + degradations: notes/tawcroot/path-translation.md §AF_UNIX.
Covered by hosted tests (test_socket_handlers.c) and a deep-prefix
production integration test (prod_unix_bind_over_budget_sun_path);
full host suite green.

Remaining (needs a device target; `.tawctarget` was `none` at fix
time):

- Verify on a physical device from app context: bind/connect a guest
  socket path long enough to overflow tier 1 under the real
  `/data/data/me.phie.tawc/distros/<id>/rootfs` prefix.
- Specifically watch for the app-context quirk from the pacman-key
  fix (commit 027ba5e): that device combo returned ENOENT for AF_UNIX
  bind/connect via `/proc/self/fd/N/...` even though the same path
  worked for stat/open. If it still does, over-budget paths keep
  failing there (ENOENT instead of ENAMETOOLONG; under-budget paths
  are unaffected on tier 1) and the fallback needs a different
  mechanism (e.g. supervisor-assisted bind with SCM_RIGHTS fd
  passing).

Then delete this issue.
