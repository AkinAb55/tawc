# tawcroot virtual identity (stateful fake uid/gid)

Goal: a stock `openssh-server` in an unmodified rootfs works under
tawcroot — key-auth root login, exec and PTY sessions, `UsePAM yes` —
and, more generally, any daemon that drops privileges and *verifies the
drop* behaves correctly. The mechanism is replacing the stateless
fake-root identity (`get*id` → constant 0, `set*id` → constant success)
with a tracked virtual identity, the way proot's `fake_id0` extension
does.

## Current state (July 2026)

Two of the three sshd blockers are already fixed in tawcroot proper:

- `socket(AF_NETLINK, …, NETLINK_AUDIT)` → `-EPROTONOSUPPORT`
  (syscalls_socket.c), so libpam/sshd see an audit-less kernel instead
  of Android's SELinux `EACCES`. Fixed `su`/PAM rootfs-wide.
- chmod-family host `EPERM`/`EACCES` swallowed under fake root
  (handle_fchmodat in syscalls_fs.c), so sshd's `pty_setowner()` chmod
  on `/dev/pts/N` no longer kills TTY logins.

The remaining blocker, reproducible on the emulator Debian rootfs
(`/usr/sbin/sshd -p 2222 -E /tmp/sshd.log`, key in the session
scratchpad; host keys and `/root/.ssh/authorized_keys` are already set
up there):

    permanently_set_uid: was able to restore old [e]gid [preauth]

OpenSSH's `permanently_set_uid()` (uidswap.c) drops the preauth process
to the `sshd` user via `setresgid`/`setgroups`/`setresuid`, then
*verifies irreversibility*: it attempts `setgid(old)`/`setegid(old)`
(and the uid equivalents) and `fatal()`s if any succeeds, and checks
`getgid()`/`getuid()` now report the target user. Stateless fakes fail
both ways: the restore "succeeds" and the getters still say 0.

Two guest-side workarounds were prototyped and then deliberately
reverted (privsep user remapped to uid 0 in `/etc/passwd`; `tty` group
deleted so pty_setowner skips chown/chmod). They worked but were
per-rootfs mutations; this plan replaces them.

## Design

### State

One process-wide identity struct in tawcroot globals:

    struct tawc_identity {
        uint32_t ruid, euid, suid, fsuid;
        uint32_t rgid, egid, sgid, fsgid;
        uint32_t ngroups;
        uint32_t groups[TAWC_IDENTITY_NGROUPS];  /* 32 */
    };

