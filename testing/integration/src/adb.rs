use std::io;
use std::process::{Command, Output, Stdio};

/// Run an adb shell command, wait for completion, return output.
pub fn shell(cmd: &str) -> io::Result<Output> {
    Command::new("adb")
        .args(["shell", cmd])
        .output()
}

/// Run a command inside the Arch Linux ARM chroot on the phone.
/// The command runs as root via su, inside arch-chroot-run which sets up
/// mounts and environment (WAYLAND_DISPLAY, XDG_RUNTIME_DIR, etc.).
pub fn chroot_run(cmd: &str) -> io::Result<Output> {
    let escaped = cmd.replace('\'', "'\\''");
    shell(&format!(
        "su -c \"/system/bin/sh /data/local/tmp/arch-chroot-run '{}'\"",
        escaped
    ))
}

/// Spawn a command in the chroot with piped stdout/stderr (non-blocking).
/// Returns the Child process. Caller is responsible for reading output.
pub fn chroot_spawn(cmd: &str) -> io::Result<std::process::Child> {
    let escaped = cmd.replace('\'', "'\\''");
    Command::new("adb")
        .args([
            "shell",
            &format!(
                "su -c \"/system/bin/sh /data/local/tmp/arch-chroot-run '{}'\"",
                escaped
            ),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
}

/// Send text to the compositor via broadcast intent — equivalent to
/// Gboard calling `commitText(text, 1)`. Goes through:
/// BroadcastReceiver -> TawcInputConnection.commitText -> nativeCommitText
/// -> text_input_v3 -> Wayland client.
///
/// More reliable than `adb shell input text` which may be intercepted by
/// the IME before reaching the editor.
pub fn input_text(text: &str) -> io::Result<Output> {
    shell(&format!(
        "am broadcast -a me.phie.tawc.TEXT_INPUT --es text '{}'",
        text.replace('\'', "'\\''")
    ))
}

/// Set composing/preedit text — equivalent to Gboard calling
/// `setComposingText(text, 1)`. The Wayland client should display this as
/// a preedit (highlighted, not yet committed). Successive calls replace the
/// previous preedit. Pass an empty string to clear the preedit.
pub fn input_set_composing(text: &str) -> io::Result<Output> {
    input_set_composing_with_delete(text, 0, 0)
}

/// `setComposingText` with explicit composing-region replacement deltas.
/// `delete_before` / `delete_after` are UTF-16 code units around the
/// cursor that should be removed from committed text before the new
/// preedit is set — i.e. simulating Gboard's `setComposingRegion(s, e)`
/// followed by `setComposingText(text)` flow without depending on an
/// actual IME service. (Test broadcasts bypass the system IME to avoid
/// non-determinism — see CompositorActivity.testInputReceiver.)
pub fn input_set_composing_with_delete(text: &str, delete_before: u32, delete_after: u32) -> io::Result<Output> {
    shell(&format!(
        "am broadcast -a me.phie.tawc.SET_COMPOSING_TEXT --es text '{}' --ei deleteBefore {} --ei deleteAfter {}",
        text.replace('\'', "'\\''"), delete_before, delete_after
    ))
}

/// `commitText` with explicit composing-region replacement deltas.
/// See [input_set_composing_with_delete] for delta semantics.
pub fn input_text_with_delete(text: &str, delete_before: u32, delete_after: u32) -> io::Result<Output> {
    shell(&format!(
        "am broadcast -a me.phie.tawc.TEXT_INPUT --es text '{}' --ei deleteBefore {} --ei deleteAfter {}",
        text.replace('\'', "'\\''"), delete_before, delete_after
    ))
}

/// Finalize the current preedit as committed text — equivalent to Gboard
/// calling `finishComposingText()`. The active preedit becomes part of the
/// editor's text and the preedit is cleared.
pub fn input_finish_composing() -> io::Result<Output> {
    shell("am broadcast -a me.phie.tawc.FINISH_COMPOSING_TEXT")
}

/// Delete `before` UTF-16 code units before the cursor and `after` after —
/// equivalent to Gboard calling `deleteSurroundingText(before, after)`.
pub fn input_delete_surrounding(before: u32, after: u32) -> io::Result<Output> {
    shell(&format!(
        "am broadcast -a me.phie.tawc.DELETE_SURROUNDING_TEXT --ei before {} --ei after {}",
        before, after
    ))
}

/// Send a key event to the compositor via broadcast intent.
/// Goes through: BroadcastReceiver -> nativeSendKeyEvent -> wl_keyboard.
pub fn input_keyevent(keycode: u32) -> io::Result<Output> {
    shell(&format!(
        "am broadcast -a me.phie.tawc.KEY_EVENT --ei keycode {}",
        keycode
    ))
}

/// Send a tap at physical screen coordinates (x, y).
/// The compositor divides by touch_scale (2) to get logical coordinates.
pub fn input_tap(x: u32, y: u32) -> io::Result<Output> {
    shell(&format!("input tap {} {}", x, y))
}

/// Clear the logcat buffer so subsequent reads only show new messages.
pub fn logcat_clear() -> io::Result<Output> {
    Command::new("adb").args(["logcat", "-c"]).output()
}

/// Dump logcat lines matching the tawc-native tag (compositor Rust logs).
pub fn logcat_dump_tawc() -> io::Result<String> {
    logcat_dump("tawc-native")
}

/// Dump logcat lines matching a specific tag.
pub fn logcat_dump(tag: &str) -> io::Result<String> {
    let output = Command::new("adb")
        .args(["logcat", "-d", "-s", tag])
        .output()?;
    Ok(String::from_utf8_lossy(&output.stdout).to_string())
}

/// Trigger the compositor to log its current state.
pub fn broadcast_query_state() -> io::Result<Output> {
    shell("am broadcast -a me.phie.tawc.QUERY_STATE")
}

// Common Android keycodes (used with input_keyevent)
pub const KEYCODE_DEL: u32 = 67; // Backspace
pub const KEYCODE_ENTER: u32 = 66;
pub const KEYCODE_TAB: u32 = 61;
