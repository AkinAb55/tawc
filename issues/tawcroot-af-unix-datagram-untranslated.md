# tawcroot: AF_UNIX recvmsg/recvfrom source address still untranslated

`syscalls_socket.c` now translates `sun_path` for bind/connect/sendto/
sendmsg (forward) and reverse-translates getsockname/getpeername/
accept/accept4 out-param addresses. Remaining:

- **`recvmsg` `msg_name` / `recvfrom` `src_addr`**: the datagram source
  address the kernel writes back still holds the HOST `sun_path` for an
  AF_UNIX peer that bound a filesystem path. Same reverse-translation
  need as getpeername; lower value (few consumers read the datagram
  source as a path). Would reuse `tawcroot_host_path_to_guest_abs` the
  same way `getname_with_reverse` does, but recvmsg has to reach into
  the guest `msghdr` to find msg_name, so it needs its own handler.

## Severity

Low. The common connectionless senders (glibc `syslog(3)` to /dev/log,
sd_notify) are senders, not source-address readers, and are covered by
the sendto/sendmsg forward translation.
