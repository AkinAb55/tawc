/* libc-free debug-print helpers.
 *
 * Used by everything outside the SIGSYS handler hot path. Inside the
 * handler this is forbidden: even `tawc_write` issues a syscall through
 * the stub, which is fine, but the rest of the design constrains the
 * handler to no allocations / no stdio. Use these freely from main /
 * --exec-child / rootfs init code; do not call them from handlers.
 *
 * Output goes to stdout (fd 1). No newline auto-append.
 */

#pragma once

#include <stddef.h>
#include <stdint.h>

void tawc_io_str(const char *s);
void tawc_io_hex(unsigned long v);
void tawc_io_dec(long v);
void tawc_io_kv_hex(const char *k, unsigned long v);
void tawc_io_kv_dec(const char *k, long v);

/* Print "[ok ] label\n" or "[FAIL] label\n"; return 0 if ok, 1 otherwise.
 * Used by the smoke drivers; unit tests use the same convention. */
int  tawc_io_step(const char *label, int ok);

/* Print "[skip] label (reason)\n". Used when the kernel lacks a syscall the
 * test exercises (e.g. faccessat2 on <5.8): the case is neither pass nor
 * fail, just N/A on this platform. The cleat parser registers these as
 * passing tests with the reason in the name so the run stays green. */
void tawc_io_skip(const char *label, const char *reason);

/* Tiny string utilities (libc-free). */
size_t tawc_strlen(const char *s);
int    tawc_streq(const char *a, const char *b);
int    tawc_starts_with(const char *s, const char *prefix);
long   tawc_parse_long(const char *s);
int    tawc_int_to_str(char *buf, size_t buflen, int v); /* returns length */
