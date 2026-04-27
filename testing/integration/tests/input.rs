//! Input dispatch tests. Drive the gtk4-debug-app through compositor input
//! paths (text-input-v3 commits, key events, touch taps) and assert the
//! debug app observes the expected client-side behaviour.
//!
//! Deliberately avoid asserting anything about buffer types (AHB vs
//! SHM): the point is how the compositor *dispatches input*, not how
//! clients render. That keeps these tests safe to run on the emulator
//! (where libhybris/AHB is unavailable).

use tawc_integration::adb;
use tawc_integration::helpers::{assert_compositor_clean, start_text_input, TIMEOUT};

// Physical screen coordinates for tapping inside the text view.
// Compositor uses 2x scale, so logical = physical / 2.
// Text content starts at approximately physical (80, 234).
// Monospace 18pt ≈ 22 physical px per character.
const TAP_TEXT_MID_X: u32 = 200;
const TAP_TEXT_MID_Y: u32 = 250;

/// Env passed to gtk4-debug-app for input tests. Currently empty — i.e.
/// GTK4 uses its default GSK renderer (Vulkan/GL via libhybris on device).
/// We'd rather use `GSK_RENDERER=cairo` so this group is buffer-path
/// independent and runs on the emulator too, but the cairo path currently
/// dies right after the first frame with "the target surface has been
/// finished" — see issues/gtk4-cairo-renderer-broken.md.
const INPUT_ENV: &str = "";

