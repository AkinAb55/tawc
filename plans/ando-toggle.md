# Per-distro ando toggle

Make ando (notes/ando.md) a per-distro setting: configurable at install
time and editable later, default **disabled**. When disabled, a guest
must not be able to use ando through any supported access path — the
CLI is just one client of the socket, so removing `/usr/local/bin/ando`
alone would be theater. The CLI stays installed either way; when
disabled it fails with a clear error telling the user how to enable
ando.

## Why the current shape can't be gated

- The capability **is** the socket. Any guest process can `connect(2)`
  to `/usr/share/tawc/ando.sock` and speak the (documented) protocol;
  the CLI adds nothing the socket doesn't grant.
- The socket is one global node in `<appData>/share/`, and that dir is
  bind-mounted into **every** rootfs as `/usr/share/tawc` (it also
  carries the wayland/kumquat sockets, which must stay shared).
- The broker's only auth is `SO_PEERCRED` uid == app uid. Every guest
  of every distro is the app uid, so the broker cannot tell distros
  apart at accept time — there is nothing per-distro to check against.

So a per-distro toggle needs two structural changes: the socket must
become per-distro, and its reachability from a guest must be governed
by that distro's bind table.

## Design

Two independent layers, both keyed on the new setting:

1. **No listener**: the broker only listens on sockets of
   ando-enabled distros. A disabled distro has no socket node and no
   accepting listener anywhere.
2. **No path**: the per-distro socket is reachable only through a
   per-distro bind that is included in the spawn's bind table only
   when the setting is enabled. A disabled guest has no path that
   translates to *any* ando socket — its own or another distro's.

Either layer alone stops the direct-socket path; together they make
the failure modes independent (a bind-table bug still hits a closed
listener, a listener-lifecycle bug still has no reachable path).

### Setting

`Installation.andoEnabled: Boolean`, default `false`; an absent field
in legacy `metadata.json` also parses as `false` (no schema bump —
additive field with safe default). Existing installs therefore lose
ando on upgrade until the user flips the toggle — intended: opt-in,
fail-closed. Applies to all install methods, all build types.

Wherever the toggle appears (InstallActivity, DistroInfoActivity), it
carries a shared one-line description string, roughly: *"ando allows
running Android commands outside the Linux environment."*

### Socket relocation

- Host: `<appData>/distros/<id>/ando/ando.sock` — sibling of
  `rootfs/`, so uninstall removes it naturally, and crucially
  **outside any wholesale-bound dir** (the share dir is bound into
  every rootfs; per-distro sockets must not live under it or every
  guest can enumerate them all).
- Guest: bind host dir `distros/<id>/ando/` → guest
  `/usr/share/tawc/ando/`; client default becomes
  `/usr/share/tawc/ando/ando.sock`. Dir-level (not file-level) so the
  broker's unlink+rebind on app restart stays reachable under chroot's
  real mounts, and so the translation is one entry.
- The bind is emitted by each method's bind builder (tawcroot
  `bindSpecs()`, proot equivalent, `ChrootMounter`) **only when
  `andoEnabled`**, read fresh from `metadata.json` per spawn exactly
  like `externalBindsFor()` does today.
- `<appData>/share/ando.sock` goes away; app startup unlinks any stale
  node from older versions.

**Checkpoint:** the guest dir nests inside the share bind
(`/usr/share/tawc`). Verify tawcroot/proot translation gives the more
specific prefix precedence (or order the built-in bind list so the
ando entry wins); if nesting is awkward, fall back to a non-nested
guest path (e.g. `/run/tawc-ando/`) — nothing depends on the exact
path since we ship both ends.

### Broker (compositor/src/ando.rs)

Replace `start(path)` with a declarative sync API — roughly
`nativeSyncAndoBrokers(ids: [String], paths: [String])`:

- Ensure exactly one listener per (id, path) in the set; start
  missing ones (mkdir + unlink stale + bind), stop removed ones.
- Stopping a listener: close it, unlink the socket node, and SIGKILL
  the pgids of in-flight children spawned via that listener (each
  connection thread already knows its child pgid; reuse the
  disconnect-kill path). Disable means disabled, not
  "drains eventually".
- Per-connection handling, protocol, peercred check are unchanged.
  The socket a connection arrived on *is* the distro identity; no
  protocol change, no client handshake change, no tawcroot C changes.

### Kotlin lifecycle wiring

New small object (say `AndoBrokers.refresh(context)`): list installs,
filter `andoEnabled` (and dir exists), call the native sync. Called
from:

- `TawcApplication.onCreate` startup thread (replaces the current
  `nativeStartAndoBroker` call; same lazy-`System.loadLibrary`
  placement).
- `Installer` right after the initial metadata write (so ando is live
  during install/first boot, matching binds), and on uninstall.
- The toggle commit paths below.

### Install-time configuration

