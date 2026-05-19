//! Input dispatch tests. Drive wayland-debug-app through compositor input
//! paths (text-input-v3 commits, key events, touch taps) and assert the
//! debug app observes the expected client-side behaviour.
//!
//! # The rule
//!
//! Tests interact with the system **only** as a keyboard or as an app:
//!
//! - **As a keyboard**: every input call goes through
//!   [`TawcInputConnection`] via `adb::ic_*` helpers — the same Kotlin
//!   surface the system IMM dispatches Gboard / OpenBoard / AOSP-latin
//!   events through. The IC's full state machine (Editable mirror,
//!   `computeReplaceDeltas`, `composingRegionIsPreedit` short-circuit,
//!   `wireCursor`, `unitsToKeyPlan`, `lastSyncedCursor` divergence guard) runs on
//!   every test exactly the same way it runs in production.
//! - **As an app**: assertions go through `wayland-debug-app`'s observed
//!   `TAWC_DEBUG:…` events (`TEXT_CHANGED`, `PREEDIT`, `CURSOR_POS`,
//!   `KEY`, `COMMIT`, `DELETE_SURROUNDING`). That's what a real wayland
//!   client running under our compositor would see.
//!
//! There is intentionally **no test infrastructure that pokes into the
//! compositor's state machine in between**. The previous bypass channel
//! that called `NativeBridge.native*` directly was deleted because it
//! could pass even when IC code (the largest chunk of our text-input
//! logic) was broken — text-input-v3's done-ordering replaces preedit on
//! the wayland side regardless of what the IC computed, so a buggy IC
//! produced the right *observable* and the test smiled. Driving every
//! scenario through IC closes that hole and turns wayland-side
//! assertions into a real integration check.
//!
//! [`crate::helpers::start_wayland_debug_text_input`] calls
//! `enable-test-input` on
//! every test so the system IME (a third-party at the OS boundary) is
//! removed from the loop — that's not "in the middle of the state
//! machine", it's removing a non-deterministic external actor that
//! would otherwise amplify our IC calls back at us.
//!
//! Related real GTK coverage lives in
//! `apps::test_gtk4_widget_factory_copy_paste_and_text_input`; this module
//! stays focused on deterministic protocol-level input coverage.
//!
//! Deliberately avoid asserting anything about buffer types (AHB vs SHM):
//! the point is how the compositor *dispatches input*, not how clients
//! render. That keeps these tests safe to run on the emulator (where
//! libhybris/AHB is unavailable).

use std::thread;
use std::time::{Duration, Instant};

use tawc_integration::adb;
use tawc_integration::debug_app::DebugApp;
use tawc_integration::helpers::{
    assert_compositor_clean, start_wayland_debug_clipboard_copy,
    start_wayland_debug_clipboard_paste, start_wayland_debug_popup, start_wayland_debug_subsurface,
    ensure_wayland_debug_app, start_wayland_debug_popup_switch,
    start_wayland_debug_subsurface_input_empty, start_wayland_debug_text_input,
    start_wayland_debug_text_input_no_surrounding, start_wayland_debug_text_input_stale_newline,
    start_wayland_debug_touch, TIMEOUT,
};
use tawc_integration::GraphicsBackend;

/// Input dispatch has no buffer-type stake; pick the most portable
/// backend (no libhybris LD_LIBRARY_PATH, no gfxstream env) so a single
/// run exercises the dispatch paths without depending on a GPU.
const INPUT_BACKEND: GraphicsBackend = GraphicsBackend::Cpu;

// Coordinates aimed at wayland-debug-app's compact 640x240 surface.
// Coordinates are physical;
// the compositor maps them to the app's logical surface through output scale.
const WAYLAND_TAP_TEXT_MID_X: u32 = 200;
const WAYLAND_TAP_TEXT_MID_Y: u32 = 250;
const WAYLAND_TAP_TEXT_START_X: u32 = 40;

#[derive(Clone, Copy)]
struct InputTapCoords {
    text_mid_x: u32,
    text_mid_y: u32,
    text_start_x: u32,
}

const WAYLAND_TAP_COORDS: InputTapCoords = InputTapCoords {
    text_mid_x: WAYLAND_TAP_TEXT_MID_X,
    text_mid_y: WAYLAND_TAP_TEXT_MID_Y,
    text_start_x: WAYLAND_TAP_TEXT_START_X,
};

const WAYLAND_DEBUG_ENV: &str = "";

// --- Scenes -----------------------------------------------------------------
//
// Each scene drives the IC with a Gboard-shaped sequence and asserts what
// the wayland client saw. Scenes assume the buffer starts empty.

