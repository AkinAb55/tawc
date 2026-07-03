# Reduce per-test overhead in `tests/integration`

**Status:** design only, not implemented. Motivated by "are we burning
tens/hundreds of ms per test on setup that isn't the thing being tested?"

## Context / constraints

- The suite runs serial (`--test-threads=1`, `scripts/run-integration-tests.sh:369`),
  so every per-test millisecond is fully additive across ~all tests. Total
  wall-clock is the metric.
- Device access is already efficient: one compositor launch, one `adb forward`
  for the whole run (`run-integration-tests.sh:357`), broker channel reused per
  request. No per-test adb connection, no per-test build/install/push (all
  hash/stamp-gated). **The wins are in the Rust harness + test bodies, not the
  transport.**
- Goal: cut fixed sleeps and redundant/expensive device round-trips without
  weakening the isolation guarantees (`test_init`, `assert_compositor_clean`).

## Effort ordering

Do the cheap, mechanical, low-risk items first (2, 3, 4), then the
higher-payoff-but-careful sleep replacement (1), then the trivial constant (5).

1. **Items 2 + 3** — delete redundant calls; add one `OnceLock`. Immediate
   reduction in device fork-execs.
2. **Item 4** — mechanical `OnceLock` wrapping, isolated to `rootfs.rs`.
3. **Item 1** — highest payoff, needs care: replace each sleep with the right
   existing signal (`frames`, `rendered_toplevels`, `input-ready`). Do it
   helper-by-helper, measure, keep a small floor only where no discrete signal
   exists.
4. **Item 5** — trivial constant tweak, do alongside Item 1.

---

## Item 1 — Replace unconditional grace sleeps with condition waits (highest impact)

**Where:**
- `helpers.rs:420` — `thread::sleep(1000ms)` after first paint in `launch_and_wait_for_toplevel`.
- `helpers.rs:468` — `thread::sleep(1000ms)` after AHB import in `launch_and_wait_for_ahb`.
- `helpers.rs:512` — `thread::sleep(500ms)` grace in `assert_renders_via_shm`.
- Test-local: `apps.rs:99` (1s), `apps.rs:142` (1s), `libhybris.rs:384` (2s),
  `gfxstream.rs:205` (2s), `apps.rs:211/213/215` (250ms×3), `rendering.rs:89/136`
  (250ms), `cpu_graphics.rs:94` (500ms).

**What these sleeps are actually waiting for** (from the comments):
- `helpers.rs:420/468`: let the app finish opening its window / reach IM+shell
  readiness before the caller drives input or kills it.
- `helpers.rs:512`: catch a regression that only manifests on the *second* SHM commit.
- `libhybris.rs:384` / `gfxstream.rs:205`: Firefox settle.

**Approach:** key each wait off an observable in `CompositorState` (already
plumbed via `query-state`) instead of a wall-clock guess:
- "Second commit / still-progressing" → sample `frames` (`compositor.rs:14`
  field) and wait until it advances by N, reusing the exact mechanism
  `assert_client_animating` already uses (`helpers.rs:266-281`). Replaces
  `helpers.rs:512` and the "app finished opening" sleeps.
- "Window fully mapped" → wait for `rendered_toplevels >= 1` (already a field,
  `compositor.rs:17`; already have `wait_for_rendered_toplevels`,
  `compositor.rs:198`) rather than a flat 1s after first buffer.
- For input-driven tests (lxterminal etc., the reason `helpers.rs:420` exists):
  gate on `wait_for_active_input_connection` (`adb.rs:257`) / `input-ready`,
  which is the real precondition, instead of a 1s proxy.

**Expected saving:** ~0.5–2s per affected test; dozens of tests. Likely the
single biggest wall-clock reduction.

**Risk:** these sleeps sometimes paper over races the author couldn't otherwise
express. Keep a small floor where a specific second-event signal doesn't exist,
but prefer the `frames`/`rendered_toplevels` signals that already exist.

---

## Item 2 — Remove redundant `require_compositor()` after `test_init()`

**Where:** `cpu_graphics.rs:38-39`, `libhybris.rs:162-163` and `:208-209`,
`libhybris_zink.rs:48-49` and `:87-88`, `gfxstream.rs:55-56`.

