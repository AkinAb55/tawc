/* Filesystem syscall handlers — phase 1. See syscalls_fs.c. */

#pragma once

/* Register the path-bearing fs handler set in the dispatch table.
 * Called from tawcroot_dispatch_init. */
void tawcroot_fs_register(void);

struct statx;

/* STATX_MNT_ID emulation for pre-5.8 kernels (see syscalls_fs.c for
 * the full story): when `req_mask` asks for a mount id and `sx` lacks
 * one, fill stx_mnt_id from /proc/self/fdinfo of the target at
 * (dirfd, path) — empty/NULL path means dirfd itself. Best-effort;
 * on failure `sx` is unchanged. Also used by the shm statx
 * synthesizers and the hosted parser test. */
void tawcroot_statx_fill_mnt_id(int dirfd, const char *path,
				unsigned int req_mask, struct statx *sx);
