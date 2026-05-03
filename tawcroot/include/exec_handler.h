/* SIGSYS-handler-side execve interception.
 *
 * Catches the guest's `execve` / `execveat`, validates the binary,
 * writes the request into a memfd, and re-execs tawcroot itself with
 * `--exec-child <fd>`. From the guest's point of view the syscall
 * succeeds and a new program is running, exactly like a kernel-
 * driven execve — except the new tawcroot process inherits our
 * SIGSYS handler chain (we re-install it in `--exec-child` after
 * the kernel-execve resets handlers to SIG_DFL).
 *
 * Why we can't let the guest's execve go through:
 * - The kernel resets every signal handler to SIG_DFL on a successful
 *   exec (`man 2 execve`). The seccomp filter is preserved (filters
 *   ride along), so the new program runs with our trap set installed
 *   but no SIGSYS handler.
 * - The new program's first path-bearing syscall traps to SIGSYS
 *   with disposition SIG_DFL → kernel kills the program.
 *
 * This file's contract is:
 *
 *   `tawcroot_exec_handler_perform(path, argc, argv, envp)`
 *     - path: rootfs-translated, absolute on the host filesystem
 *       (caller is responsible for path translation).
 *     - argv: NULL-terminated guest-supplied argv.
 *     - envp: NULL-terminated guest-supplied envp.
 *     - Validates path is openable (open + readable).
 *     - Creates non-CLOEXEC memfd, writes exec_state.
 *     - Opens /proc/self/exe.
 *     - execveat-s self with `--exec-child <fdstr>`.
 *     - On success: never returns (control transfers to the new
 *       tawcroot incarnation).
 *     - On failure: returns -errno. Caller (the SIGSYS handler) is
 *       expected to surface this back to the guest as the result of
 *       its `execve` syscall.
 *
 * The handler is async-signal-safe: every step uses raw_sys.h and
 * touches no globals beyond the function-local stack frame and the
 * memfd it creates.
 */

#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Perform the re-exec dance. See module header.
 *
 * `path` is the host-fs path the guest wanted to exec (post-rootfs-
 * translation). `argv` and `envp` are NULL-terminated arrays of
 * NUL-terminated strings (same shape as argv/envp passed to execve).
 *
 * Returns -errno on any pre-execveat failure:
 *   -EACCES   couldn't open path
 *   -EFAULT   memfd creation / write failed
 *   -ENOMEM   exec_state too large for any memfd we'd want to use
 *   -ENOEXEC  /proc/self/exe couldn't be opened
 *   anything else from execveat itself (rare; usually just doesn't
 *   return)
 *
 * Never returns 0 — either the dance succeeds and control transfers
 * to the new tawcroot, or we return a negative errno. */
long tawcroot_exec_handler_perform(const char *path, int argc,
                                   const char *const *argv,
                                   const char *const *envp);

#ifdef __cplusplus
}
#endif
