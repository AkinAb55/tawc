# tawcroot: `tawc_loader_seg_layout` records `p_align` then discards it

`tawc_loader_seg_layout` accepts `p_align` as a parameter, stores
it on the segment record, and then `(void)p_align`s it — the
layout math doesn't use the value. So either:

- the parameter is dead and should be removed from the signature,
  or
- the layout math should validate it (`p_align >= page_size`,
  `p_align` is a power of two) and use it where alignment matters.

## Why it could matter later

`p_align` from a phdr can in principle exceed page size (huge
pages, vector-aligned BSS). The current code lays out segments
on page boundaries unconditionally; an ELF that requests larger
alignment will load but its segments won't actually be aligned
the way the linker intended. Today's targets all use page-sized
alignment, so this is inert.

## Fix options

1. **Drop the parameter.** Cleanest if we don't intend to honor
   `p_align`. Update callers to stop passing it.
2. **Validate and honor.** Reject phdrs with `p_align <
   page_size` or non-power-of-two `p_align`; round segment base
   up to `p_align` instead of page size.

Picking depends on whether we want to support oversized
alignment. For tawcroot's targets today, option 1 is fine.

## Severity

Cleanup. No correctness impact today.
