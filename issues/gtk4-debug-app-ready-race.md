# gtk4-debug-app READY race with text-input enable

## Symptom

Without an explicit delay in `gtk4-debug-app`, the integration test
`test_gtk4_text_input_hardware_buffers` broadcasts `TEXT_INPUT` before
GTK4's IM context has sent `zwp_text_input_v3.enable`. The compositor
drops `commit_string` events while `!inst.enabled`
(`server/compositor/src/text_input.rs`), so the first typed characters
are silently lost and the test times out.

## Current workaround

`testing/gtk4-debug-app/gtk4-debug-app.c` replaces the "emit READY on
next idle" pattern with `g_timeout_add(500, emit_ready, NULL)` in
`on_map()`. 500ms comfortably covers the ~300ms observed IM init on
this device but is fragile: slower devices, heavier setups, or
unrelated scheduling can still race; faster hardware pays the tax
for nothing.

GTK3 does not need this because its IM context activates synchronously
when the text widget gains focus.

## Options for a real fix

- **Wait for IM activation signal.** GTK4 doesn't expose a direct
  "IM enabled" signal on `GtkIMContext` / `GtkTextView`. Could poll
  `gtk_text_view_im_context_filter_keypress` side-effects, or listen
  on the underlying Wayland object via a raw client (overkill).
- **Buffer commit_string in the compositor.** Queue Android IME events
  while `!inst.enabled` and flush when enable arrives. Harder
  to reason about (focus changes, multiple clients), and GTK3 proves
  we don't actually need it.
- **Retry the broadcast from the test harness.** `DebugApp` could
  re-broadcast on timeout. Cheap, but leaks "text-input is racy" into
  every new test.

## Acceptance

Remove the `g_timeout_add(500, ...)` and revert to the original
`g_idle_add(emit_ready, NULL)` pattern; `test_gtk4_text_input_hardware_buffers`
still passes reliably.
