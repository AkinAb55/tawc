# tawcroot: AF_UNIX translation covers bind/connect/accept only — sendto/sendmsg and address read-back are untranslated

`syscalls_socket.c` translates `sun_path` for `bind`/`connect`/
`accept`, but:

- **`sendto` / `sendmsg` with a `msg_name` sockaddr_un** go to the
  kernel untranslated. Connectionless AF_UNIX clients pass the path
  there — glibc `syslog(3)` to `/dev/log`, sd_notify
  (`NOTIFY_SOCKET`) clients, some DNS helpers. Exactly the failure
  mode this file fixed for bind/connect, one syscall over.
- **No reverse translation for returned addresses**:
  `getsockname` / `getpeername` / `accept4`'s addr out-params hand the
  guest the HOST `sun_path`
  (`/data/data/.../rootfs/root/.gnupg/S.gpg-agent`). A server that
  re-publishes its bound path advertises a path that doesn't exist in
  the guest view; also a host-path information leak, which
  §"Translation rules" tries to avoid elsewhere (getcwd, maps).

`recvmsg`'s `msg_name` (datagram source address) has the same
reverse-translation need.

## Fix shape

sendto/sendmsg: same translate-into-local-sockaddr dance as
handle_connect (sendmsg needs the `msghdr` copied and its `msg_name`
repointed at a translated local copy). Reverse: share the
host-prefix → guest-abs logic with `tawcroot_fd_to_guest_abs`,
truncating to 107 bytes (or ENAMETOOLONG... kernel truncates, match
kernel).

## Severity

Medium: `/dev/log` datagram syslog is common in daemons; failures are
silent (messages vanish or go to a host path).
