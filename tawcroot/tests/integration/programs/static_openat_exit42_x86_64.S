/* Static x86_64 fixture: openat(/etc/probe), exit_group(42 if ok, else 50+errno).
 * No glibc, no ld.so — just one trapped syscall (openat is in our trap set)
 * to exercise the SIGSYS-handler dispatch from inside a manual-loaded guest.
 * Used by phase-2g multi-process diagnostics: if static_execve_exit42 works
 * but this fails when fork+execve'd, the bug is in handler dispatch from a
 * post-re-exec guest, not in glibc/ld.so.
 */
	.text
	.globl _start
	.type  _start, @function
_start:
	mov     $257, %rax              /* SYS_openat */
	mov     $-100, %rdi             /* AT_FDCWD */
	movabs  $path, %rsi
	xor     %rdx, %rdx              /* O_RDONLY */
	xor     %r10, %r10
	syscall

	/* Encode: rv >= 0  → exit 42 ; rv < 0 → exit 50 + min(errno, 49). */
	cmp     $0, %rax
	jl      .Lneg
	mov     $42, %rdi
	jmp     .Lexit
.Lneg:
	neg     %rax
	cmp     $49, %rax
	jbe     .Lerrno_ok
	mov     $49, %rax
.Lerrno_ok:
	add     $50, %rax
	mov     %rax, %rdi
.Lexit:
	mov     $231, %rax              /* exit_group */
	syscall
	ud2

	.section .rodata
path:
	.asciz  "/etc/probe"
	.section .note.GNU-stack,"",@progbits
