# `input::test_input_dispatch` is flaky on the emulator

After `ac87c0b` consolidated the input scenarios into a single chained
`#[test]` that drives one debug-app instance through 13 scenes, the
suite passes consistently on the OnePlus 9 device and intermittently on
the x86_64 emulator. As of 2026-05-04 the residual flake rate is ~20%
(4/5 typical), down from the original ~80% after fixing two distinct
races below.

## Symptoms (residual)

`scene_full_compose_loop_with_click_in_middle`:

```
'hello world': "Timeout waiting for text 'hello world' (last: Some(\"hello worlworld\"))"
```

The 5x `setComposingText("w" → "wo" → "wor" → "worl" → "world")` loop
ends with `"worl" + "world"` (or worse — `"hello wwoworworlworld"` has
been observed) instead of just `"world"`. Every intermediate preedit
gets COMMITTED to the text buffer instead of replacing the previous
preedit.

The committed-preedit pattern only manifests in `scene_full` and only
when run after the 9 preceding scenes (a fresh `gtk4-debug-app` driven
through just the same broadcast sequence does the right thing
end-to-end — see Diagnostic notes below). Something about the IM
context or compositor `text_input_state` accumulates across scenes and
biases the next preedit replacement into a commit.

## Repro

```
TAWC_TARGET=emulator bash testing/run-integration-tests.sh --no-build "input::"
```

Each `scene_full_compose_loop_with_click_in_middle` failure pinpoints
`tests/input.rs:459` — `app.wait_for_text("hello world", TIMEOUT)`.

## What's been fixed (2026-05-04)

1. **Compositor-not-ready race** (compositor + test runner). Two
   coupled bugs surfaced as `chroot_process.rs: Failed to read PID/PGID`
   or `helpers.rs: Debug app exited while waiting for 'READY'`:
   - The compositor was binding the Wayland socket in `lib.rs` *before*
     entering `event_loop::run`, then doing ~80ms of GLES init,
     source registration and Xwayland spawn before reaching the
     dispatch loop. During that window a connecting GTK4 client could
     land in the listen backlog without `accept()` ever running.
     Fixed by deferring bind to inside `event_loop::run` as the last
     step before `event_loop.dispatch` (compositor/src/event_loop.rs).
   - `am force-stop me.phie.tawc` leaves the previous run's
     `wayland-0` socket file on disk, so the test runner's
     `test -e wayland-0` poll matched a stale path while the new
     compositor was still in early init (which made the GTK4 client
     connect against a dead inode and exit immediately). Fixed by
     additionally grepping logcat for the `tawc-native: Entering
     calloop event loop` line before declaring the compositor ready.

2. **Inter-broadcast wait was zero**. Adding `app.wait_for_preedit(p)`
   between each `adb::input_set_composing(p)` (already done in
   `scene_compose_lifecycle`, missing in `scene_full`) closes some of
   the rapid-fire window. It does NOT fix the residual flake — even
   with a confirmed PREEDIT round-trip + 150ms sleep between
   iterations, the same "every preedit gets committed" pattern still
   shows up ~30% of runs. Reverted because it doesn't help and
   introduces noise.

## Hypotheses (untested)

- The compositor's `current_preedit` mirror gets out of sync with what
  the client thinks the preedit is across many enable/disable cycles.
  `reset_buffer` clears it via `finishComposingText` →
  `delete_surrounding_text(1000, 1000)`, but the GTK4 IM context
  state on the client side might also need a reset.
- A stray focus leave/enter cycle (which calls
  `handle_android_event(FinishComposingText)`) could be firing during
  the rapid-fire compose loop. Would explain the per-preedit commit
  pattern: each preedit_string event triggers an internal commit. No
  evidence yet.
- GTK4 specifically might commit a preedit on receiving a new
  preedit_string with `cursor_begin == cursor_end == len` if it
  interprets it as a "preedit + cursor at end" with replacement
  semantics that depend on prior surrounding-text state.

## Diagnostic notes

- A standalone manual run that bypasses the test harness and just
  fires the same 5x `am broadcast SET_COMPOSING_TEXT` sequence + a
  `FINISH_COMPOSING_TEXT` against a fresh `gtk4-debug-app` produces
  exactly the right output: `PREEDIT:h ... PREEDIT:hello, TEXT_CHANGED:hello, PREEDIT (cleared)`.
  The flake only appears under the 13-scene cumulative test sequence.
- Failure pattern is "every preedit committed" not "some preedits
  committed" — points at a state machine that's been wedged into the
  wrong mode rather than a transient timing race.

## Possible directions

- Fully tear down the GTK4 client between scene groups (lose the
  amortisation but break the cross-scene state carry).
- Insert an explicit text-input enable/disable cycle into
  `reset_buffer` so the compositor's per-instance state and the
  client's IM context both fully reset between scenes.
- Capture `WAYLAND_DEBUG=server` output across a failing run to
  confirm whether the compositor is sending `commit_string` events
  for preedits (state-machine bug on our side) or just
  `preedit_string` events the client misinterprets (client / GTK4
  bug we'd have to work around).
