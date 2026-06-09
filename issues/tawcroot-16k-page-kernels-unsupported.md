# tawcroot: loader hardcodes 4096-byte pages — broken on 16K-page kernels

`tawcroot_loader_exec` uses `const size_t PAGE = 4096` for parsing,
mapping, and `AT_PAGESZ`, and `capture_host_auxv` (main.c) doesn't
read the kernel's `AT_PAGESZ` at all (notes/tawcroot.md explicitly
says to inherit it). On a 16 KB-page kernel (Android 15+ devices are
moving there; Pixel emulator images already offer it):

- file-backed `mmap` with 4 K-aligned (not 16 K-aligned) offsets
  returns `-EINVAL`;
- `MAP_FIXED*` at 4 K-aligned addresses fails or rounds;
- the synthesized `AT_PAGESZ=4096` lies to the guest's malloc/ld.so.

Also: ELF segments aligned to 4 K but not 16 K (common for arm64
binaries built with older toolchains) cannot be directly mmapped on
16 K kernels at all — the kernel's own loader rejects them too, but
the rootfs distros are moving to 16 K-aligned binaries, so tawcroot
just needs to not be the extra blocker.

## Fix shape

1. Read `AT_PAGESZ` in `capture_host_auxv` / `--exec-child` and
   plumb it through `tawcroot_loader_set_host_auxv`.
2. Use it for `parse_image`, `tawc_loader_map`, the stack guard page,
   and the synthesized `at_pagesz`.
3. `tawc_loader_seg_layout`/`parse_phdrs` already take `page_size`
   parameters — only the call sites hardcode 4096. The unit suite has
   a `seg_layout_16k_page` geometry test; extend the mapper tests to
   run the real-ELF cases at 16 K too (host kernel permitting, the
   pure-geometry parts don't need a 16 K kernel).

## Severity

Medium today (no 16 K device in the test pool), high the day one
arrives — nothing will load.
