use std::io;
use std::thread;
use std::time::Duration;

use crate::adb;

/// Ensure the tawc compositor is running on the phone.
/// Starts it if not already running.
pub fn ensure_running() -> io::Result<()> {
    let output = adb::shell("dumpsys activity activities | grep me.phie.tawc/.MainActivity")?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    if stdout.contains("me.phie.tawc/.MainActivity") {
        return Ok(());
    }

    eprintln!("Starting compositor...");
    adb::shell("am start -n me.phie.tawc/.MainActivity")?;
    // Give it time to initialize EGL and create the Wayland socket
    thread::sleep(Duration::from_secs(3));
    Ok(())
}
