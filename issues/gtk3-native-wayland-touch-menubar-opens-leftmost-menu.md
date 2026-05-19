# GTK3 native Wayland touch menubar opens leftmost menu

Status: open. The popup-grab-stack regression found during this
investigation is fixed separately; this issue tracks the remaining GTK3
native Wayland menubar behavior.

`lxterminal` on native Wayland still opens the File menu when the first
fresh tap is on another menubar item, e.g. Help.

Observed on 2026-05-19 on the physical target:

- `wl_touch.down` is delivered to the toplevel with the correct logical
  coordinates for Help: `160.0,13.5`.
- GTK first requests the correct Help popup with
  `xdg_positioner.set_anchor_rect(134, 0, 49, 27)`.
- Before the popup is configured, GTK destroys it and requests the File
  popup with `set_anchor_rect(0, 0, 42, 27)`.
- The installed GTK3 package was `gtk3 1:3.24.52-1`, so this is not
  fixed for tawc's touch-only path by the GTK 3.24.49 menubar/crossing
  fixes.

Also reproduced on 2026-05-19 with `gtk3-demo --run=menus` on the same
physical target:

- Fresh tap on the `bar` menubar item delivered
  `wl_touch.down(..., 110.0, 23.5)`.
- GTK first requested the tapped menu popup with
  `xdg_positioner.set_anchor_rect(90, 0, 39, 46)`.
- GTK then destroyed that popup and opened the leftmost `test\nline2`
  menu with `set_anchor_rect(0, 0, 51, 46)`.
- Both the `lxterminal` and `gtk3-demo` `WAYLAND_DEBUG=1` logs contained
  zero `wl_pointer` / `get_pointer` events, so tawc is not accidentally
  moving pointer focus.

This appears to be a GTK3 native Wayland bug, not a tawc coordinate or
popup-grab bug. KDE bug 490833 reports the same client-side sequence:
clicking a non-leftmost GTK menubar item creates the correct popup, GTK
immediately destroys it, then creates a popup anchored at `(0, 0, ...)`
for the leftmost menu. The KDE report cites Emacs PGTK, Xournal++,
DeaDBeeF, Inkscape, and GIMP as affected clients. KDE marked their side
as downstream/workaround territory and linked GTK MR !8240; GTK 3.24.49
release notes mention "Wayland: Fix erroneous crossing events, causing
menus to malfunction", but tawc still reproduces with GTK 3.24.52 when
only `wl_touch` is advertised.

References:

- https://bugs.kde.org/show_bug.cgi?id=490833
- https://gitlab.gnome.org/GNOME/gtk/-/merge_requests/8240
- GTK commit `4e005ec603905b88439c387681d9ba13957182ac`
  (`gdk/wayland: Avoid grab crossing on idle tablets`), included since
  GTK 3.24.49.

Fixed during this investigation:

- Touch outside-dismiss now unwinds Smithay's active xdg popup grab with
  `PopupGrab::ungrab(PopupUngrabStrategy::All)`.
- `input::test_touch_grabbed_popup_switches_to_next_popup` reproduces the
  old failure: tap to open one grabbed popup, tap outside it to open another,
  require the first `POPUP_DONE`, then require the second popup to map.
- The seat no longer advertises `wl_pointer` unless a real pointer input path
  is added. The old test that required no pointer advertisement was removed
  because future targets may legitimately have pointer hardware.

Ruled out for the remaining lxterminal/GTK3 bug:

- Popup grab unwind: fixed separately and covered by
  `input::test_touch_grabbed_popup_switches_to_next_popup`.
- Advertising no `wl_pointer`: correct for tawc's touchscreen-only input,
  but GTK3 still falls back to File internally.
- A redundant same-position `wl_touch.motion` after down.
- `gtk-touchscreen-mode=true`.
- `GDK_CORE_DEVICE_EVENTS=1`.
- Preloading a constructor that calls `gdk_disable_multidevice()`.

The only tested workaround that made native GTK3 open the tapped menu was
synthetically updating `wl_pointer` focus/position before `wl_touch.down`.
That is not acceptable as the default phone behavior: tawc should not invent
a Wayland pointer or send pointer enter events for finger taps.

Other known/likely workarounds:

- Patch GTK3's menubar/touch handling.
- Run affected GTK3 apps through XWayland with `GDK_BACKEND=x11`, if the
  app works acceptably through tawc's XWayland path.
- Use keyboard menu activation/accelerators where practical.
- Prefer GTK4/Qt/headerbar/popover UI paths that do not use GTK3's old
  `GtkMenuBar`/`GtkMenu` native Wayland path.

Do not fix this by spoofing pointer events. If pointer hardware exists in a
future target, support it through a real pointer input path; otherwise keep
touch as `wl_touch` only, even if spoofed pointer focus makes GTK3 menus work.

Verification from the 2026-05-19 fix pass:

- `./scripts/build-app.sh`
- `./scripts/run-integration-tests.sh input::test_touch_grabbed_popup_switches_to_next_popup`
- `./scripts/run-integration-tests.sh --no-build input::test_touch_popup_tap`
- `./scripts/run-integration-tests.sh --no-build input::test_touch_tap`
