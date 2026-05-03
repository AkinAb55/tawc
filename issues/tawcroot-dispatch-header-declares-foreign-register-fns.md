# tawcroot: `dispatch.h` declares per-subsystem `register` fns instead of letting subsystems own them

`dispatch.h` currently declares `tawcroot_*_register` for each
subsystem (path, exec, fs, control, …). That's backwards: each
subsystem should declare its own register fn in its own header,
and `dispatch.c` should `#include` those headers to wire them up.

The current shape creates a circular-header smell: `dispatch.h`
needs to know about every subsystem, and adding a new subsystem
means editing `dispatch.h` instead of just dropping in a new
file with its own header.

## Fix

For each subsystem (e.g. `syscalls_path.c`):

1. Add a `tawcroot_path_register(...)` declaration to its own
   header (`syscalls_path.h`) — or a new one if it doesn't have
   one.
2. Remove the corresponding declaration from `dispatch.h`.
3. In `dispatch.c`, include each subsystem's header and call its
   register fn.

## Severity

Cleanup / structure. Cosmetic today; pays off when adding the
next subsystem.
