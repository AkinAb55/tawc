use std::io;
use std::thread;
use std::time::Duration;

use crate::adb;

/// Ensure the tawc compositor is running and visible on the phone.
/// Restarts it if not running or if backgrounded/paused.
pub fn ensure_running() -> io::Result<()> {
    let output = adb::shell("dumpsys activity activities | grep me.phie.tawc/.MainActivity")?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    // Check for both presence AND visibility. A paused/backgrounded compositor
    // won't have a functioning Wayland socket or receive touch events.
    let is_running = stdout.contains("me.phie.tawc/.MainActivity");
    let is_visible = stdout.contains("visible=true");

    if is_running && is_visible {
        return Ok(());
    }

    if is_running {
        eprintln!("Compositor paused/backgrounded, restarting...");
        adb::shell("am force-stop me.phie.tawc")?;
        thread::sleep(Duration::from_millis(500));
    } else {
        eprintln!("Starting compositor...");
    }

    adb::shell("am start -n me.phie.tawc/.MainActivity")?;
    // Give it time to initialize EGL and create the Wayland socket
    thread::sleep(Duration::from_secs(3));
    Ok(())
}
