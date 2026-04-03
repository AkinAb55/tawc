use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

use tawc_integration::{adb, chroot, compositor, debug_app::DebugApp};

/// One-time setup: ensure compositor running, deps installed, debug app built.
fn setup() -> String {
    static BINARY_PATH: OnceLock<String> = OnceLock::new();
    BINARY_PATH
        .get_or_init(|| {
            compositor::ensure_running().expect("Failed to ensure compositor is running");
            chroot::ensure_build_deps().expect("Failed to install build deps");
            chroot::build_debug_app().expect("Failed to build debug app")
        })
        .clone()
}

/// Start the text-input debug app and wait for it to be ready.
fn start_text_input() -> DebugApp {
    let binary = setup();
    let app = DebugApp::start(&binary, "text-input").expect("Failed to start debug app");
    app.wait_ready().expect("Debug app did not become ready");
    thread::sleep(Duration::from_millis(500));
    app
}

// Physical screen coordinates for tapping inside the text view.
// The GTK window has a text view inside a GtkScrolledWindow with margins:
//   top=80, bottom=40, left=40, right=40 (logical pixels).
// The scrolled window adds ~37px internal offset before the text view content.
// So text content starts at approximately logical (40, 117) = physical (80, 234).
// Compositor uses 2x scale, so logical = physical / 2.
// Monospace 18pt ≈ 11 logical px per character = 22 physical px per character.

/// Tap near the very start of the text (first character position).
const TAP_TEXT_START_X: u32 = 90;
const TAP_TEXT_START_Y: u32 = 250;

/// Tap roughly in the middle of a line of ~10 characters.
const TAP_TEXT_MID_X: u32 = 200;
const TAP_TEXT_MID_Y: u32 = 250;

#[test]
fn test_click_moves_cursor_to_start() {
    let app = start_text_input();

    // Type text - cursor ends up at position 6 (end of "abcdef")
    adb::input_text("abcdef").expect("Failed to send text");
    thread::sleep(Duration::from_millis(1000));

    let text = app.last_text().expect("No text received");
    assert_eq!(text, "abcdef", "Setup: text should be 'abcdef'");

    // Tap near the start of the text to move cursor to the beginning
    adb::input_tap(TAP_TEXT_START_X, TAP_TEXT_START_Y).expect("Failed to tap");
    thread::sleep(Duration::from_millis(500));

    // Now type "X" - if the click moved the cursor, X should NOT be at the end
    adb::input_text("X").expect("Failed to send text");
    thread::sleep(Duration::from_millis(1000));

    let text = app.last_text().expect("No text after insert");
    // If clicking worked, "X" would be inserted near the start: "Xabcdef" or "aXbcdef" etc.
    // If clicking did nothing, cursor stayed at end: "abcdefX"
    assert_ne!(
        text, "abcdefX",
        "Click at start of text did not move cursor - 'X' was appended to end instead of inserted at click position"
    );
}

#[test]
fn test_click_moves_cursor_mid_text() {
    let app = start_text_input();

    // Type text with a known pattern
    adb::input_text("AABBCCDD").expect("Failed to send text");
    thread::sleep(Duration::from_millis(1000));

    let text = app.last_text().expect("No text received");
    assert_eq!(text, "AABBCCDD", "Setup: text should be 'AABBCCDD'");

    // Tap in the middle of the text
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("Failed to tap");
    thread::sleep(Duration::from_millis(500));

    // Type "XX"
    adb::input_text("XX").expect("Failed to send text");
    thread::sleep(Duration::from_millis(1000));

    let text = app.last_text().expect("No text after insert");
    // If clicking worked, "XX" would be inserted somewhere in the middle
    // If clicking did nothing, cursor stayed at end: "AABBCCDDXX"
    assert_ne!(
        text, "AABBCCDDXX",
        "Click in middle of text did not move cursor - 'XX' was appended to end instead of inserted at click position"
    );
}

#[test]
fn test_click_does_not_change_text_content() {
    let app = start_text_input();

    // Type some text
    adb::input_text("hello world").expect("Failed to send text");
    thread::sleep(Duration::from_millis(1500));

    let text_before = app.last_text().expect("No text received");
    assert_eq!(text_before, "hello world", "Setup: text should be 'hello world'");

    let change_count_before = app.text_changed_count();

    // Tap at the start of the text - this should ONLY move the cursor,
    // never change the text content
    adb::input_tap(TAP_TEXT_START_X, TAP_TEXT_START_Y).expect("Failed to tap");
    thread::sleep(Duration::from_millis(500));

    // Tap in the middle
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("Failed to tap");
    thread::sleep(Duration::from_millis(500));

    // Tap at the start again
    adb::input_tap(TAP_TEXT_START_X, TAP_TEXT_START_Y).expect("Failed to tap");
    thread::sleep(Duration::from_millis(500));

    let text_after = app.last_text().expect("No text after clicks");
    let change_count_after = app.text_changed_count();

    // Clicking should never alter text content
    assert_eq!(
        text_before, text_after,
        "Clicking inside text view changed the text content! Before: '{}', After: '{}'",
        text_before, text_after
    );

    // No TEXT_CHANGED events should have fired from clicks alone
    assert_eq!(
        change_count_before, change_count_after,
        "Clicking produced {} spurious TEXT_CHANGED events (text went from '{}' to '{}')",
        change_count_after - change_count_before,
        text_before,
        text_after
    );
}

#[test]
fn test_click_reports_cursor_position_change() {
    let app = start_text_input();

    // Type text
    adb::input_text("testing cursor").expect("Failed to send text");
    thread::sleep(Duration::from_millis(1000));

    let text = app.last_text().expect("No text received");
    assert_eq!(text, "testing cursor", "Setup: text should be 'testing cursor'");

    // Record cursor state
    let cursor_count_before = app.cursor_pos_count();

    // Tap at the start of the text
    adb::input_tap(TAP_TEXT_START_X, TAP_TEXT_START_Y).expect("Failed to tap");
    thread::sleep(Duration::from_millis(500));

    // We should have received at least one CURSOR_POS event from the click
    let cursor_count_after = app.cursor_pos_count();
    assert!(
        cursor_count_after > cursor_count_before,
        "Click did not produce any CURSOR_POS events - the click was ignored by the text view"
    );

    // The cursor should have moved away from the end (position 14)
    let cursor_pos = app.last_cursor_pos().expect("No cursor position reported");
    assert_ne!(
        cursor_pos, 14,
        "Cursor position is still at end ({}) after clicking at start of text - click had no effect",
        cursor_pos
    );
}