**Why:** `helpers::test_init()` already calls `require_compositor()` internally
(`helpers.rs:31`). The explicit second call repeats `compositor::is_running()`,
which is **two** broker round-trips including a device-side `/system/bin/sh`
fork-exec (see Item 3).

**Approach:** delete the redundant `require_compositor();` line in those tests.
No behavior change — `test_init` guarantees it.

**Saving:** one device shell fork-exec + one query-state per affected test.

---

## Item 3 — Make `compositor::is_running()` cheaper

**Where:** `compositor.rs:243-254`.

```rust
pub fn is_running() -> io::Result<bool> {
    if query_state_once().is_err() { return Ok(false); }      // proves loop dispatching
    let exists = adb::rootfs_host_exec(&[                      // device fork-exec just to test -e
        "/system/bin/sh","-c","test -e /data/data/me.phie.tawc/share/wayland-0"])?;
    Ok(exists.status.success())
}
```

**Why:** the socket `test -e` fork-execs a device shell on *every* readiness
check. The comment (`compositor.rs:241-242`) says the socket probe matters only
because the compositor starts lazily on first `RUNINSIDE`. Once the suite has
launched the compositor (which `run-integration-tests.sh:324-346` already
confirms, including the socket), the socket exists for the life of the run.

**Approach:** cache the socket-existence result in a `OnceLock<bool>` after the
first success — subsequent `is_running()` calls collapse to a single
`query-state` round-trip (no device fork-exec). Alternatively drop the socket
probe entirely from the per-test path and rely on the suite-script's one-time
readiness gate.

**Saving:** one device shell fork-exec per `require_compositor()` call.

---

## Item 4 — Memoize the `rootfs::ensure_*` probes

**Where:** `rootfs.rs:50-92` (`ensure_tawc_dri_test`, `ensure_x11_debug_app`,
`ensure_eglx11_test`, `ensure_libhybris_tls_repro`). Call sites:
`xwayland.rs:249,270,365,441,530`, `libhybris.rs:86`.

**Why:** each does a chroot-entering `RUNINSIDE` (`rootfs_run`) just to `test -x`
a binary — the heaviest broker primitive (proot/chroot startup).
`ensure_libhybris_tls_repro` does **two** chroot entries (`rootfs.rs:79` binary +
`.so` probe). These run per test. The analogous `helpers::ensure_wayland_debug_app`
is already memoized in a `OnceLock` (`helpers.rs:42-44`) and runs once for the
whole binary.

**Approach:** wrap each `ensure_*` result in a `OnceLock<String>` (keyed per app
name), mirroring `helpers.rs:40-45`. Since all integration tests compile into one
test binary (`tests/integration.rs`), memoization is effectively once-global.

**Saving:** N−1 chroot entries per app, where N is the number of tests using that
app.

---

## Item 5 — Tighten teardown poll cadence

**Where:** `assert_compositor_clean` (`helpers.rs:295-323`) → `wait_for_state`
polls at 200ms (`compositor.rs:192`), `wait_for_rendered_toplevels` at 50ms
(`compositor.rs:214`).

**Why:** the common case is a fast clean-up; a 200ms first poll adds latency to
nearly every graphical test's teardown.

**Approach:** lower the `wait_for_state` interval to match
`wait_for_rendered_toplevels` (50ms), or add a short initial tight-poll before
backing off. Low risk, modest saving, on the teardown path of most tests.

---

## How to validate

- Time a full `scripts/run-integration-tests.sh` run before/after (serial, so
  total time is the metric).
- Guard against flakiness from removing sleeps: run the suite several times;
  watch the tests that previously relied on grace periods (`apps::*`,
  `libhybris::*firefox*`, `gfxstream::*firefox*`, `assert_renders_via_shm` callers).
- Confirm no regression in the isolation invariants by keeping `test_init` +
  `assert_compositor_clean` semantics unchanged (only their internal round-trip
  *cost* changes, not their assertions).

## Out of scope (separate effort)

The tawcroot C integration tests (`tawcroot/tests/integration/`) have their own
per-test overhead — `build_rootfs()` re-copies an identical fixture tree and
`rh_rmrf` spawns `system("rm -rf")` on every test, plus baked-in sleeps in
`test_exec_child.c`. Not covered here; worth a sibling plan if pursued.
