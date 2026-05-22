# Smithay Desktop Refactor Plan

This is a research/design note for moving tawc closer to Smithay's desktop
model. The goal is not to remove tawc-specific shell policy. Android Activity
ownership, touch-vs-keyboard focus behavior, output scaling, text-input focus,
and Android buffer import policy remain tawc responsibilities.

## Verdict

The useful long-term direction is to make tawc's AHB-backed wl_buffers visible
to Smithay as normal renderable buffers, then migrate rendering, hit testing,
and scene/stacking logic toward Smithay's desktop abstractions.

The blocker is not that Smithay's desktop model is the wrong use case. The
blocker is that tawc's production buffers are custom `android_wlegl` /
`tawc_gfxstream` AHB buffers. Today Smithay does not know how to size or import
those buffers, so its desktop helpers cannot treat tawc surfaces as mapped
renderable surfaces.

Therefore:

- Do not try a direct `Window::surface_under(...)` swap against the current
  renderer. It depends on Smithay renderer state that tawc does not populate.
- Do not introduce `Space<Window>` as a parallel model before buffers work in
  Smithay. It would add structure without deleting much code.
- Do add a generic external/custom buffer extension point to the Smithay fork,
  then port tawc AHB buffers onto it.
- Do preserve tawc's tinting and shader control with custom render elements or
  equivalent render hooks.
- After Smithay can see and render tawc buffers, migrate desktop behavior
  incrementally and delete tawc's duplicate traversal/hit-test/render logic only
  when each replacement is proven.

This is plausible and likely cleaner than continuing to grow a parallel scene
graph. It is also larger than a local refactor because it starts in the
Smithay fork.

## Current Shape

tawc currently uses Smithay's protocol handlers and some desktop helpers:

- `PopupManager`, `PopupGrab`, `PopupKeyboardGrab`, and `PopupPointerGrab`
- xdg-shell toplevel/popup handler traits
- Smithay seat handles for keyboard, touch, and popup grabs
- `with_surface_tree_downward` for manual surface-tree traversal

It does not use the larger desktop model:

- `smithay::desktop::Window`
- `smithay::desktop::Space<Window>`
- `Window::surface_under(...)`
- `WindowSurfaceType`
- Smithay's `render_output` / `AsRenderElements` desktop render path
- layer-shell `LayerMap` / `LayerSurface`

Instead, tawc mirrors committed buffers into local maps:

- `surface_wlegl`: custom AHB-backed buffers from `android_wlegl` and
  `tawc_gfxstream`
- `surface_shm`: SHM textures imported manually for CPU/debug paths

The renderer then walks toplevels, popups, XWayland surfaces, and subsurfaces
itself; computes logical sizes from buffer scale and viewport state; draws with
custom tint/opacity shaders; sends frame callbacks; and separately performs
touch hit testing from the same local size model.

## Key Finding

Smithay's desktop hit testing and rendering are tied together by renderer
surface state.

`Window::surface_under(...)` calls `under_from_surface_tree(...)`, which checks
`RendererSurfaceStateUserData.surface_view`. `surface_view` is populated by
Smithay's `on_commit_buffer_handler` from:

- buffer dimensions
- buffer alpha/y-inversion metadata
- buffer scale and transform
- viewport state
- subsurface offsets

If `surface_view` is missing, the Smithay helper treats the surface tree as
unmapped for hit-testing purposes.

Calling `on_commit_buffer_handler` today is not enough. It `take()`s
`SurfaceAttributes.buffer`, owns its own release/import assumptions, and calls
Smithay's hard-coded buffer helpers. Those helpers recognize Smithay-managed
SHM, EGL, dmabuf, and single-pixel buffers, but not tawc's custom AHB
wl_buffers. So the right first step is an explicit custom/external buffer
integration, not a hit-test refactor.

## External Buffer API

Add a generic API to the Smithay fork that lets a compositor attach
renderer-visible metadata and import behavior to a `wl_buffer`.

The API should be generic and upstream-shaped, not Android-specific. Conceptual
shape:

```rust
pub trait ExternalBuffer {
    fn dimensions(&self) -> Size<i32, BufferCoord>;
    fn has_alpha(&self) -> Option<bool>;
    fn y_inverted(&self) -> Option<bool> { None }
}

pub trait ExternalGlesBuffer: ExternalBuffer {
    fn import_gles(
        &self,
        renderer: &mut GlesRenderer,
        surface: Option<&SurfaceData>,
        damage: &[Rectangle<i32, BufferCoord>],
    ) -> Result<GlesTexture, GlesError>;
}
```

