# tawcroot: cwd inside a bind makes getcwd and all relative paths fail ENOENT

`handle_chdir("/system")` happily `fchdir`s to the bind src fd, leaving
the kernel cwd at the host bind-src path. But both reverse
translations of the cwd only match the ROOTFS host prefix:

- `handle_getcwd` (`syscalls_fs.c`): `host_len <
  tawcroot_rootfs_host_path_len → ENOENT`
- `prod_cwd_to_guest_abs` (`path.c`): same prefix check

Neither walks the bind table — unlike `tawcroot_fd_to_guest_abs`,
which DOES reverse-translate bind srcs for dirfds.

Consequence: after `cd /system` (or any `-b src:dst` dst), `getcwd()`
returns ENOENT and EVERY relative-path syscall (which routes through
`prod_cwd_to_guest_abs`) fails ENOENT. `cd /system && ls` is dead.
chdir succeeding into a state where the process's relative-path world
is broken makes this worse than refusing the chdir.

## Fix shape

Give both functions the same bind-table longest-prefix walk that
`tawcroot_fd_to_guest_abs` has (factor the matching out of
`fd_to_guest_abs` — it's the identical host-path → guest-abs
problem; `handle_getcwd` could simply call `prod_cwd_to_guest_abs`
or both could call one shared helper).

## Repro

Device/emulator guest shell: `cd /system; pwd` → "pwd: ENOENT";
`ls` → ENOENT. Host integration test: rootfs + `-b /tmp/somedir:dir`,
fixture that chdir("/dir") then getcwd() and open("relative-file")
(needs a new .S fixture — none of the existing ones chdir).

## Severity

High within bind use: complete loss of relative-path resolution after
an ordinary `cd`.
