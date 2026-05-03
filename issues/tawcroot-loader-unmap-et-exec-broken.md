# tawcroot: `tawc_loader_unmap` would unmap libtawcroot itself for ET_EXEC

`tawc_loader_unmap` (`tawcroot/src/loader_map.c:167-181`) frees a
loaded image by `munmap(out->base, out->span)`. For ET_DYN images
that's correct. For ET_EXEC, the loader currently sets
`out->base = 0` and `out->span = (uintptr_t)img->addr_max`, so
unmap would do `munmap(0, addr_max)` — wiping the entire low
half of the address space, including libtawcroot's own mappings.

## Why it hasn't blown up

No production caller invokes `tawc_loader_unmap` on the success
path of an ET_EXEC load (`tawcroot_loader_exec` exits the process
on failure rather than cleaning up). The only direct test —
`test_loader_map.c::map_real_self_exe_dyn` — only calls
`tawc_loader_unmap` on the success path for ET_DYN.

## Fix options

- Set `out->base` to the real low address of the image for
  ET_EXEC, so `munmap(out->base, out->span)` covers only the
  loaded segments. Simpler.
- Have `tawc_loader_unmap` walk `img->loads[]` and unmap each
  segment individually. More robust but requires keeping the
  segment list around.

## Severity

Medium, inert today. Becomes load-bearing the moment we add an
ET_EXEC test that exercises the success-path cleanup, or any
production code path that wants to unload-and-retry an image.