The exact trait names and module boundaries should follow Smithay style, but
the capability must cover two separate needs:

- Metadata: `buffer_dimensions`, `buffer_has_alpha`, and
  `buffer_y_inverted` need to return useful answers for external buffers so
  `RendererSurfaceState` can compute `surface_view`.
- Import: `ImportAll::import_buffer` or an equivalent render-element path needs
  to import the external buffer into the active renderer and cache/update it
  correctly.

tawc's implementation would wrap `WleglBufferData`. AHB import stays in tawc:
`android_wlegl` and `tawc_gfxstream` still create the buffers, and tawc still
owns `EGL_NATIVE_BUFFER_ANDROID` import details. Smithay only sees a generic
external buffer trait object attached to the `wl_buffer`.

Important design requirements:

- Same-`wl_buffer` reattach with fresh AHB contents must mark the surface dirty.
  tawc currently handles this with `buffer_commit_pending`.
- Buffer release timing must be unambiguous. Once a surface uses Smithay's
  commit buffer handler, tawc should stop doing parallel release/import
  bookkeeping for that buffer.
- The API should not require Smithay itself to link Android or know about
  `AHardwareBuffer`.
- The API should be useful for future compositor-specific buffers, not just
  tawc.

## Custom Render Elements

Using Smithay's desktop render path does not have to mean losing tawc's buffer
tinting or shader control. If stock `WaylandSurfaceRenderElement` cannot be
customized enough, use custom render elements for tawc buffers.

Reasons to keep this explicit from the start:

- SHM magenta tint is intentional debug behavior.
- AHB source tinting distinguishes libhybris and gfxstream paths.
- Some Android buffers need forced-opaque rendering.
- Future tawc rendering policy may need custom shaders or per-buffer effects.

Possible approaches:

- Extend the external buffer import/render path so it can return or select a
  custom render element for the buffer.
- Add a renderer hook/effect hook that wraps Smithay's normal surface render
  element and applies tawc shader policy.
- Keep a custom `RenderElement` only for tawc external buffers while ordinary
  Smithay SHM/dmabuf/EGL buffers use stock elements.

Avoid a split where SHM renders through Smithay but AHB renders through tawc's
old full scene traversal. That leaves two z-order, damage, and frame-callback
systems. A custom render element is fine; a second compositor pipeline is not.

## What Smithay Can Then Replace

Once external buffers populate Smithay renderer state and can render through
Smithay render elements, the higher-level abstractions become useful:

- `Window::surface_under(...)` can replace tawc's descendant traversal for
  toplevels, popups, and subsurfaces.
- `WindowSurfaceType` can make popup/subsurface/toplevel classification
  explicit.
- `AsRenderElements` can assemble render elements for window and popup trees.
- `render_output` / `OutputDamageTracker` can replace tawc's full-screen draw
  loop and some damage tracking.
- `Space<Window>` can own window positions, stacking, activation state, and
  output overlap once the Android host mapping is understood.
- `LayerMap` / `LayerSurface` can be used if tawc adds layer-shell protocol
  support later.

This would let tawc delete real complexity instead of wrapping it:

- manual surface-tree draw collection
- duplicated hit-test traversal
- local popup render ordering
- local surface size model split between render and input
- some frame callback/output bookkeeping

## Android Host / Output / Space Questions

The Activity/output mapping is still the open design area. It is probably best
resolved experimentally after buffers work, because the right answer depends on
how much of Smithay rendering tawc can adopt cleanly.

Candidate models:

- One Smithay `Output` per Android `CompositorActivity` / host. This matches
  per-host EGL surfaces and host-scoped touch input, but is less like a normal
  desktop.
- One global `Space<Window>` with per-host render passes filtering the visible
  window(s). This preserves a shared desktop model but may require extra host
  mapping.
- One `Space<Window>` per Android host. This fits current one-window-per-task
  behavior and may be easiest, but gives fewer benefits until tawc supports
  multiple visible windows per host.

Do not block the external buffer work on choosing this perfectly. The migration
can first prove one host, one output, one window, then iterate.

Things tawc must keep owning regardless:

- when a new Android Activity is spawned or finished
- which Activity is foreground
- keyboard/text-input focus via `TawcState::set_input_focus`
- touch-vs-keyboard focus separation
- Android IME and clipboard integration
- fullscreen state mirrored to Android

## Popup And Touch Policy

Android touch is routed through Smithay `TouchHandle`, so clients receive
normal `wl_touch` events. The part still owned by tawc is popup-grab touch
policy.

Smithay's popup module provides `PopupKeyboardGrab` and `PopupPointerGrab`; it
does not currently provide a ready-made `PopupTouchGrab`. tawc therefore
manually dismisses popups on touch outside and clears Smithay popup grab state.

After hit testing moves to Smithay, tawc should still keep an explicit popup
touch policy boundary:

- non-grabbed popup touches should not move keyboard/text-input focus
- `wl_subsurface` touch targets must focus the main surface for keyboard
- explicit popup grabs must keep Smithay popup-grab state consistent
- host-scoped touch events must not dismiss popups on another Android host

A future tawc `TouchGrab` backed by `PopupGrab` may be worthwhile, but it
still needs tawc host routing and focus policy.

## Invariants To Keep

- Touch focus and keyboard/text-input focus are distinct decisions.
- `wl_subsurface` can receive touch but must never receive keyboard focus.
- Non-grabbed `xdg_popup` touches must not move keyboard focus.
- Explicit popup grabs remain protocol-driven and use Smithay popup grabs.
- `TawcState::set_input_focus` remains the one path for keyboard and
  text-input-v3 focus updates.
- The debug app should keep aborting loudly on protocol violations.
- Android Activity assignment remains tawc-owned.
- AHB import, SHM tinting, AHB source tinting, and forced-opaque behavior must
  survive any renderer migration.

## Suggested Order

1. Add tests around current behavior:
   - empty input region falls through
   - subsurface touch targets main-surface keyboard focus
   - non-grabbed popup touch keeps keyboard focus
   - grabbed popup behavior still follows Smithay popup grabs
   - AHB-backed Firefox/WebRender subsurfaces remain touchable
   - same-`wl_buffer` reattach with fresh AHB contents repaints
   - SHM and AHB tinting remain visible
2. Design and implement the Smithay external buffer API in the fork.
3. Port tawc AHB wl_buffers to that API while keeping tawc's old renderer as
   the active rendering path.
4. Start calling Smithay's commit buffer handler for external-buffer-backed
   surfaces and prove `RendererSurfaceState.surface_view` is populated for AHB.
5. Replace tawc hit testing for one xdg toplevel with `Window::surface_under`
   behind a feature flag or branch; verify Firefox/WebRender, input regions,
   viewport, and popup cases.
6. Implement custom render element or render hook for tint/forced-opaque policy.
7. Port one host's rendering to Smithay render elements while preserving visual
   output and frame callbacks.
8. Evaluate `Space<Window>` and output mapping with the simplest working host
   model; expand only when it deletes tawc-owned stacking/placement code.
9. Delete old manual traversal/render/hit-test code only after Smithay-backed
   paths pass the integration tests.

## Short-Term Fallback

If the Smithay fork work stalls, the low-risk cleanup is still to create a
tawc-local touch-resolution/popup boundary around the existing code. That would
reduce duplicated hit-test/popup policy, but it is a containment refactor, not
the strategic path to using Smithay's desktop model.

## Open Questions

- What is the smallest external buffer API Smithay would accept upstream:
  metadata hooks plus renderer-specific import hooks, or a custom render
  element hook only?
- Should external buffers integrate through `ImportAll::import_buffer`, through
  `WaylandSurfaceRenderElement`, or through a separate `AsRenderElements`
  customization point?
- Can tinting be implemented as a generic render-effect hook rather than a
  tawc-only render element?
- Should the first output model be one Smithay `Output` per Android host, or a
  global output with host-filtered render passes?
- Is a future in-app window switcher better represented by `Space<Window>` or
  by Android task ordering plus local metadata?

## Non-Goals

- Do not emulate pointer input from touch as part of this refactor.
- Do not let a library helper decide keyboard focus for subsurfaces or
  non-grabbed popups without an explicit tawc policy check.
- Do not introduce `Space<Window>` before Smithay can see/render tawc AHB
  buffers.
- Do not treat `Space` as a replacement for Android Activity/task policy.