#[test]
fn test_text_input_and_backspace() {
    let mut app = start_text_input(INPUT_ENV);

    // Type multi-word text (covers basic input + spaces)
    adb::input_text("hello world").expect("Failed to send text");
    app.wait_for_text("hello world", TIMEOUT)
        .expect("Text should be 'hello world'");

    // Backspace should delete last character
    adb::input_keyevent(adb::KEYCODE_DEL).expect("Failed to send backspace");
    app.wait_for_text("hello worl", TIMEOUT)
        .expect("Text should be 'hello worl' after backspace");

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// Build up a word with `setComposingText`, finalize with
/// `finishComposingText`. Verifies the basic compose loop that Gboard runs
/// on every word — preedit shown letter by letter, then committed when the
/// user stops typing.
#[test]
fn test_compose_then_finish() {
    let mut app = start_text_input(INPUT_ENV);

    for prefix in ["h", "he", "hel", "hell", "hello"] {
        adb::input_set_composing(prefix).expect("Failed to set composing");
        app.wait_for_preedit(prefix, TIMEOUT)
            .unwrap_or_else(|e| panic!("Preedit not '{}': {}", prefix, e));
    }

    // Editor's main text is still empty — preedit is not committed yet.
    assert_eq!(
        app.last_text().as_deref().unwrap_or(""),
        "",
        "Preedit should not appear in the text buffer until finishComposingText"
    );

    adb::input_finish_composing().expect("Failed to finish composing");
    app.wait_for_text("hello", TIMEOUT)
        .expect("Text should be 'hello' after finishComposingText");
    app.wait_for_preedit("", TIMEOUT)
        .expect("Preedit should be cleared after finishComposingText");

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// Autocorrect flow: user typed a word (preedit), then a corrected word is
/// committed on top of the preedit. This is what Gboard does when you tap a
/// suggestion or hit space on a misspelling — `commitText("the ")` while the
/// preedit is "teh", and the protocol's done ordering replaces the preedit
/// with the commit string.
#[test]
fn test_autocorrect_replaces_preedit() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_set_composing("teh").expect("Failed to set composing");
    app.wait_for_preedit("teh", TIMEOUT)
        .expect("Preedit should be 'teh'");

    // commitText("the "). Per text-input-v3 done ordering, the existing
    // preedit ("teh") is replaced by the cursor first, *then* the commit
    // string is inserted. The user should see "the " — not "tehthe ".
    adb::input_text("the ").expect("Failed to commit replacement");
    app.wait_for_text("the ", TIMEOUT)
        .expect("Autocorrect should have replaced 'teh' with 'the '");
    app.wait_for_preedit("", TIMEOUT)
        .expect("Preedit should be cleared after the commit");

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// Build several committed words, then start a new preedit at the end and
/// verify it doesn't disrupt the prior committed text. Catches regressions
/// where preedit replacement accidentally eats already-committed characters.
#[test]
fn test_compose_after_committed_text() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_text("hello ").expect("Failed to commit 'hello '");
    app.wait_for_text("hello ", TIMEOUT).expect("");

    adb::input_set_composing("wor").expect("Failed to set composing");
    app.wait_for_preedit("wor", TIMEOUT).expect("preedit=wor");
    // Committed text should still be there alongside the preedit.
    assert_eq!(
        app.last_text().as_deref().unwrap_or(""),
        "hello ",
        "Setting a new preedit should not change committed text"
    );

    adb::input_set_composing("world").expect("Failed to extend composing");
    app.wait_for_preedit("world", TIMEOUT).expect("preedit=world");
    adb::input_finish_composing().expect("Failed to finish composing");
    app.wait_for_text("hello world", TIMEOUT)
        .expect("Final text should be 'hello world'");

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// Compose, click in the middle of *committed* text, then continue
/// composing. The new preedit should land at the new cursor position, not
/// at the end. Regression test for the Editable-drift bug where Gboard's
/// internal cursor diverges from the Wayland client's after a touch.
#[test]
fn test_compose_after_cursor_move() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_text("abcdef").expect("Failed to type 'abcdef'");
    app.wait_for_text("abcdef", TIMEOUT).expect("");

    let cursor_count_before = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("Failed to tap");
    let cursor_pos = app
        .wait_for_cursor_change(cursor_count_before, TIMEOUT)
        .expect("Click did not produce CURSOR_POS event");
    assert!(
        cursor_pos > 0 && cursor_pos < 6,
        "Cursor should be in the middle of 'abcdef', got {}",
        cursor_pos
    );

    // Type "X" via composing-text path (Gboard does this on every keystroke).
    adb::input_set_composing("X").expect("Failed to set composing 'X'");
    app.wait_for_preedit("X", TIMEOUT)
        .expect("Preedit 'X' should be visible");
    adb::input_finish_composing().expect("Failed to finish composing");

    // Wait for the text to settle including the inserted character.
    let expected_len = 7;
    let deadline = std::time::Instant::now() + TIMEOUT;
    loop {
        if let Some(t) = app.last_text() {
            if t.len() == expected_len && t.contains('X') {
                break;
            }
        }
        if std::time::Instant::now() > deadline {
            panic!(
                "Timeout waiting for X-inserted text. Last: {:?}",
                app.last_text()
            );
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }

    let text = app.last_text().expect("text");
    let x_pos = text.find('X').expect("X must be in the text");
    assert_eq!(
        x_pos, cursor_pos as usize,
        "X should be inserted at the cursor position {}, got {} in '{}'",
        cursor_pos, x_pos, text
    );

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// **Regression test for the "stale preedit re-committed after click" bug.**
///
/// 1. User types "hello" via setComposingText (no commit yet) — compositor's
///    `current_preedit` is "hello".
/// 2. User taps elsewhere. The Wayland client moves its cursor and reports
///    the change with `cause=other`.
/// 3. The IME issues `finishComposingText`. Without the fix, the compositor
///    re-commits "hello" at the new cursor position because it still thinks
///    the preedit is active client-side. With the fix, `current_preedit` is
///    cleared on `cause=other` so finishComposingText is a no-op.
#[test]
fn test_finish_composing_after_click_no_duplicate() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_set_composing("hello").expect("Failed to set composing");
    app.wait_for_preedit("hello", TIMEOUT)
        .expect("Preedit should be 'hello'");

    // Tap. GTK4 doesn't auto-commit the preedit — it stays — but the
    // cursor moves and the client reports cause=other surrounding text.
    let cursor_count_before = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("Failed to tap");
    app.wait_for_cursor_change(cursor_count_before, TIMEOUT)
        .expect("Tap should produce a cursor change");

    // finishComposingText: with the fix, current_preedit was cleared by
    // the cause=other commit, so finish is a no-op. Without the fix, it
    // would commit "hello" again at the new cursor.
    adb::input_finish_composing().expect("Failed to finish composing");
    std::thread::sleep(std::time::Duration::from_millis(300));

    // The buffer should NOT contain "hellohello" or any duplicated copies
    // of the preedit text.
    let text = app.last_text().unwrap_or_default();
    let hello_count = text.matches("hello").count();
    assert!(
        hello_count <= 1,
        "finishComposingText after click duplicated the preedit: \
         got '{}' with {} copies of 'hello'",
        text,
        hello_count
    );

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// **Regression test for the `setComposingRegion` + `setComposingText`
/// duplicate-text bug.**
///
/// Gboard's "tap to retype" flow:
///   1. user has typed "hello world",
///   2. user clicks inside a word,
///   3. Gboard calls `setComposingRegion(start, end)` to mark the word as
///      composing — it's still committed text on the Wayland side, just
///      annotated as composing on the Android side,
///   4. Gboard calls `setComposingText("...", 1)` to replace the marked
///      region with new preedit text.
///
/// We simulate (3)+(4) via `input_set_composing_with_delete`, which is
/// exactly the JNI call `TawcInputConnection.setComposingText` makes for
/// the same Gboard sequence. Without the fix, the compositor only sends
/// `preedit_string` (no delete) and the original word stays in the buffer.
/// With the fix, the original word is deleted before the preedit is set.
#[test]
fn test_compose_region_replaces_committed_text() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_text("hello world").expect("Failed");
    app.wait_for_text("hello world", TIMEOUT)
        .expect("Initial text 'hello world'");

    // Move cursor to position 5 (between hello and  world).
    let cursor_count = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("tap");
    let cursor = app
        .wait_for_cursor_change(cursor_count, TIMEOUT)
        .expect("cursor move");
    assert!(cursor > 0 && cursor < 11, "cursor in middle, got {}", cursor);

    // Replace [cursor-cursor, cursor] (i.e. "hello"-cursor chars before)
    // with preedit "HELLO". Specifically: set composing region from start
    // of word (assume offset 0) to cursor — that's `cursor` chars before.
    let before = cursor;
    let after = 0u32;
    adb::input_set_composing_with_delete("HELLO", before, after).expect("setComposingText");
    app.wait_for_preedit("HELLO", TIMEOUT)
        .expect("Preedit should be 'HELLO'");

    // The committed text should no longer have the chars we replaced.
    let committed_now = app.last_text().expect("text");
    assert!(
        !committed_now.starts_with("hello"),
        "Replacement should have deleted committed bytes; got '{}'",
        committed_now
    );

    // Commit.
    adb::input_finish_composing().expect("finishComposingText");

    // Final text: original "hello world" with "hello" portion (or as much
    // as `before` chars covers) replaced by "HELLO".
    // The exact prefix replaced depends on cursor position; assert no
    // duplication of "hello".
    let deadline = std::time::Instant::now() + TIMEOUT;
    let mut last = String::new();
    while std::time::Instant::now() < deadline {
        last = app.last_text().unwrap_or_default();
        if last.contains("HELLO") {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    assert!(
        last.contains("HELLO"),
        "Replacement HELLO should be in buffer; got '{}'",
        last
    );
    assert_eq!(
        last.matches("hello").count(),
        0,
        "Original 'hello' should have been replaced; got '{}'",
        last
    );

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// Same as above but committing directly (no intermediate preedit) —
/// matches Gboard's autocorrect-replace flow. Catches the case where
/// `commitText` over a composing region must also delete the original
/// committed bytes.
#[test]
fn test_commit_text_replaces_composing_region() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_text("hello world").expect("Failed");
    app.wait_for_text("hello world", TIMEOUT).expect("");

    let cursor_count = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("tap");
    let cursor = app
        .wait_for_cursor_change(cursor_count, TIMEOUT)
        .expect("cursor move");
    assert!(cursor > 0 && cursor < 11, "cursor in middle, got {}", cursor);

    // Replace `cursor` chars before the cursor with "FOO".
    adb::input_text_with_delete("FOO", cursor, 0).expect("commitText replacement");

    let deadline = std::time::Instant::now() + TIMEOUT;
    let mut last = String::new();
    while std::time::Instant::now() < deadline {
        last = app.last_text().unwrap_or_default();
        if last.contains("FOO") && !last.contains("hello") {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    assert!(
        last.contains("FOO"),
        "Replacement FOO should be in buffer; got '{}'",
        last
    );
    assert_eq!(
        last.matches("hello").count(),
        0,
        "Original 'hello' should have been replaced; got '{}'",
        last
    );

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// **Realistic typing-then-correction flow.** Builds up several committed
/// words via the compose loop, clicks to position the cursor mid-text,
/// composes a new word at the new cursor, finishes composing. Catches
/// regressions in the full Gboard flow that simple per-feature tests miss.
#[test]
fn test_full_compose_loop_with_click_in_middle() {
    let mut app = start_text_input(INPUT_ENV);

    // Word 1: typed letter by letter as preedit, finished with space.
    for prefix in ["h", "he", "hel", "hell", "hello"] {
        adb::input_set_composing(prefix).expect("");
    }
    adb::input_finish_composing().expect("");
    adb::input_text(" ").expect("");
    app.wait_for_text("hello ", TIMEOUT).expect("");

    // Word 2.
    for prefix in ["w", "wo", "wor", "worl", "world"] {
        adb::input_set_composing(prefix).expect("");
    }
    adb::input_finish_composing().expect("");
    app.wait_for_text("hello world", TIMEOUT).expect("");

    // Click mid-text. Cursor should land somewhere inside the existing text.
    let cursor_count = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("");
    let cursor = app
        .wait_for_cursor_change(cursor_count, TIMEOUT)
        .expect("Tap should change cursor");
    assert!(cursor > 0 && cursor < 11, "cursor in middle, got {}", cursor);

    // Compose a new word at the cursor.
    for prefix in ["x", "xy", "xyz"] {
        adb::input_set_composing(prefix).expect("");
    }
    adb::input_finish_composing().expect("");

    // The final text should be exactly "hello world" with "xyz" inserted
    // at the cursor position. Total length 14 (11 + 3).
    let deadline = std::time::Instant::now() + TIMEOUT;
    let mut last = String::new();
    while std::time::Instant::now() < deadline {
        last = app.last_text().unwrap_or_default();
        if last.len() == 14 && last.contains("xyz") {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    assert_eq!(
        last.len(),
        14,
        "Expected len 14 (11+3), got '{}' (len {})",
        last,
        last.len()
    );
    let xyz_pos = last.find("xyz").expect("xyz in text");
    assert_eq!(
        xyz_pos, cursor as usize,
        "xyz should be at cursor pos {} but landed at {}",
        cursor, xyz_pos
    );
    // No word from the original "hello world" should appear twice.
    // (They may legitimately appear ZERO times if the cursor split them.)
    assert!(
        last.matches("hello").count() <= 1,
        "'hello' duplicated in '{}'",
        last
    );
    assert!(
        last.matches("world").count() <= 1,
        "'world' duplicated in '{}'",
        last
    );

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// **Regression test for "active word moves with the cursor on click".**
///
/// When a preedit is active and the user clicks elsewhere, GTK4 keeps
/// rendering the preedit at the (now-moved) cursor position. Without
/// the fix, the compositor only clears its own `current_preedit` state
/// on `cause=other` and never tells the client to drop its preedit, so
/// the "currently active word" visually follows the cursor.
///
/// The fix: on `cause=other`, the compositor sends
/// `preedit_string(None) + done` to the client. The client clears its
/// preedit (and emits `preedit-changed("")` in GTK), so the word stops
/// shadowing the cursor. The text-being-composed is lost, matching how
/// every desktop text widget treats a click during composition.
///
/// We assert two things:
/// 1. The buffer is unchanged by the click (the preedit was not silently
///    committed at the new cursor location).
/// 2. The client emits an empty PREEDIT event, confirming the preedit
///    overlay is gone.
#[test]
fn test_click_during_preedit_clears_preedit() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_text("world").expect("commit world");
    app.wait_for_text("world", TIMEOUT).expect("");

    adb::input_set_composing("hello").expect("set composing hello");
    app.wait_for_preedit("hello", TIMEOUT)
        .expect("preedit 'hello' should be visible");
    // Sanity: preedit is overlay only — the buffer still has only "world".
    assert_eq!(
        app.last_text().as_deref().unwrap_or(""),
        "world",
        "Preedit should not be in the buffer before any commit"
    );

    let cursor_count_before = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("tap");
    app.wait_for_cursor_change(cursor_count_before, TIMEOUT)
        .expect("Tap should change the cursor");

    // The preedit overlay must be cleared on the client side.
    app.wait_for_preedit("", TIMEOUT)
        .expect("Preedit should be cleared by the click");

    // The buffer must not silently gain "hello" from the click — clicking
    // during a preedit discards the in-progress word, it doesn't commit it.
    let text = app.last_text().unwrap_or_default();
    assert_eq!(
        text, "world",
        "Click during preedit should not commit; got buffer '{}'",
        text
    );

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

/// Test deleteSurroundingText: Gboard's path for trimming chars without
/// using a key event (e.g. word-correcting backspace, suggestion deletion).
#[test]
fn test_delete_surrounding_text() {
    let mut app = start_text_input(INPUT_ENV);

    adb::input_text("hello world").expect("Failed");
    app.wait_for_text("hello world", TIMEOUT).expect("");

    // Delete the trailing "world" via deleteSurroundingText(5, 0).
    adb::input_delete_surrounding(5, 0).expect("Failed to delete surrounding");
    app.wait_for_text("hello ", TIMEOUT)
        .expect("Text should be 'hello ' after deleting 5 chars before cursor");

    app.stop().expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}

#[test]
fn test_click_cursor_positioning() {
    let mut app = start_text_input(INPUT_ENV);

    // Type text
    adb::input_text("abcdef").expect("Failed to send text");
    app.wait_for_text("abcdef", TIMEOUT)
        .expect("Text should be 'abcdef'");

    // Click in the middle - should produce CURSOR_POS events and move cursor
    let cursor_count_before = app.cursor_pos_count();
    adb::input_tap(TAP_TEXT_MID_X, TAP_TEXT_MID_Y).expect("Failed to tap");

    let cursor_pos = app
        .wait_for_cursor_change(cursor_count_before, TIMEOUT)
        .expect("Click did not produce any CURSOR_POS events");
    assert!(
        cursor_pos > 0 && cursor_pos < 6,
        "Cursor should be in middle, got position {}",
        cursor_pos
    );

    // Clicking should not have changed the text
    let text = app.last_text().expect("No text after click");
    assert_eq!(text, "abcdef", "Click changed text content to '{}'", text);

    // Backspace should delete from cursor position, not from end
    let change_count = app.text_changed_count();
    adb::input_keyevent(adb::KEYCODE_DEL).expect("Failed to send backspace");
    let text = app
        .wait_for_text_change(change_count, TIMEOUT)
        .expect("No TEXT_CHANGED after backspace");
    assert_ne!(
        text, "abcde",
        "Backspace deleted from end instead of cursor position"
    );
    assert_eq!(
        text.len(),
        5,
        "Expected 5 characters after deleting one from 'abcdef', got '{}'",
        text
    );

    // Type at cursor position - should insert in middle, not at end
    let change_count = app.text_changed_count();
    adb::input_text("X").expect("Failed to send text");
    let text = app
        .wait_for_text_change(change_count, TIMEOUT)
        .expect("No TEXT_CHANGED after typing X");
    assert!(
        !text.ends_with("X") || text.len() != 6,
        "Typed 'X' was appended to end instead of inserted at cursor position, got '{}'",
        text
    );

    app.stop()
        .expect("Debug app crashed or failed to stop cleanly");
    assert_compositor_clean();
}
