# Smithay Desktop Refactor Plan

This is a speculative plan for using more of Smithay's higher-level desktop
abstractions. The goal is not to remove tawc-specific policy. Android Activity
ownership, touch-vs-keyboard focus behavior, output scaling, text-input focus,
and the AHB/SHM render path are still compositor policy.

## Current Shape

tawc currently uses Smithay's protocol handlers and some desktop helpers:

- `PopupManager`, `PopupGrab`, `PopupKeyboardGrab`, and `PopupPointerGrab`
- xdg-shell toplevel/popup handler traits
- Smithay seat handles for keyboard, touch, and popup grabs
- manual surface-tree traversal for hit testing and rendering

It does not use the larger desktop model:

- `smithay::desktop::Window`
- `smithay::desktop::Space<Window>`
- `Window::surface_under(...)`
- `WindowSurfaceType`
- Smithay's `render_output` / `AsRenderElements` desktop render path
- layer-shell `LayerMap` / `LayerSurface`

The result is that tawc owns toplevel stacking, host assignment, input hit
testing, keyboard-focus policy, popup dismissal, and rendering order directly.

## Invariants To Keep

- Touch focus and keyboard/text-input focus are distinct decisions.
- `wl_subsurface` can receive touch but must never receive keyboard focus.
- Non-grabbed `xdg_popup` touches must not move keyboard focus.
- Explicit popup grabs remain protocol-driven and use Smithay popup grabs.
- `TawcState::set_input_focus` remains the one path for keyboard and
  text-input-v3 focus updates.
- The debug app should keep aborting loudly on protocol violations.
- Android Activity assignment remains tawc-owned.

## Viable Cut Lines

### 1. Hit Testing Only

Use Smithay's `Window::surface_under(...)` for toplevel-local hit testing, but
keep tawc's host list, toplevel-to-host map, and renderer.

What moves to Smithay:

- descendant traversal for toplevel surfaces, popups, and subsurfaces
- role-aware surface classification via `WindowSurfaceType`

What stays in tawc:

- Android host selection
- top-level window ordering
- touch-resolution policy
- popup dismissal policy
- rendering

This is the smallest useful cut. It should reduce fragile manual traversal
without changing the lifetime or render model. The main risk is whether
`Window::surface_under(...)` matches tawc's existing input-region behavior for
Firefox/WebRender child surfaces. That needs tests before adoption.

### 2. Window Wrapper Only

Create a `smithay::desktop::Window` for each xdg toplevel and store it beside
the existing tawc toplevel metadata, but do not introduce `Space`.

What moves to Smithay:

- per-window geometry helpers
- `WindowSurfaceType` hit queries
- window identity and user data
- xdg/Xwayland unification later, if useful

What stays in tawc:

- host assignment
- z-order and foreground host
- frame scheduling
- rendering order
- input focus policy

This is a good intermediate step if `Space` is too intrusive. It also creates a
place to hang future per-window state without extending ad hoc maps.

### 3. `Space<Window>` For Stacking And Placement

Use one or more `Space<Window>` values to own window positions and stacking
order. Android hosts can then point at windows mapped in the space.

Possible layouts:

- One global `Space<Window>` containing every mapped window.
- One `Space<Window>` per Android Activity/host.
- One global space plus a tawc mapping from Android host to the visible window.

What moves to Smithay:

- map/raise/lower/relocate behavior
- active-state toggling for mapped windows
- broad hit testing across windows
- output-to-space mapping, if we wire Smithay outputs that way

What stays in tawc:

- when a new Android Activity is spawned
- which Activity shows which window
- touch-resolution policy after a surface is found
- text-input integration
- custom renderer, at least initially

This cut may be worthwhile once tawc supports more complex multi-window
behavior. For the current one-Activity-per-window model, it may add structure
without removing much code. A per-host `Space` is easier to fit but gives less
benefit; a global `Space` is more desktop-like but must be reconciled with
Android's task model.

### 4. Popup Handling Boundary

Keep `PopupManager` as the source of popup tree state, but push more popup
queries/dismissal decisions behind a small tawc wrapper.

What moves or centralizes:

- finding popup root
- enumerating popups for a toplevel
- determining whether a touch is inside a popup tree
- dismissing host popups

What stays in tawc:

- keyboard-focus behavior for non-grabbed popups
- policy for Android-host popups
- explicit grab handling

This is not a Smithay migration as much as a boundary cleanup. It is valuable
because popup behavior has already produced real bugs, and the protocol rules
are subtle.

### 5. Render Path Migration

Use Smithay desktop render helpers for ordinary Wayland windows and popups,
while keeping tawc's custom import code for Android wlegl/AHB buffers.

What moves to Smithay:

- render element assembly for windows/popups/layers
- frame callback integration tied to rendered elements
- eventual layer-shell rendering

What stays in tawc:

- GL context and Android `ANativeWindow` binding
- AHB import and SHM tint behavior
- damage policy tuned for Android SurfaceView
- per-host frame scheduling

This is the most invasive cut. It should only follow a smaller refactor unless
there is a concrete rendering bug or feature that Smithay's render path clearly
solves. The likely friction point is that tawc imports AHBs manually because
Smithay's GLES renderer does not know Android buffers.

### 6. Layer-Shell Adoption

If tawc starts supporting desktop panels, launchers, overlays, or lock-screen
style clients, use Smithay's `LayerSurface` and `LayerMap` instead of inventing
a separate layer stack.

This is mostly future-facing. It should be evaluated when there is an actual
layer-shell client to support.

## Suggested Order

1. Add narrow tests around current hit testing:
   - empty input region falls through
   - subsurface touch targets main-surface keyboard focus
   - non-grabbed popup touch keeps keyboard focus
   - grabbed popup behavior still follows Smithay popup grabs
2. Introduce a `TouchResolution`/hit-test boundary that returns:
   - touch target
   - popup target/root information
   - keyboard-focus action
3. Try cut line 1 in a branch: replace local descendant traversal with
   `Window::surface_under(...)` for one toplevel.
4. If that fits, add `Window` objects beside existing toplevel state.
5. Only then evaluate `Space<Window>` for stacking/placement.
6. Leave render-path migration for last.

## Open Questions

- Does Smithay's `Window::surface_under(...)` fully honor empty input regions in
  the same way our Firefox/WebRender workaround expects?
- Is a global `Space<Window>` compatible with one Android Activity per Wayland
  toplevel, or would per-host spaces better match the platform?
- Can Smithay `Window` state be introduced without disrupting existing
  `toplevel_to_host` and lifecycle cleanup?
- Would `Space` activation state fight with `TawcState::set_input_focus`, or can
  activation remain visual/protocol state while focus stays tawc-owned?
- Can any Smithay render helpers be used while preserving AHB import and SHM
  tinting, or does that require a larger renderer integration?

## Non-Goals

- Do not emulate pointer input from touch as part of this refactor.
- Do not let a library helper decide keyboard focus for subsurfaces or
  non-grabbed popups without an explicit tawc policy check.
- Do not migrate rendering and input in the same patch unless a test forces it.
- Do not treat `Space` as a replacement for Android Activity/task policy.
