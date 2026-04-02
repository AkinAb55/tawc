use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

use tawc_integration::{adb, chroot, compositor, debug_app::DebugApp};

/// One-time setup: ensure compositor running, deps installed, debug app built.
/// Returns the path to the debug app binary inside the chroot.
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
    // Brief pause for text_input_v3 enable to propagate
    thread::sleep(Duration::from_millis(500));
    app
}

#[test]
fn test_basic_text_input() {
    let app = start_text_input();

    adb::input_text("hello").expect("Failed to send text");

    let result = app.wait_for("TEXT_CHANGED:", Duration::from_secs(5));
    assert!(result.is_ok(), "No TEXT_CHANGED received: {:?}", result);

    // The final text should contain "hello"
    // (may arrive as multiple TEXT_CHANGED events as characters are delivered)
    let text = app.last_text().expect("No text received");
    assert_eq!(text, "hello", "Expected 'hello', got '{}'", text);
}

#[test]
fn test_backspace() {
    let app = start_text_input();

    adb::input_text("abc").expect("Failed to send text");
    app.wait_for("TEXT_CHANGED:", Duration::from_secs(5))
        .expect("No TEXT_CHANGED after typing");

    // Wait for all characters to arrive
    thread::sleep(Duration::from_millis(500));

    // Send backspace (KEYCODE_DEL = 67)
    adb::input_keyevent(adb::KEYCODE_DEL).expect("Failed to send backspace");

    // Wait for the text to update
    thread::sleep(Duration::from_millis(1000));

    let text = app.last_text().expect("No text after backspace");
    assert_eq!(text, "ab", "Expected 'ab' after backspace, got '{}'", text);
}

#[test]
fn test_multiple_words() {
    let app = start_text_input();

    adb::input_text("hello world").expect("Failed to send text");

    // Wait for text to arrive
    thread::sleep(Duration::from_millis(2000));

    let text = app.last_text().expect("No text received");
    assert_eq!(
        text, "hello world",
        "Expected 'hello world', got '{}'",
        text
    );
}
