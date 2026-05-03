/* Phase-1 smoke driver -- entry from main.c when invoked with `-r <rootfs>`.
 *
 * Sets up dispatch table + filter, then runs a small set of inline-asm
 * syscall probes against an actual rootfs tree on disk. Lets us
 * validate the dispatch + path-translation contract without yet having
 * a manual ELF loader to launch a real guest.
 *
 * Returns 0 on success / 1 on any failure (number of failures printed).
 */

#pragma once

int tawcroot_phase1_main(const char *rootfs);

/* argv-aware variant: also collects `-b src:dst` entries before running
 * the smoke. */
int tawcroot_phase1_main_argv(int argc, char **argv, const char *rootfs);
