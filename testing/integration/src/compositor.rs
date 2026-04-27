use std::io;
use std::thread;
use std::time::{Duration, Instant};

use crate::adb;

/// Compositor state snapshot, parsed from COMPOSITOR_STATE log line.
#[derive(Debug, Clone)]
pub struct CompositorState {
    pub clients: u32,
    pub toplevels: u32,
    pub surfaces_wlegl: u32,
    pub surfaces_shm: u32,
    pub frames: u64,
    /// Number of toplevels visible in the last rendered frame.
    /// If this is non-zero while toplevels is zero, the screen shows a stale frame.
    pub rendered_toplevels: u32,
}

/// Query the compositor's current state via broadcast intent.
/// Does NOT clear logcat — counts existing COMPOSITOR_STATE lines and waits for a new one.
pub fn query_state(timeout: Duration) -> Result<CompositorState, String> {
    // Count existing COMPOSITOR_STATE lines so we can detect the new one
    let existing_logs = adb::logcat_dump_tawc()
        .map_err(|e| format!("Failed to dump logcat: {}", e))?;
    let existing_count = existing_logs.lines()
        .filter(|l| l.contains("COMPOSITOR_STATE:"))
        .count();

    adb::broadcast_query_state().map_err(|e| format!("Failed to send query: {}", e))?;

    let deadline = Instant::now() + timeout;
    loop {
        let logs = adb::logcat_dump_tawc()
            .map_err(|e| format!("Failed to dump logcat: {}", e))?;
        let state_lines: Vec<&str> = logs.lines()
            .filter(|l| l.contains("COMPOSITOR_STATE:"))
            .collect();
        if state_lines.len() > existing_count {
            // Parse the newest COMPOSITOR_STATE line
            if let Some(state) = parse_compositor_state_line(state_lines.last().unwrap()) {
                return Ok(state);
            }
        }
        if Instant::now() > deadline {
            return Err(format!(
                "Timeout waiting for COMPOSITOR_STATE in logcat (existing: {}, current: {})",
                existing_count, state_lines.len()
            ));
        }
        thread::sleep(Duration::from_millis(50));
    }
}

/// Wait until the compositor reports the expected number of clients and toplevels.
pub fn wait_for_state(
    expected_clients: u32,
    expected_toplevels: u32,
    timeout: Duration,
) -> Result<CompositorState, String> {
    let deadline = Instant::now() + timeout;
    loop {
        let state = query_state(Duration::from_secs(2))?;
        if state.clients == expected_clients && state.toplevels == expected_toplevels {
            return Ok(state);
        }
        if Instant::now() > deadline {
            return Err(format!(
                "Compositor state didn't reach clients={} toplevels={} within {:?} (last: {:?})",
                expected_clients, expected_toplevels, timeout, state
            ));
        }
        thread::sleep(Duration::from_millis(200));
    }
}

fn parse_compositor_state_line(line: &str) -> Option<CompositorState> {
    let idx = line.find("COMPOSITOR_STATE:")?;
    let payload = &line[idx + "COMPOSITOR_STATE:".len()..];
    let mut clients = None;
    let mut toplevels = None;
    let mut surfaces_wlegl = None;
    let mut surfaces_shm = None;
    let mut frames = None;
    let mut rendered_toplevels = None;
    for part in payload.split_whitespace() {
        if let Some((key, val)) = part.split_once('=') {
            match key {
                "clients" => clients = Some(val.parse().ok()?),
                "toplevels" => toplevels = Some(val.parse().ok()?),
                "surfaces_wlegl" => surfaces_wlegl = Some(val.parse().ok()?),
                "surfaces_shm" => surfaces_shm = Some(val.parse().ok()?),
                "frames" => frames = Some(val.parse().ok()?),
                "rendered_toplevels" => rendered_toplevels = Some(val.parse().ok()?),
                _ => {}
            }
        }
    }
    Some(CompositorState {
        clients: clients?,
        toplevels: toplevels?,
        surfaces_wlegl: surfaces_wlegl?,
        surfaces_shm: surfaces_shm?,
        frames: frames?,
        rendered_toplevels: rendered_toplevels?,
    })
}

/// Wait until the last rendered frame shows the expected number of toplevels.
/// This ensures the screen actually reflects the current state, not a stale frame.
pub fn wait_for_rendered_toplevels(
    expected: u32,
    timeout: Duration,
) -> Result<CompositorState, String> {
    let deadline = Instant::now() + timeout;
    loop {
        let state = query_state(Duration::from_secs(2))?;
        if state.rendered_toplevels == expected {
            return Ok(state);
        }
        if Instant::now() > deadline {
            return Err(format!(
                "Screen still shows {} toplevels (expected {}), compositor state: {:?}",
                state.rendered_toplevels, expected, state
            ));
        }
        thread::sleep(Duration::from_millis(50));
    }
}

/// Ensure the tawc compositor is running on the phone. Starts the
/// `CompositorService` (which owns the compositor thread + Wayland
/// socket) if it isn't already up. The harness never stops the
/// compositor itself — `run-integration-tests.sh` does the final
/// force-stop after the suite, and leaving the service running between
/// test binaries lets the next one hit the already-running fast-path.
///
/// Liveness is determined by the tawc app process, not by an Android
/// Activity: per-window CompositorActivities only exist for the
/// duration of an actual Wayland toplevel and are absent between tests.
/// The socket file alone isn't a good signal — `am force-stop` leaves
/// the bound socket file behind, so subsequent `test -e` checks would
/// pass against a dead compositor.
pub fn ensure_running() -> io::Result<()> {
    if compositor_alive()? {
        return Ok(());
    }

    eprintln!("Starting compositor (via MainActivity → CompositorService)...");
    // The Service is `exported="false"`, so `am start-foreground-service`
    // fails over adb with "Requires permission not exported." Launch
    // MainActivity instead — its onCreate calls startForegroundService
    // unconditionally, which has the same effect from inside the app.
    // Force-stop first so any leftover socket file from a previous
    // run is cleared before the new compositor binds.
    adb::shell("am force-stop me.phie.tawc")?;
    thread::sleep(Duration::from_millis(300));
    adb::shell("am start -n me.phie.tawc/.MainActivity")?;

    let deadline = std::time::Instant::now() + Duration::from_secs(15);
    loop {
        if compositor_alive()? {
            return Ok(());
        }
        if std::time::Instant::now() > deadline {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "Compositor did not become ready within 15s",
            ));
        }
        thread::sleep(Duration::from_millis(100));
    }
}

/// True iff the tawc app process is alive AND the chroot-visible
/// Wayland socket exists. Both conditions matter: `am force-stop`
/// leaves the unix-domain socket file behind on disk even though no
/// process is listening, so the file alone would falsely indicate
/// readiness on the very next test run.
fn compositor_alive() -> io::Result<bool> {
    let output = adb::shell(
        "pidof me.phie.tawc >/dev/null && \
         su -c 'test -e /data/local/arch-chroot/tmp/wayland-0' && echo ready",
    )?;
    Ok(String::from_utf8_lossy(&output.stdout).contains("ready"))
}