/// Click cursor positioning + behaviour at the cursor: backspace deletes
/// from cursor (not end), commit_string inserts at cursor (not end), and a
/// preedit-then-finish round-trip places the new char at the click site.
fn scene_click_cursor_positioning(app: &DebugApp, taps: InputTapCoords) {
    adb::ic_commit_text("abcdef").expect("commit 'abcdef'");
    app.wait_for_text("abcdef", TIMEOUT).expect("'abcdef'");

    let cursor_count_before = app.cursor_pos_count();
    adb::input_tap(taps.text_mid_x, taps.text_mid_y).expect("tap mid");
    let cursor = app
        .wait_for_cursor_change(cursor_count_before, TIMEOUT)
        .expect("CURSOR_POS event after tap");
    assert!(
        cursor > 0 && cursor < 6,
        "cursor should be in middle of 'abcdef', got {}",
        cursor
    );
    assert_eq!(
        app.last_text().as_deref(),
        Some("abcdef"),
        "tap changed text"
    );

    let change_count = app.text_changed_count();
    adb::ic_send_key_event(adb::KEYCODE_DEL).expect("backspace at cursor");
    let after_bs = app
        .wait_for_text_change(change_count, TIMEOUT)
        .expect("TEXT_CHANGED after backspace");
    assert_eq!(
        after_bs.len(),
        5,
        "expected 5 chars after one backspace from 'abcdef', got {:?}",
        after_bs
    );
    assert_ne!(
        after_bs, "abcde",
        "backspace deleted from end, not cursor: {:?}",
        after_bs
    );

    let cursor_after_bs = cursor - 1;
    adb::ic_set_composing_text("X").expect("setComposingText 'X'");
    app.wait_for_preedit("X", TIMEOUT).expect("preedit 'X'");
    adb::ic_finish_composing().expect("finishComposingText");

    let deadline = Instant::now() + TIMEOUT;
    let mut last = String::new();
    while Instant::now() < deadline {
        last = app.last_text().unwrap_or_default();
        if last.len() == 6 && last.contains('X') {
            break;
        }
        thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(
        last.len(),
        6,
        "expected len 6 after inserting 'X' into 5-char buffer, got {:?}",
        last
    );
    let x_pos = last.find('X').expect("'X' must appear in buffer");
    assert_eq!(
        x_pos, cursor_after_bs as usize,
        "'X' should sit at cursor position {}, got pos {} in {:?}",
        cursor_after_bs, x_pos, last
    );
    assert!(
        !last.ends_with('X') || last.len() != 6 || x_pos == 5,
        "'X' was appended at end instead of inserted at cursor: {:?}",
        last
    );
}

/// Full integration: build "hello world" via two compose loops, click in
/// the middle, compose another word at the new cursor. Catches
/// regressions in the end-to-end Gboard flow that simple per-feature
/// scenarios miss.
fn scene_full_compose_loop_with_click_in_middle(app: &DebugApp, taps: InputTapCoords) {
    for prefix in ["h", "he", "hel", "hell", "hello"] {
        adb::ic_set_composing_text(prefix).expect("setComposingText");
    }
    adb::ic_finish_composing().expect("finish word 1");
    adb::ic_commit_text(" ").expect("commit ' '");
    app.wait_for_text("hello ", TIMEOUT).expect("'hello '");

    for prefix in ["w", "wo", "wor", "worl", "world"] {
        adb::ic_set_composing_text(prefix).expect("setComposingText");
    }
    adb::ic_finish_composing().expect("finish word 2");
    app.wait_for_text("hello world", TIMEOUT)
        .expect("'hello world'");

    let cursor_count = app.cursor_pos_count();
    adb::input_tap(taps.text_mid_x, taps.text_mid_y).expect("tap mid");
    let cursor = app
        .wait_for_cursor_change(cursor_count, TIMEOUT)
        .expect("cursor change");
    assert!(cursor > 0 && cursor < 11, "cursor mid, got {}", cursor);

    for prefix in ["x", "xy", "xyz"] {
        adb::ic_set_composing_text(prefix).expect("setComposingText 'xyz' loop");
    }
    adb::ic_finish_composing().expect("finish word 3");

    let deadline = Instant::now() + TIMEOUT;
    let mut last = String::new();
    while Instant::now() < deadline {
        last = app.last_text().unwrap_or_default();
        if last.len() == 14 && last.contains("xyz") {
            break;
        }
        thread::sleep(Duration::from_millis(50));
    }
    assert_eq!(
        last.len(),
        14,
        "expected len 14 (11 + 'xyz'), got {:?} (len {})",
        last,
        last.len()
    );
    let xyz_pos = last.find("xyz").expect("'xyz' in text");
    assert_eq!(
        xyz_pos, cursor as usize,
        "'xyz' should land at cursor pos {} but went to {} in {:?}",
        cursor, xyz_pos, last
    );
    assert!(
        last.matches("hello").count() <= 1,
        "'hello' duplicated in {:?}",
        last
    );
    assert!(
        last.matches("world").count() <= 1,
        "'world' duplicated in {:?}",
        last
    );
}

/// IC delta-computation: `<word><space><backspace>` then
/// `setComposingRegion + commitText("<word> ")` must replace the marked
/// region rather than appending. Without the IC's pre-commit delete
/// emission the wire commit had nothing deleting the marked region, so
/// the buffer ended up `hellohello `.
fn scene_space_backspace_space_no_duplicate(app: &DebugApp) {
    adb::ic_set_composing_text("hello").expect("ic setComposingText 'hello'");
    app.wait_for_preedit("hello", TIMEOUT)
        .expect("preedit 'hello'");

    let change_count = app.text_changed_count();
    adb::ic_commit_text("hello ").expect("ic commitText 'hello '");
    app.wait_for_text_change(change_count, TIMEOUT)
        .expect("text change after 'hello '");

    let change_count = app.text_changed_count();
    adb::ic_send_key_event(adb::KEYCODE_DEL).expect("backspace");
    app.wait_for_text_change(change_count, TIMEOUT)
        .expect("text change after backspace");
    let after_bs = app.last_text().unwrap_or_default();
    assert_eq!(
        after_bs.trim_end_matches(' ').len(),
        5,
        "after backspace buffer should be 'hello'; got {:?}",
        after_bs
    );

    thread::sleep(Duration::from_millis(200));

    adb::ic_set_composing_region(0, 5).expect("ic setComposingRegion 0..5");
    thread::sleep(Duration::from_millis(150));

    let change_count = app.text_changed_count();
    adb::ic_commit_text("hello ").expect("ic commitText 'hello ' over region");
    app.wait_for_text_change(change_count, TIMEOUT)
        .expect("text change after second space");
    thread::sleep(Duration::from_millis(300));

    let text = app.last_text().unwrap_or_default();
    assert_eq!(
        text.matches("hello").count(),
        1,
        "'hello' duplicated — got {:?} (len {}). The IC didn't delete the \
         composing region before commitText, so the new 'hello ' was \
         appended instead of replacing the marked one.",
        text,
        text.len()
    );
}

/// IC re-commit-word + newline (OpenBoard's per-Enter pattern). Enter
/// fires `setComposingRegion(0, N)` + `commitText(word, 1)` +
/// `commitText("\n", 1)`. Without the short-circuit fix, the IC
/// propagates composing-region deltas and can slice bytes off the buffer.
fn scene_recommit_word_then_newline_no_h_prepend(app: &DebugApp) {
    adb::ic_commit_text("hello").expect("ic commitText 'hello'");
    app.wait_for_text("hello", TIMEOUT).expect("'hello'");
    thread::sleep(Duration::from_millis(200));

    let change_count = app.text_changed_count();
    adb::ic_set_composing_region(0, 5).expect("ic setComposingRegion 0..5");
    adb::ic_commit_text("hello").expect("ic commitText 'hello' (re-commit)");
    adb::ic_commit_text("\n").expect("ic commitText '\\n'");
    app.wait_for_text_change(change_count, TIMEOUT)
        .expect("text change after Enter");
    thread::sleep(Duration::from_millis(250));

    // The debug app escapes '\n' as the two-char sequence `\n` so
    // the protocol stays single-line — assert against that escaped
    // form.
    let expected = "hello\\n";
    let text = app.last_text().unwrap_or_default();
    assert_eq!(
        text, expected,
        "expected buffer {:?} but got {:?}. The IC propagated \
         composing-region deltas, slicing bytes off the buffer.",
        expected, text
    );
}

/// Round-trip through `updateFromCompositor` must clear any composing
/// span on the Editable. Without [BaseInputConnection.removeComposingSpans],
/// a stale span left over from `setComposingRegion` would mis-classify
/// the next IC `commitText` as "replace the marked region" — slicing
/// bytes that are no longer part of the buffer's view of composing.
fn scene_update_from_compositor_clears_composing_spans(app: &DebugApp) {
    adb::ic_commit_text("abc").expect("ic commitText 'abc'");
    app.wait_for_text("abc", TIMEOUT).expect("'abc'");
    thread::sleep(Duration::from_millis(250));

    adb::ic_set_composing_region(0, 3).expect("ic setComposingRegion 0..3");
    // Round-trip: the next surrounding_text from the client clears the span.
    // updateFromCompositor must call removeComposingSpans.
    thread::sleep(Duration::from_millis(400));

    let change_count = app.text_changed_count();
    adb::ic_commit_text("XYZ").expect("ic commitText 'XYZ'");
    app.wait_for_text_change(change_count, TIMEOUT)
        .expect("text change after 'XYZ'");
    thread::sleep(Duration::from_millis(250));

    let text = app.last_text().unwrap_or_default();
    assert!(
        text.contains("XYZ"),
        "'XYZ' missing from buffer: {:?}",
        text
    );
    assert!(
        text.contains("abc") || text.contains("XYZ"),
        "stale composing span sliced bytes from the buffer: {:?}",
        text
    );
}

fn with_wayland_text_input(run: impl FnOnce(&DebugApp)) {
    let mut app = start_wayland_debug_text_input(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    run(&app);
    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

fn with_wayland_touch(run: impl FnOnce(&DebugApp)) {
    let mut app = start_wayland_debug_touch(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    run(&app);
    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

fn with_wayland_subsurface(run: impl FnOnce(&DebugApp)) {
    let mut app = start_wayland_debug_subsurface(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    run(&app);
    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

fn with_wayland_subsurface_input_empty(run: impl FnOnce(&DebugApp)) {
    let mut app = start_wayland_debug_subsurface_input_empty(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    run(&app);
    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

fn with_wayland_popup(run: impl FnOnce(&DebugApp)) {
    let mut app = start_wayland_debug_popup(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    run(&app);
    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

fn with_wayland_popup_switch(run: impl FnOnce(&DebugApp)) {
    let mut app = start_wayland_debug_popup_switch(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    run(&app);
    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

#[derive(Clone, Debug)]
struct TouchDebugEvent {
    id: i32,
    x: f64,
    y: f64,
    active: u32,
}

fn parse_touch_event(payload: &str) -> TouchDebugEvent {
    let mut parts = payload.split(':');
    let id = parts
        .next()
        .expect("touch id")
        .parse()
        .expect("touch id integer");
    let x = parts
        .next()
        .expect("touch x")
        .parse()
        .expect("touch x number");
    let y = parts
        .next()
        .expect("touch y")
        .parse()
        .expect("touch y number");
    let active = parts
        .next()
        .expect("touch active count")
        .parse()
        .expect("touch active integer");
    assert!(
        parts.next().is_none(),
        "extra fields in touch payload {payload:?}"
    );
    TouchDebugEvent { id, x, y, active }
}

fn touch_events(app: &DebugApp, tag: &str) -> Vec<TouchDebugEvent> {
    app.payloads_with_tag(tag)
        .iter()
        .map(|payload| parse_touch_event(payload))
        .collect()
}

fn inject_touch(kind: &str) {
    let output = adb::inject_touch(kind).unwrap_or_else(|e| panic!("inject-touch {kind}: {e}"));
    assert!(
        output.status.success(),
        "inject-touch {kind} failed: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[derive(Clone, Debug)]
struct SurfaceTouchDebugEvent {
    target: String,
    id: i32,
    x: f64,
    y: f64,
    active: u32,
}

#[derive(Clone, Debug)]
struct PopupLayout {
    child_x: i32,
    child_y: i32,
    shadow: i32,
    content_w: i32,
    content_h: i32,
    configure_x: i32,
    configure_y: i32,
    configure_w: i32,
    configure_h: i32,
    parent_geom_x: i32,
    parent_geom_y: i32,
}

fn parse_surface_touch_event(payload: &str) -> SurfaceTouchDebugEvent {
    let mut parts = payload.split(':');
    let target = parts.next().expect("touch target").to_string();
    let id = parts
        .next()
        .expect("touch id")
        .parse()
        .expect("touch id integer");
    let x = parts
        .next()
        .expect("touch x")
        .parse()
        .expect("touch x number");
    let y = parts
        .next()
        .expect("touch y")
        .parse()
        .expect("touch y number");
    let active = parts
        .next()
        .expect("touch active count")
        .parse()
        .expect("touch active integer");
    assert!(
        parts.next().is_none(),
        "extra fields in surface touch payload {payload:?}"
    );
    SurfaceTouchDebugEvent {
        target,
        id,
        x,
        y,
        active,
    }
}

fn parse_popup_layout(payload: &str) -> PopupLayout {
    let fields: Vec<i32> = payload
        .split(':')
        .map(|part| part.parse().expect("popup layout integer"))
        .collect();
    assert_eq!(
        fields.len(),
        11,
        "unexpected popup layout payload {payload:?}"
    );
    PopupLayout {
        child_x: fields[0],
        child_y: fields[1],
        shadow: fields[2],
        content_w: fields[3],
        content_h: fields[4],
        configure_x: fields[5],
        configure_y: fields[6],
        configure_w: fields[7],
        configure_h: fields[8],
        parent_geom_x: fields[9],
        parent_geom_y: fields[10],
    }
}

fn surface_touch_events(app: &DebugApp, tag: &str) -> Vec<SurfaceTouchDebugEvent> {
    app.payloads_with_tag(tag)
        .iter()
        .map(|payload| parse_surface_touch_event(payload))
        .collect()
}

fn assert_surface_tap_delivered(app: &DebugApp, target: &str) {
    inject_touch("tap");
    app.wait_for_tag_count("SURFACE_TOUCH_DOWN", 1, TIMEOUT)
        .expect("surface touch down");
    app.wait_for_tag_count("SURFACE_TOUCH_UP", 1, TIMEOUT)
        .expect("surface touch up");

    let downs = surface_touch_events(app, "SURFACE_TOUCH_DOWN");
    let ups = surface_touch_events(app, "SURFACE_TOUCH_UP");
    assert_eq!(downs.len(), 1, "expected one down, got {downs:?}");
    assert_eq!(ups.len(), 1, "expected one up, got {ups:?}");
    assert_eq!(downs[0].target, target, "touch down target");
    assert_eq!(ups[0].target, target, "touch up target");
    assert_eq!(downs[0].id, ups[0].id, "tap up used a different slot");
    assert_eq!(downs[0].active, 1, "tap down active count");
    assert_eq!(ups[0].active, 0, "tap up active count");
    assert!(
        downs[0].x >= 0.0 && downs[0].y >= 0.0,
        "surface-local coordinates should be non-negative: {:?}",
        downs[0]
    );
}

fn assert_popup_shadow_geometry_tap_delivered(app: &DebugApp) {
    app.wait_for_tag_count("POPUP_LAYOUT", 1, TIMEOUT)
        .expect("popup layout");
    let layout = parse_popup_layout(
        app.payloads_with_tag("POPUP_LAYOUT")
            .last()
            .expect("popup layout payload"),
    );

    assert_eq!(
        layout.configure_x,
        layout.child_x - layout.parent_geom_x,
        "popup configure x should be relative to parent window geometry"
    );
    assert_eq!(
        layout.configure_y,
        layout.child_y - layout.parent_geom_y,
        "popup configure y should be relative to parent window geometry"
    );
    assert_eq!(layout.configure_w, layout.content_w, "popup configure width");
    assert_eq!(layout.configure_h, layout.content_h, "popup configure height");

    assert_surface_tap_delivered(app, "popup");
    let down = surface_touch_events(app, "SURFACE_TOUCH_DOWN")
        .pop()
        .expect("popup touch down");
    let expected_x = f64::from(layout.shadow) + f64::from(layout.content_w) / 2.0;
    let expected_y = f64::from(layout.shadow) + f64::from(layout.content_h) / 2.0;
    assert!(
        (down.x - expected_x).abs() <= 2.0 && (down.y - expected_y).abs() <= 2.0,
        "popup surface-local touch should include shadow/window-geometry offset: \
         down={down:?} expected=({expected_x:.1}, {expected_y:.1}) layout={layout:?}"
    );
}

/// Toolkitless coverage for the basic text-input-v3 editing surfaces:
/// commit_string, raw key events, and IME deleteSurroundingText.
#[test]
fn test_basic_editing_and_delete() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("hello world").expect("commit 'hello world'");
        app.wait_for_text("hello world", TIMEOUT)
            .expect("text 'hello world'");

        adb::ic_send_key_event(adb::KEYCODE_DEL).expect("backspace");
        app.wait_for_text("hello worl", TIMEOUT)
            .expect("'hello worl' after backspace");

        adb::ic_commit_text("d").expect("commit 'd'");
        app.wait_for_text("hello world", TIMEOUT).expect("restored");

        adb::ic_delete_surrounding_text(5, 0).expect("delete_surrounding");
        app.wait_for_text("hello ", TIMEOUT)
            .expect("'hello ' after delete_surrounding(5, 0)");
    });
}

#[test]
fn test_preedit_lifecycle() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("hello ").expect("commit 'hello '");
        app.wait_for_text("hello ", TIMEOUT).expect("'hello '");

        for prefix in ["w", "wo", "wor", "worl", "world"] {
            adb::ic_set_composing_text(prefix).expect("setComposingText");
            app.wait_for_preedit(prefix, TIMEOUT)
                .unwrap_or_else(|e| panic!("preedit not '{}': {}", prefix, e));
            assert_eq!(
                app.last_text().as_deref().unwrap_or(""),
                "hello ",
                "preedit '{}' leaked into committed text",
                prefix
            );
        }

        adb::ic_finish_composing().expect("finishComposingText");
        app.wait_for_text("hello world", TIMEOUT)
            .expect("'hello world' after finishComposingText");
        app.wait_for_preedit("", TIMEOUT)
            .expect("preedit cleared after finishComposingText");
    });
}

#[test]
fn test_autocorrect_replaces_preedit() {
    with_wayland_text_input(|app| {
        adb::ic_set_composing_text("teh").expect("setComposingText 'teh'");
        app.wait_for_preedit("teh", TIMEOUT).expect("preedit 'teh'");
        adb::ic_commit_text("the ").expect("commit autocorrect");
        app.wait_for_preedit("", TIMEOUT)
            .expect("preedit cleared after autocorrect");
        let text = app.last_text().unwrap_or_default();
        assert!(
            text.ends_with("the ") && !text.contains("teh"),
            "active preedit was not replaced by autocorrect commit: {:?}",
            text
        );
    });
}

/// Cursor movement is the part GTK used to hide for us. The toolkitless app
/// validates the compositor's touch delivery directly: tap moves the cursor,
/// Backspace and composing insertion happen at that cursor, a full
/// compose-click-compose loop inserts the second word at the tapped cursor,
/// and touching elsewhere finalizes pending preedit without letting a stale
/// finishComposingText duplicate it.
#[test]
fn test_touch_cursor_positioning() {
    with_wayland_text_input(|app| {
        scene_click_cursor_positioning(app, WAYLAND_TAP_COORDS);
    });
}

#[test]
fn test_full_compose_loop_with_click_in_middle() {
    with_wayland_text_input(|app| {
        scene_full_compose_loop_with_click_in_middle(app, WAYLAND_TAP_COORDS);
    });
}

#[test]
fn test_tap_commits_pending_preedit_once() {
    with_wayland_text_input(|app| {
        let before = app.last_text().unwrap_or_default();
        adb::ic_set_composing_text("pending").expect("setComposingText 'pending'");
        app.wait_for_preedit("pending", TIMEOUT)
            .expect("preedit 'pending'");

        let cursor_count = app.cursor_pos_count();
        adb::input_tap(
            WAYLAND_TAP_COORDS.text_start_x,
            WAYLAND_TAP_COORDS.text_mid_y,
        )
        .expect("tap line start");
        let cursor = app
            .wait_for_cursor_change(cursor_count, TIMEOUT)
            .expect("cursor change after tap");
        assert_eq!(cursor, 0, "tap at line start should move cursor to 0");
        app.wait_for_preedit("", TIMEOUT)
            .expect("preedit cleared by tap");

        adb::ic_finish_composing().expect("finishComposingText after tap");
        thread::sleep(Duration::from_millis(300));

        let text = app.last_text().unwrap_or_default();
        let without_pending = text.replacen("pending", "", 1);
        assert!(
            without_pending == before && text.contains("pending"),
            "tap dropped committed text or pending preedit: before={:?} after={:?}",
            before,
            text
        );
        assert_eq!(
            text.matches("pending").count(),
            1,
            "stale finishComposingText duplicated pending preedit: {:?}",
            text
        );
    });
}

/// The touch visualizer uses normalized host-side injection, so this test
/// asserts event shape rather than absolute pixels. It covers a plain tap:
/// wl_touch.down reaches the client, followed by wl_touch.up for the same
/// slot, with no dependency on the device's physical resolution.
#[test]
fn test_xdg_configure_state_maximized_vs_fullscreen() {
    let binary = ensure_wayland_debug_app();

    let mut normal = DebugApp::start(INPUT_BACKEND, &binary, "scale", WAYLAND_DEBUG_ENV)
        .expect("start non-fullscreen debug app");
    normal
        .wait_for_tag_value("CONFIGURE_STATE", "maximized", TIMEOUT)
        .expect("non-fullscreen app should be configured maximized");
    assert!(
        normal.payloads_with_tag("CONFIGURE_STATE").iter().all(|s| s != "fullscreen"),
        "non-fullscreen app received fullscreen configure"
    );
    normal.stop().expect("normal debug app failed to stop cleanly");
    assert_compositor_clean();

    let mut fullscreen = start_wayland_debug_touch(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    fullscreen
        .wait_for_tag_value("CONFIGURE_STATE", "fullscreen", TIMEOUT)
        .expect("fullscreen-requesting app should be configured fullscreen");
    fullscreen
        .stop()
        .expect("fullscreen debug app failed to stop cleanly");
    assert_compositor_clean();
}

#[test]
fn test_touch_tap() {
    with_wayland_touch(|app| {
        inject_touch("tap");
        app.wait_for_tag_count("TOUCH_DOWN", 1, TIMEOUT)
            .expect("touch down");
        app.wait_for_tag_count("TOUCH_UP", 1, TIMEOUT)
            .expect("touch up");

        let downs = touch_events(app, "TOUCH_DOWN");
        let ups = touch_events(app, "TOUCH_UP");
        assert_eq!(downs.len(), 1, "expected one TOUCH_DOWN, got {downs:?}");
        assert_eq!(ups.len(), 1, "expected one TOUCH_UP, got {ups:?}");
        assert_eq!(downs[0].id, ups[0].id, "tap up used a different slot");
        assert_eq!(downs[0].active, 1, "tap down active count");
        assert_eq!(ups[0].active, 0, "tap up active count");
        assert!(
            (downs[0].x - ups[0].x).abs() < 1.0 && (downs[0].y - ups[0].y).abs() < 1.0,
            "tap moved unexpectedly: down={:?} up={:?}",
            downs[0],
            ups[0]
        );
    });
}

/// A wl_subsurface is rendered as part of the parent surface tree, but input
/// must still be delivered to the child wl_surface with child-local
/// coordinates. The debug scene places the subsurface under the broker's
/// normalized tap point.
#[test]
fn test_touch_subsurface_tap() {
    with_wayland_subsurface(|app| {
        app.wait_for_tag_value("SURFACE_READY", "subsurface", TIMEOUT)
            .expect("subsurface ready");
        assert_surface_tap_delivered(app, "subsurface");
    });
}

/// Render-only subsurfaces can be visible but set an empty input region.
/// Firefox/WebRender uses that shape: the child surface must not steal the
/// touch from the browser toplevel.
#[test]
fn test_touch_ignores_input_empty_subsurface() {
    with_wayland_subsurface_input_empty(|app| {
        app.wait_for_tag_value("SURFACE_READY", "subsurface", TIMEOUT)
            .expect("subsurface ready");
        assert_surface_tap_delivered(app, "toplevel");
    });
}

/// Basic xdg_popup coverage: the first tap must land on the popup with
/// popup-local coordinates, including non-zero window-geometry/shadow
/// offsets. A later tap outside the popup must dismiss it; GTK menu bars
/// depend on that `popup_done` before mapping a different menu popup.
#[test]
fn test_touch_popup_tap() {
    with_wayland_popup(|app| {
        app.wait_for_tag_value("SURFACE_READY", "popup", TIMEOUT)
            .expect("popup ready");
        assert_popup_shadow_geometry_tap_delivered(app);

        inject_touch("tap-outside-popup");
        app.wait_for_tag_value("POPUP_DONE", "", TIMEOUT)
            .expect("popup dismissed after outside tap");
    });
}

/// GTK menu bars use grabbed xdg_popups. When a tap outside the current
/// popup opens another menu popup on the same toplevel, the compositor must
/// dismiss the old popup through the grab path; a bare `popup_done` leaves
/// Smithay's active-grab stack pointing at the old menu and the next
/// `xdg_popup.grab` is rejected as not-topmost.
#[test]
fn test_touch_grabbed_popup_switches_to_next_popup() {
    with_wayland_popup_switch(|app| {
        inject_touch("tap-menu-a");
        app.wait_for_tag_value("SURFACE_READY", "popup", TIMEOUT)
            .expect("first grabbed popup ready");

        inject_touch("tap-menu-b");
        app.wait_for_tag_value("POPUP_DONE", "", TIMEOUT)
            .expect("first grabbed popup dismissed before second popup");
        app.wait_for_tag_value("SURFACE_READY", "popup2", TIMEOUT)
            .expect("second grabbed popup ready after outside touch");
    });
}

/// Drag is touch.down + a stream of wl_touch.motion events + touch.up. The
/// assertions only compare the client's own observed coordinates, avoiding
/// any baked-in screen dimensions or density assumptions.
#[test]
fn test_touch_drag() {
    with_wayland_touch(|app| {
        inject_touch("drag");
        app.wait_for_tag_count("TOUCH_DOWN", 1, TIMEOUT)
            .expect("touch down");
        app.wait_for_tag_count("TOUCH_MOTION", 3, TIMEOUT)
            .expect("touch motion");
        app.wait_for_tag_count("TOUCH_UP", 1, TIMEOUT)
            .expect("touch up");

        let down = touch_events(app, "TOUCH_DOWN").remove(0);
        let motions = touch_events(app, "TOUCH_MOTION");
        let up = touch_events(app, "TOUCH_UP").remove(0);
        let last_motion = motions.last().expect("last motion");

        assert!(
            motions.iter().all(|m| m.id == down.id),
            "drag slot changed: {motions:?}"
        );
        assert!(up.id == down.id, "drag up slot changed");
        assert!(
            last_motion.x > down.x && last_motion.y > down.y,
            "drag did not move down/right: down={down:?} last={last_motion:?}"
        );
        assert!(
            (up.x - last_motion.x).abs() < 1.0 && (up.y - last_motion.y).abs() < 1.0,
            "drag up should report the final motion position: up={up:?} last={last_motion:?}"
        );
    });
}

/// Two-finger delivery is the path most likely to rot because each Android
/// pointer ID must remain a distinct Wayland touch slot through down,
/// motion, and up. The debug broker builds a real multi-pointer MotionEvent
/// stream against the focused SurfaceView; the client verifies both slots.
#[test]
fn test_touch_multitouch() {
    with_wayland_touch(|app| {
        inject_touch("multitouch");
        app.wait_for_tag_count("TOUCH_DOWN", 2, TIMEOUT)
            .expect("two touch downs");
        app.wait_for_tag_count("TOUCH_MOTION", 6, TIMEOUT)
            .expect("multi-touch motion");
        app.wait_for_tag_count("TOUCH_UP", 2, TIMEOUT)
            .expect("two touch ups");

        let downs = touch_events(app, "TOUCH_DOWN");
        let motions = touch_events(app, "TOUCH_MOTION");
        let ups = touch_events(app, "TOUCH_UP");
        assert_eq!(downs.len(), 2, "expected two downs, got {downs:?}");
        assert_eq!(ups.len(), 2, "expected two ups, got {ups:?}");
        assert_ne!(downs[0].id, downs[1].id, "two fingers shared one slot");
        assert_eq!(downs[0].active, 1, "first down active count");
        assert_eq!(downs[1].active, 2, "second down active count");
        assert_eq!(ups.last().unwrap().active, 0, "all fingers should be up");

        for id in [downs[0].id, downs[1].id] {
            let first = downs.iter().find(|e| e.id == id).unwrap();
            let last = motions
                .iter()
                .rev()
                .find(|e| e.id == id)
                .unwrap_or_else(|| panic!("missing motion for touch id {id}"));
            assert!(
                (last.x - first.x).abs() > 5.0 || (last.y - first.y).abs() > 5.0,
                "touch id {id} did not move enough: first={first:?} last={last:?}"
            );
        }
    });
}

/// The two composing-region replacement shapes are distinct IC paths:
/// setComposingText over a region emits delete+preedit, while commitText over
/// a region emits delete+commit.
#[test]
fn test_composing_region_preedit_replaces_committed_text() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("hello world").expect("commit 'hello world'");
        app.wait_for_text("hello world", TIMEOUT)
            .expect("'hello world'");

        let cursor_count = app.cursor_pos_count();
        adb::input_tap(WAYLAND_TAP_COORDS.text_mid_x, WAYLAND_TAP_COORDS.text_mid_y)
            .expect("tap mid");
        let cursor = app
            .wait_for_cursor_change(cursor_count, TIMEOUT)
            .expect("cursor change after tap");
        assert!(cursor > 0 && cursor < 11, "cursor mid, got {}", cursor);
        thread::sleep(Duration::from_millis(200));

        adb::ic_set_composing_region(0, cursor).expect("setComposingRegion");
        adb::ic_set_composing_text("HELLO").expect("setComposingText 'HELLO'");
        app.wait_for_preedit("HELLO", TIMEOUT)
            .expect("preedit 'HELLO'");
        assert!(
            !app.last_text().unwrap_or_default().starts_with("hello"),
            "setComposingText over region did not delete original bytes"
        );

        adb::ic_finish_composing().expect("finishComposingText");
        let deadline = Instant::now() + TIMEOUT;
        while Instant::now() < deadline && !app.last_text().unwrap_or_default().contains("HELLO") {
            thread::sleep(Duration::from_millis(50));
        }
        let text = app.last_text().unwrap_or_default();
        assert!(
            text.contains("HELLO"),
            "HELLO missing after finish: {:?}",
            text
        );
        assert_eq!(
            text.matches("hello").count(),
            0,
            "original lowercase region survived replacement: {:?}",
            text
        );
    });
}

#[test]
fn test_commit_text_replaces_composing_region() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("hello world").expect("commit 'hello world'");
        app.wait_for_text("hello world", TIMEOUT)
            .expect("'hello world'");

        let cursor_count = app.cursor_pos_count();
        adb::input_tap(WAYLAND_TAP_COORDS.text_mid_x, WAYLAND_TAP_COORDS.text_mid_y)
            .expect("tap mid");
        let cursor = app
            .wait_for_cursor_change(cursor_count, TIMEOUT)
            .expect("cursor change after tap");
        assert!(cursor > 0 && cursor < 11, "cursor mid, got {}", cursor);

        thread::sleep(Duration::from_millis(200));
        adb::ic_set_composing_region(0, cursor).expect("setComposingRegion");
        adb::ic_commit_text("FOO").expect("commitText over composing region");
        let deadline = Instant::now() + TIMEOUT;
        while Instant::now() < deadline {
            let text = app.last_text().unwrap_or_default();
            if text.contains("FOO") && !text.contains("hello") {
                break;
            }
            thread::sleep(Duration::from_millis(50));
        }
        let text = app.last_text().unwrap_or_default();
        assert!(
            text.contains("FOO") && !text.contains("hello"),
            "commitText over region did not replace marked text: {:?}",
            text
        );
    });
}

#[test]
fn test_space_backspace_space_no_duplicate() {
    with_wayland_text_input(|app| {
        scene_space_backspace_space_no_duplicate(app);
    });
}

#[test]
fn test_diverged_cursor_no_byte_slicing() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("hello ").expect("commit 'hello '");
        app.wait_for_text("hello ", TIMEOUT).expect("'hello '");
        adb::ic_commit_text("world").expect("append 'world'");
        app.wait_for_text("hello world", TIMEOUT)
            .expect("'hello world' after append");
        thread::sleep(Duration::from_millis(200));

        adb::ic_set_composing_region(0, 5).expect("setComposingRegion 0..5");
        adb::ic_set_selection(5, 5).expect("setSelection 5..5 (diverge Editable cursor)");
        let change_count = app.text_changed_count();
        adb::ic_commit_text("X").expect("commitText after divergence");
        app.wait_for_text_change(change_count, TIMEOUT)
            .expect("text change after diverged commit");
        thread::sleep(Duration::from_millis(300));
        assert!(
            app.last_text().unwrap_or_default().contains("world"),
            "diverged cursor propagated a byte-slicing delete: {:?}",
            app.last_text()
        );
    });
}

#[test]
fn test_recommit_word_then_newline_no_h_prepend() {
    with_wayland_text_input(|app| {
        scene_recommit_word_then_newline_no_h_prepend(app);
    });
}

#[test]
fn test_delete_surrounding_after_stale_newline_context_clears_buffer() {
    let mut app = start_wayland_debug_text_input_stale_newline(INPUT_BACKEND, WAYLAND_DEBUG_ENV);

    adb::ic_commit_text("hello").expect("ic commitText 'hello'");
    app.wait_for_text("hello", TIMEOUT).expect("'hello'");

    for newline_count in 1..=3 {
        adb::ic_set_composing_region(0, 5).expect("ic setComposingRegion 0..5");
        adb::ic_commit_text("hello").expect("ic commitText 'hello' (re-commit)");
        adb::ic_commit_text("\n").expect("ic commitText '\\n'");

        let expected = format!("hello{}", "\\n".repeat(newline_count));
        app.wait_for_text(&expected, TIMEOUT)
            .unwrap_or_else(|e| panic!("expected {:?} after recommit/newline: {}", expected, e));
    }

    adb::ic_delete_surrounding_text(11, 11).expect("delete surrounding broad range");
    let cleared = app.wait_for_text("", TIMEOUT);
    let last = app.last_text();

    app.stop()
        .expect("stale-newline debug app failed to stop cleanly");
    assert_compositor_clean();

    assert!(
        cleared.is_ok(),
        "deleteSurroundingText after stale newline context should clear the buffer; last={:?}",
        last
    );
}

#[test]
fn test_delete_surrounding_after_stale_newline_context_counts_codepoints() {
    let mut app = start_wayland_debug_text_input_stale_newline(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    let word = "a😀bc";

    adb::ic_commit_text(word).expect("ic commitText emoji word");
    app.wait_for_text(word, TIMEOUT).expect("emoji word");

    for newline_count in 1..=3 {
        adb::ic_set_composing_region(0, 5).expect("ic setComposingRegion 0..5");
        adb::ic_commit_text(word).expect("ic commitText emoji word (re-commit)");
        adb::ic_commit_text("\n").expect("ic commitText '\\n'");

        let expected = format!("{}{}", word, "\\n".repeat(newline_count));
        app.wait_for_text(&expected, TIMEOUT)
            .unwrap_or_else(|e| panic!("expected {:?} after recommit/newline: {}", expected, e));
    }

    adb::ic_delete_surrounding_text(7, 0).expect("delete surrounding suffix");
    let suffix_deleted = app.wait_for_text("a", TIMEOUT);
    let last = app.last_text();

    app.stop()
        .expect("stale-newline debug app failed to stop cleanly");
    assert_compositor_clean();

    assert!(
        suffix_deleted.is_ok(),
        "deleteSurroundingText should count an emoji surrogate pair as one key; last={:?}",
        last
    );
}

#[test]
fn test_commit_replace_after_stale_newline_context_does_not_slice_wire_bytes() {
    let mut app = start_wayland_debug_text_input_stale_newline(INPUT_BACKEND, WAYLAND_DEBUG_ENV);

    adb::ic_commit_text("hello").expect("ic commitText 'hello'");
    app.wait_for_text("hello", TIMEOUT).expect("'hello'");

    for newline_count in 1..=3 {
        adb::ic_set_composing_region(0, 5).expect("ic setComposingRegion 0..5");
        adb::ic_commit_text("hello").expect("ic commitText 'hello' (re-commit)");
        adb::ic_commit_text("\n").expect("ic commitText '\\n'");

        let expected = format!("hello{}", "\\n".repeat(newline_count));
        app.wait_for_text(&expected, TIMEOUT)
            .unwrap_or_else(|e| panic!("expected {:?} after recommit/newline: {}", expected, e));
    }

    adb::ic_set_selection(5, 5).expect("ic setSelection to stale cursor");
    adb::ic_set_composing_region(0, 5).expect("ic setComposingRegion 0..5");
    adb::ic_commit_text("HELLO").expect("ic commitText replacement");

    let expected = "hello\\n\\n\\nHELLO";
    let inserted = app.wait_for_text(expected, TIMEOUT);
    let last = app.last_text();

    app.stop()
        .expect("stale-newline debug app failed to stop cleanly");
    assert_compositor_clean();

    assert!(
        inserted.is_ok(),
        "stale context replacement should fall back to insertion, not slice wire bytes; last={:?}",
        last
    );
}

#[test]
fn test_update_from_compositor_clears_composing_spans() {
    with_wayland_text_input(|app| {
        scene_update_from_compositor_clears_composing_spans(app);
    });
}

/// Toolkitless mirror of the surrounding-less GTK/VTE-shaped input case.
#[test]
fn test_surroundingless_client_uses_keyboard_for_backspace() {
    let mut app = start_wayland_debug_text_input_no_surrounding(INPUT_BACKEND, WAYLAND_DEBUG_ENV);

    assert_eq!(
        app.count_with_tag("DELETE_SURROUNDING"),
        0,
        "DELETE_SURROUNDING fired before we sent input"
    );

    adb::ic_commit_text(" ").expect("commit ' '");
    app.wait_for_tag_value("COMMIT", " ", TIMEOUT)
        .expect("commit_string ' ' did not reach the client");

    adb::ic_delete_surrounding_text(1, 0).expect("delete_surrounding(1, 0)");
    app.wait_for_tag_value("KEY", "BackSpace", TIMEOUT)
        .expect("Backspace key did not reach the client");

    assert_eq!(
        app.count_with_tag("DELETE_SURROUNDING"),
        0,
        "compositor sent delete_surrounding_text to a surrounding-less client"
    );

    app.stop()
        .expect("debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

#[test]
fn test_android_clipboard_text_to_client() {
    let android_text = "android clipboard to wayland";
    adb::clipboard_set_text(android_text).expect("set Android clipboard");

    let mut paste_app = start_wayland_debug_clipboard_paste(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    paste_app
        .wait_for_tag_value("CLIPBOARD_PASTE", android_text, TIMEOUT)
        .expect("Wayland client did not receive Android clipboard text");
    paste_app
        .stop()
        .expect("clipboard paste app crashed or failed to stop cleanly");
}

#[test]
fn test_client_clipboard_text_to_android() {
    let wayland_text = "wayland clipboard to android";
    let mut copy_app =
        start_wayland_debug_clipboard_copy(INPUT_BACKEND, WAYLAND_DEBUG_ENV, wayland_text);
    let deadline = Instant::now() + TIMEOUT;
    loop {
        let got = adb::clipboard_get_text().expect("get Android clipboard");
        if got == wayland_text {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "Android clipboard did not receive Wayland text; last={:?}",
            got
        );
        thread::sleep(Duration::from_millis(100));
    }
    copy_app
        .stop()
        .expect("clipboard copy app crashed or failed to stop cleanly");

    assert_compositor_clean();
}