Init: all ids 0, `ngroups = 1, groups = {0}` (root-like, matches
today's illusion). Storage follows the mutable-globals rule from
notes/tawcroot.md §"Threading and vfork invariants": fixed-size
double-buffered snapshots + atomic sequence counter, exactly the
signal-shadow pattern (`signal_shadow.c`, torn-free test
`action_multithread_seqlock_is_torn_free`). Writers are only the
trapped `set*id` syscalls; readers are `get*id` handlers and the
fake-decoration paths. POSIX says identity is process-wide (glibc
broadcasts set*id to all threads); a single process-wide struct is
therefore the *correct* semantic, not an approximation. vfork: a
vfork child that calls set*id before exec would corrupt the parent's
view — same bounded-failure stance as the chroot globals; no known
workload does this.

`fork` inheritance is free (address-space copy). `execve` is not: the
identity struct must be serialized into the exec-state fd
(exec_state.c — versioned, length-prefixed; bump the version) and
restored by `--exec-child` before the manual-load jump.

### Privilege predicate

privileged ⇔ `euid == 0`. (The kernel actually checks CAP_SETUID/
CAP_SETGID, but under fake root "root has all caps, others none" is
the whole capability model. Do not model capabilities further.)

### Syscall rules (Linux semantics, applied to virtual state)

All in identity.c, replacing the `fake_zero` installs. `-1` means
"keep" where the kernel accepts it. On every euid change, fsuid
follows euid (same for gids).

- `setuid(u)`: privileged → ruid=euid=suid=u. Unprivileged → allowed
  iff `u ∈ {ruid, suid}`, sets euid only. Else `-EPERM`.
- `setreuid(r, e)`: unprivileged → `r ∈ {ruid, euid}`,
  `e ∈ {ruid, euid, suid}`. After applying: if `r != -1` or
  (`e != -1` and `e != old ruid`), then `suid = new euid` (the
  saved-id update rule OpenSSH implicitly relies on). Privileged →
  anything.
- `setresuid(r, e, s)`: unprivileged → each of r/e/s `∈ {ruid, euid,
  suid}` or -1. Privileged → anything.
- `setfsuid(f)`: **returns the previous fsuid, never an error.**
  Applies iff privileged or `f ∈ {ruid, euid, suid, fsuid}`.
- gid family: identical rules with gids, gated on the *uid* privilege
  predicate (kernel: CAP_SETGID; our model: euid==0).
- `setgroups(n, list)`: privileged only, else `-EPERM`. `n >
  TAWC_IDENTITY_NGROUPS` → `-ENOMEM` (kernel-plausible; sshd/su use a
  handful). Copy through the guarded guest-copy helpers.
- `getuid/geteuid/getgid/getegid`: report tracked values.
- `getresuid/getresgid`: write tracked triples (keep the existing
  EFAULT-safe copy shape).
- `getgroups(n, list)`: **new trap** (currently passes through and
  leaks Android gids — 3003/9997/… in `id` output today). Answer from
  the shadow: n==0 → return ngroups; n < ngroups → `-EINVAL`; else
  copy. Syscall numbers: aarch64 158, x86_64 115.

What sshd needs, concretely, as the implementation checklist: after
`setresgid(994…)+setgroups+setresuid(994…)` from euid 0, the process
must get `-EPERM` from `setgid(0)`, `setresgid(-1,0,-1)`, `setuid(0)`,
`setresuid(-1,0,-1)`, and must see `getuid()==getgid()==994/…` — while
the monitor (which never dropped) can still `setresuid(0,0,0)` for the
post-auth session.

### Interactions to keep consistent

- **chown/chmod fakes become privilege-gated.** handle_fchownat /
  handle_fchown / handle_fchmodat currently fake success
  unconditionally; once identity is stateful, fake only when
  `euid == 0`, otherwise return the host result unmodified. A
  genuinely-dropped process should see real permission errors.
- **stat decoration stays as-is** (rootfs files appear root-owned).
  A dropped process can still *open* anything the app uid can — same
  divergence proot has; enforcement is a non-goal.
- **/proc/self/status**: audit proc_shadow.c; if `status` is
  synthesized, source its `Uid:`/`Gid:`/`Groups:` lines from the
  identity state. If it passes through, leave it (divergence exists
  today) and note it in notes/tawcroot.md.
- **plans/tawcroot-future-work.md perf item 4** ("return fake identity
  directly from BPF via `SECCOMP_RET_ERRNO|0`") conflicts with
  stateful identity — BPF cannot return dynamic values. Drop that
  item, or scope it to "only until the first set*id call" (not worth
  the complexity; recommend dropping when this lands).

### Out of scope (bounded-ness)

- No privilege *enforcement* — identity is cosmetic-consistent, file
  access remains whatever the app uid can do.
- No setuid-bit honoring on exec (`sudo`/`su` from a dropped process
  cannot re-elevate; same as proot).
- No capget/capset modeling beyond the euid predicate.
- No per-user NSS awareness; ids are numbers.
- fd-based `fchmod` stays untrapped (kernel resolves the fd itself;
  Android denials surface as real errors). Revisit trigger: a
  workload fatals on fchmod of a foreign node the way sshd did on
  fchmodat.

## Tests

Extend `rootfs_smoke.c` (host + device, same binary):

- Drop-then-restore: `setresuid(994,994,994)` from 0 → 0; then
  `setuid(0)` → `-EPERM`, `setresuid(-1,0,-1)` → `-EPERM`, `getuid()`
  → 994. Same for gids (drop gids *before* uid, like sshd does).
- `setreuid` saved-id rule: `setreuid(1000, 1000)` from 0, then
  verify `getresuid` reports suid==1000.
- `setfsuid`: returns previous fsuid; unprivileged setfsuid to an
  unrelated id is a silent no-op (returns previous, state unchanged).
- `setgroups` from dropped euid → `-EPERM`; `getgroups` round-trips
  the shadow; initial shadow is `{0}`.
- Identity survives `execve` (mirror an existing exec-persistence
  test in the exec suite: set ids, exec the testhost child step,
  child asserts getuid).
- Multithread: concurrent `getresuid` during a `setresuid` loop never
  observes a torn triple (copy the signal-shadow seqlock test shape).
- Existing `test_identity_setid_family_fakes_success` is *replaced*
  by rule-accurate expectations (its "setuid(0) from root → 0" cases
  remain valid).
- Privilege-gated chmod/chown: after a drop, fchmodat of a root-owned
  bind target returns the real host error, not fake success.

## Acceptance

On the emulator Debian rootfs with **zero** guest modifications
(stock `/etc/passwd`, `sshd:x:994:65534`, stock `/etc/group` with
`tty:x:5:`, stock PAM config):

    scripts/rootfs-run.sh '/usr/sbin/sshd -p 2222 -E /tmp/sshd.log'
    adb forward tcp:2222 tcp:2222
    ssh -p 2222 -i <key> root@127.0.0.1 'id'      # exec channel
    ssh -tt -p 2222 -i <key> root@127.0.0.1       # PTY + shell

Both succeed with `UsePAM yes`; `/tmp/sshd.log` shows no
`permanently_set_uid`/`pam`/`chmod` complaints. Also: `su -s /bin/sh
nobody -c id` prints nobody's ids, and a subsequent `su root` inside
that shell fails instead of silently succeeding.

Size estimate: ~250 line identity.c rewrite + ~30 lines exec_state +
~150 lines of smoke steps. No new BPF machinery (dispatch-driven
filter picks up `getgroups` automatically).
