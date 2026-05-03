# tawcroot: `/proc/self/maps` shows host paths to guest binaries

After the in-process loader places the guest binary, ld.so, and
its libraries, `/proc/self/maps` lists them with their **host**
paths (e.g. `<rootfs>/lib64/ld-linux-…`, with `<rootfs>` being
the actual on-disk app-private path). Programs that grep their
own maps see paths that don't exist in their guest world view.

proot wraps `read()` on `/proc/self/maps` to rewrite paths.
tawcroot doesn't. The plan section "Designed for expansion"
lists this as "expand on demand."

## When this surfaces

- Sandboxes that compute "where did my libc load from" by
  scanning their own maps.
- Crash handlers that include map info in their dumps (Mozilla
  has one; whether it surfaces user-visible confusion depends
  on whether it parses paths or just records them).
- Tracers / profilers that resolve symbols via `/proc/self/maps`.

Today's workloads (Wayland desktop apps, Firefox basic
operation, pacman) don't appear to trip on this, but the failure
mode would be subtle (silent symbol-resolution drift, not a
crash).

## Fix sketch

Trap `openat` on the fd-resolved path `/proc/self/maps` (and
`/proc/<our-tid>/maps`) and return a shadow fd backed by a
buffered rewrite of the kernel's output. Rewrite each path-
bearing line by reverse-translating the host path back to the
guest-visible path via the bind table.

Same approach extends to `/proc/self/exe` reads (already a
similar concern) and `/proc/<pid>/auxv` if AT_EXECFN matters to
a workload.

## Severity

Deferred. Pick up when Firefox or a sandboxed app actually
surfaces confusion about its own library locations.
