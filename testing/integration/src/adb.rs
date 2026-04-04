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
        "su -c \"/system_ext/bin/bash /data/local/tmp/arch-chroot-run '{}'\"",
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
                "su -c \"/system_ext/bin/bash /data/local/tmp/arch-chroot-run '{}'\"",
                escaped
            ),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
}

/// Send text to the compositor via broadcast intent.
/// Goes through: BroadcastReceiver -> nativeCommitText -> text_input_v3 -> GTK.
/// This is more reliable than `adb shell input text` which may be intercepted by the IME.
pub fn input_text(text: &str) -> io::Result<Output> {
    shell(&format!(
        "am broadcast -a me.phie.tawc.TEXT_INPUT --es text '{}'",
        text.replace('\'', "'\\''")
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
    let output = Command::new("adb")
        .args(["logcat", "-d", "-s", "tawc-native"])
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