Follow the `externalBinds` chain end to end: `InstallActivity`
checkbox ("Allow running Android commands (ando)" plus the shared
description line, default **off**, shown for all methods) → intent
extra → `InstallationService.startInstall` arg → `Installer` →
initial metadata. Exec-broker install action gains
`--arg ando=true|false` in `InstallActions` (default false).

### Post-install configuration

Toggle row on `DistroInfoActivity` with the shared description line,
state-gated to READY/FAILED like `ManageBindsActivity.commit()`
(avoid racing service writes), read-modify-write through
`InstallationStore`, then `AndoBrokers.refresh()`.

Take-effect semantics (document in notes/ando.md):

- **Disable: immediate.** Listener closed, socket unlinked, in-flight
  ando children killed. Already-running guest processes keep the
  (now-dead) path in their bind table; connects fail.
- **Enable: next spawn.** The listener comes up immediately, but a
  session spawned while disabled has no bind entry; processes started
  after the toggle get it. Same "per-spawn" semantics binds already
  have.

### Client

`/usr/local/bin/ando` stays installed unconditionally
(`AndoInstallProvider` untouched) — the stamp machinery is global, the
setting is dynamic, and a present-but-refusing CLI is the error-message
surface. Update `SOCKET_DEFAULT` to the new path, and on connect
ENOENT/ECONNREFUSED print a clear multi-line error before the existing
exit 127, roughly:

```
ando: cannot reach the ando broker — ando is disabled for this distro.
ando allows running Android commands outside the Linux environment.
Enable it in the tawc app (distro settings), then open a new
terminal/session.
```

(The "new terminal" part matters because enable only takes effect on
the next spawn.) `TAWC_ANDO_SOCKET`/`TAWC_ANDO_SU` hooks are
client-side only and grant nothing; unchanged.

## Threat model / honesty

- This closes every **supported** access path: CLI, hand-rolled
  socket clients, and cross-distro socket reach (no bind covers
  another distro's `distros/<id>/ando/`; under chroot host paths
  aren't visible at all).
- It is **not** a boundary against a same-uid process that escapes
  the virtualization layer. tawcroot/proot are not sandboxes; an
  escaped guest is the app uid and could equally ptrace the app or
  kill it. That is the same stance notes/ando.md already takes, and
  no per-distro design can fix it — only real process isolation
  could.
- Audit note: ando's socket is the only guest→Android exec surface.
  The debug ExecBroker is not guest-reachable (peercred gate is
  {0, 2000}; guests are 10xxx) and debug-only; wayland/kumquat
  sockets carry no exec capability. Implementation should re-grep
  rootfs defaults (ShellDefaults etc.) for ando references to keep
  disabled-distro UX clean.

## Rejected alternatives

- **Remove the CLI when disabled** — socket still reachable;
  explicitly the non-goal.
- **Single socket + identify distro from peercred pid** (via
  `/proc/<pid>/cwd`/`root`) — unreliable (no chroot under
  tawcroot/proot, cwd is attacker-chosen) and racy.
- **Token handshake** (per-distro secret bound only into enabled
  rootfses) — equivalent power to per-distro sockets with more
  protocol; sockets-as-identity is simpler and needs no client
  change beyond the path.
- **Global toggle in `Settings`** — doesn't meet the per-distro
  requirement.

## Tests

Persistent-state rule: integration tests must not durably mutate
fixture metadata (tests commit 71c8734). Mirror the
`Settings.enterTestMode()` pattern: an in-memory per-id override in
`InstallationStore` (or a thin layer above it) that test-mode
`set-ando` writes; spawn paths and `AndoBrokers.refresh()` read
through it; app-process death discards it. Broker actions:
`set-ando` (`installId`, `enabled`) + `get-ando`.

- Unit: `Installation` JSON round-trip with the field; legacy JSON
  (field absent) → `false`.
- Integration (tests/integration/tests/ando.rs additions; the
  existing ando suite needs its fixture flipped to enabled, via the
  install arg or test-mode `set-ando`):
  - disabled → `ando true` exits 127 with the enable-instructions
    error on stderr;
  - disabled → direct socket check: the guest path is absent/dead
    (e.g. `TAWC_ANDO_SOCKET=/usr/share/tawc/ando/ando.sock ando true`
    after disable, and a raw `connect` attempt);
  - disable kills an in-flight `ando sleep`-style child;
  - re-enable → next spawn works;
  - old shared path `/usr/share/tawc/ando.sock` no longer works even
    when enabled.
  - install-time: covered cheaply by asserting the `--arg ando=true`
    plumbing lands in metadata, and that an install without the arg
    defaults to disabled (avoid a full extra install if the suite's
    fixture budget doesn't allow one).

## Doc updates (same change as implementation)

- notes/ando.md: socket location, broker lifecycle (sync API,
  per-distro listeners), setting + take-effect semantics, security
  model paragraph.
- notes/exec-broker.md: new `set-ando`/`get-ando` rows, install
  action arg.
- This plan is deleted and folded into notes/ando.md when done.
