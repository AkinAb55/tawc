use std::io::{self, BufRead, BufReader};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::adb;

const PROTOCOL_PREFIX: &str = "TAWC_DEBUG:";

/// A running instance of gtk3-debug-app with structured output capture.
pub struct DebugApp {
    process: std::process::Child,
    /// All received protocol lines (without the TAWC_DEBUG: prefix).
    lines: Arc<Mutex<Vec<String>>>,
    /// Channel for new protocol lines as they arrive.
    line_rx: mpsc::Receiver<String>,
}

impl DebugApp {
    /// Start the debug app with the given subcommand.
    /// `binary_path` is the path inside the chroot (e.g. "/tmp/gtk3-debug-app/gtk3-debug-app").
    pub fn start(binary_path: &str, subcommand: &str) -> io::Result<Self> {
        let cmd = format!("{} {}", binary_path, subcommand);
        let mut child = adb::chroot_spawn(&cmd)?;

        let stdout = child.stdout.take().expect("stdout was piped");
        let lines = Arc::new(Mutex::new(Vec::new()));
        let lines_clone = lines.clone();
        let (tx, rx) = mpsc::channel();

        // Reader thread: continuously drain stdout, filter for protocol lines
        thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines() {
                match line {
                    Ok(line) => {
                        // adb shell may add \r
                        let line = line.trim_end_matches('\r').to_string();
                        if let Some(payload) = line.strip_prefix(PROTOCOL_PREFIX) {
                            let payload = payload.to_string();
                            lines_clone.lock().unwrap().push(payload.clone());
                            let _ = tx.send(payload);
                        }
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            process: child,
            lines,
            line_rx: rx,
        })
    }

    /// Wait until a protocol line starting with `prefix` appears.
    /// Returns the full payload (after TAWC_DEBUG:).
    pub fn wait_for(&self, prefix: &str, timeout: Duration) -> Result<String, String> {
        let deadline = Instant::now() + timeout;

        // Check already-received lines
        for line in self.lines.lock().unwrap().iter() {
            if line.starts_with(prefix) {
                return Ok(line.clone());
            }
        }

        // Wait for new lines
        loop {
            let remaining = deadline
                .checked_duration_since(Instant::now())
                .ok_or_else(|| {
                    let received = self.lines.lock().unwrap().clone();
                    format!(
                        "Timeout waiting for '{}' (received: {:?})",
                        prefix, received
                    )
                })?;

            match self.line_rx.recv_timeout(remaining) {
                Ok(line) if line.starts_with(prefix) => return Ok(line),
                Ok(_) => continue,
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    let received = self.lines.lock().unwrap().clone();
                    return Err(format!(
                        "Timeout after {:?} waiting for '{}' (received: {:?})",
                        timeout, prefix, received
                    ));
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    let received = self.lines.lock().unwrap().clone();
                    return Err(format!(
                        "Debug app exited while waiting for '{}' (received: {:?})",
                        prefix, received
                    ));
                }
            }
        }
    }

    /// Wait for the app to signal it's ready (window mapped, focused).
    pub fn wait_ready(&self) -> Result<(), String> {
        self.wait_for("READY", Duration::from_secs(30)).map(|_| ())
    }

    /// Get the value from the most recent TEXT_CHANGED line.
    pub fn last_text(&self) -> Option<String> {
        self.lines
            .lock()
            .unwrap()
            .iter()
            .rev()
            .find_map(|l| l.strip_prefix("TEXT_CHANGED:").map(|s| s.to_string()))
    }
}

impl Drop for DebugApp {
    fn drop(&mut self) {
        // Kill the debug app inside the chroot. The nested shell chain
        // (adb -> su -> bash -> chroot -> bash -> app) doesn't propagate
        // signals reliably, so killall by name is the safest approach.
        let _ = adb::chroot_run("killall gtk3-debug-app");
        let _ = self.process.kill();
        let _ = self.process.wait();
    }
}
