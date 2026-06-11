# assert_compositor_clean races async Android host teardown

`helpers::assert_compositor_clean` waits for `clients==0 && toplevels==0`
(`wait_for_state`) but then asserts `hosts == 0` / `bound_hosts == 0`
immediately. Host teardown goes through an Android Activity `finish()`
round trip, so a host can legitimately still be registered for a short
while after the last surface is gone.

Seen once on the emulator (2026-06-10):
`xwayland::test_xwayland_setting_starts_and_stops_process_live` failed
its final `assert_compositor_clean` with `hosts: 1, bound_hosts: 1` and
everything else already zero; the same test passed on immediate re-run
and in a subsequent full `xwayland` filter run.

Likely fix: fold the host counts into the polled condition (extend
`wait_for_state` or add a `wait_for_hosts(0, ...)`) instead of asserting
them point-in-time.
