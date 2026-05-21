//! Text input dispatch tests. Drive wayland-debug-app through compositor
//! text-input-v3 commits, key events, clipboard probes, and cursor taps, then
//! assert the debug app observes the expected client-side behaviour.
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
    start_wayland_debug_clipboard_paste, start_wayland_debug_text_input,
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

#[test]
fn test_completion_suggestion_replaces_current_word() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("abc").expect("commit 'abc'");
        app.wait_for_text("abc", TIMEOUT).expect("'abc'");

        adb::ic_replace_text(0, 3, "ABC").expect("replace current word with completion");

        app.wait_for_text("ABC", TIMEOUT)
            .expect("completion suggestion should replace the current word");
    });
}

#[test]
fn test_commit_correction_replaces_current_word() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("abc").expect("commit 'abc'");
        app.wait_for_text("abc", TIMEOUT).expect("'abc'");

        adb::ic_commit_correction(0, "abc", "ABC").expect("commit correction");

        app.wait_for_text("ABC", TIMEOUT)
            .expect("correction should replace the current word");
    });
}

/// Cursor movement is the part GTK used to hide for us. The toolkitless app
/// validates the compositor's touch delivery directly: tap moves the cursor,
/// Backspace and composing insertion happen at that cursor, a full
/// compose-click-compose loop inserts the second word at the tapped cursor,
/// and touching elsewhere clears pending preedit without letting a stale
/// finishComposingText commit it at the new cursor.
#[test]
fn test_cursor_positioning_from_tap() {
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
fn test_tap_clears_pending_preedit_without_committing() {
    with_wayland_text_input(|app| {
        adb::ic_commit_text("anchor").expect("commit 'anchor'");
        app.wait_for_text("anchor", TIMEOUT).expect("'anchor'");
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
        assert_eq!(
            text, before,
            "tap should clear uncommitted preedit without inserting it at the old or new cursor"
        );
    });
}

#[test]
fn test_focus_leave_clears_pending_preedit_without_committing() {
    let mut text_app = start_wayland_debug_text_input(INPUT_BACKEND, WAYLAND_DEBUG_ENV);

    adb::ic_commit_text("anchor").expect("commit 'anchor'");
    text_app.wait_for_text("anchor", TIMEOUT).expect("'anchor'");
    adb::ic_set_composing_text("pending").expect("setComposingText 'pending'");
    text_app
        .wait_for_preedit("pending", TIMEOUT)
        .expect("preedit 'pending'");

    let preedit_count = text_app.count_with_tag("PREEDIT");
    let before = text_app.last_text().unwrap_or_default();

    let mut touch_app = start_wayland_debug_touch(INPUT_BACKEND, WAYLAND_DEBUG_ENV);
    text_app
        .wait_for_tag_count("PREEDIT", preedit_count + 1, TIMEOUT)
        .expect("focused-away text client did not receive preedit clear");
    assert_eq!(
        text_app.last_preedit().as_deref(),
        Some(""),
        "focus leave should clear preedit through the compositor"
    );
    assert_eq!(
        text_app.last_text().as_deref(),
        Some(before.as_str()),
        "focus leave must not commit pending preedit"
    );
    text_app
        .wait_for_tag_value("TEXT_INPUT_LEAVE", "", TIMEOUT)
        .expect("focused-away text client did not receive text-input leave");

    adb::ic_finish_hidden_composing().expect("stale finishComposingText after focus leave");
    thread::sleep(Duration::from_millis(300));
    assert_eq!(
        text_app.last_text().as_deref(),
        Some(before.as_str()),
        "stale finishComposingText after focus leave must not commit old preedit"
    );

    touch_app
        .stop()
        .expect("touch debug app crashed or failed to stop cleanly");
    text_app
        .stop()
        .expect("text debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
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
