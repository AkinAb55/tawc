//! Text clipboard bridge between Android and Wayland selections.
//!
//! Smithay owns the Wayland data-device protocol mechanics. This module
//! owns tawc's platform bridge policy: text MIME selection, eager
//! mirroring of client-owned selections into Android, and async writes
//! for compositor-owned Android selections.
//!
//! Mirroring is "latest selection wins". At most one pull is in flight,
//! owned by the event loop as `TawcState::clipboard_pull`; a newer
//! selection (or fresh Android text) cancels and replaces it. Clients
//! routinely set the clipboard more than once per copy — GTK3 apps
//! (Firefox, VTE terminals) immediately re-announce the selection with
//! `SAVE_TARGETS` appended — so the bridge must survive bursts and
//! mirror the final state.

use std::fs::File;
use std::io::{Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Mutex,
};
use std::time::Duration;

use log::{error, info, warn};
use smithay::reexports::calloop::generic::Generic;
use smithay::reexports::calloop::timer::{TimeoutAction, Timer};
use smithay::reexports::calloop::{
    channel, Interest, LoopHandle, Mode, PostAction, RegistrationToken,
};
use smithay::wayland::selection::SelectionTarget;

use crate::compositor::TawcState;

/// Hard cap for eager clipboard pulls into Android. A broken or malicious
/// Wayland/X11 client can keep writing forever; we stop once the transfer
/// crosses this boundary and leave Android's clipboard unchanged.
pub const MAX_TEXT_BYTES: usize = 1024 * 1024;

const PULL_TIMEOUT: Duration = Duration::from_secs(5);

const TEXT_MIMES: &[&str] = &[
    "text/plain;charset=utf-8",
    "text/plain",
    "UTF8_STRING",
    "STRING",
];

#[derive(Clone)]
pub enum SelectionUserData {
    AndroidText(String),
    X11(SelectionTarget),
}

/// Which side owns the selection a pull reads from.
#[derive(Clone, Copy)]
pub enum PullSource {
    Wayland,
    X11,
}

impl PullSource {
    fn label(self) -> &'static str {
        match self {
            PullSource::Wayland => "wayland",
            PullSource::X11 => "x11",
        }
    }
}

pub enum ClipboardEvent {
    AndroidText(String),
    PullSelection {
        source: PullSource,
        mime_type: String,
    },
}

static CLIPBOARD_SENDER: Mutex<Option<channel::Sender<ClipboardEvent>>> = Mutex::new(None);
static NEXT_PULL_ID: AtomicU64 = AtomicU64::new(0);
static PULL_TIMEOUTS_TOTAL: AtomicU64 = AtomicU64::new(0);

pub fn debug_state() -> String {
    format!(
        "clipboard_pull_timeouts_total={}",
        PULL_TIMEOUTS_TOTAL.load(Ordering::Relaxed)
    )
}

pub fn create_clipboard_channel() -> channel::Channel<ClipboardEvent> {
    let (sender, ch) = channel::channel();
    *CLIPBOARD_SENDER.lock().unwrap() = Some(sender);
    ch
}

pub fn clear_clipboard_sender() {
    *CLIPBOARD_SENDER.lock().unwrap() = None;
}

pub fn send_android_text(text: String) {
    if text.len() > MAX_TEXT_BYTES {
        warn!(
            "clipboard: dropping Android clipboard text over cap: {} bytes",
            text.len()
        );
        return;
    }
    if let Some(sender) = CLIPBOARD_SENDER.lock().unwrap().as_ref() {
        let _ = sender.send(ClipboardEvent::AndroidText(text));
    }
}

/// Queue a pull of a freshly set client selection into Android. Deferred
/// through the clipboard channel because Smithay invokes the selection
/// handlers before installing the new selection in seat state.
pub fn queue_selection_pull(source: PullSource, mime_types: &[String]) {
    let Some(mime_type) = preferred_text_mime(mime_types) else {
        return;
    };
    if let Some(sender) = CLIPBOARD_SENDER.lock().unwrap().as_ref() {
        let _ = sender.send(ClipboardEvent::PullSelection { source, mime_type });
    }
}

pub fn text_mime_types() -> Vec<String> {
    TEXT_MIMES.iter().map(|m| (*m).to_string()).collect()
}

pub fn preferred_text_mime(mime_types: &[String]) -> Option<String> {
    TEXT_MIMES
        .iter()
        .find(|mime| mime_types.iter().any(|candidate| candidate == **mime))
        .map(|m| (*m).to_string())
}

pub fn is_supported_text_mime(mime_type: &str) -> bool {
    TEXT_MIMES.contains(&mime_type)
}

pub fn pipe() -> std::io::Result<(OwnedFd, OwnedFd)> {
    let mut fds = [0; 2];
    let rc = unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) };
    if rc < 0 {
        return Err(std::io::Error::last_os_error());
    }
    let read = unsafe { OwnedFd::from_raw_fd(fds[0]) };
    let write = unsafe { OwnedFd::from_raw_fd(fds[1]) };
    Ok((read, write))
}

/// The in-flight selection read, owned by the event loop. Both event
/// sources (pipe + timeout timer) are deregistered together on every
/// completion, cancellation, or failure path.
pub struct ActivePull {
    /// Defensive only: callbacks act only when their captured id matches
    /// the current pull's. Cancellation already removes both sources, and
    /// calloop's token versioning prevents stale dispatch, so a mismatch
    /// should be unreachable.
    id: u64,
    fd_token: RegistrationToken,
    timer_token: RegistrationToken,
    buf: Vec<u8>,
    label: &'static str,
}

/// Begin mirroring the selection readable on `fd` into Android,
/// canceling any pull already in flight.
pub fn start_pull(
    handle: &LoopHandle<'static, TawcState>,
    state: &mut TawcState,
    fd: OwnedFd,
    source: PullSource,
) {
    let label = source.label();
    cancel_pull(handle, state);

    if let Err(e) = set_nonblocking(fd.as_raw_fd()) {
        warn!("clipboard: failed to set {} pull nonblocking: {}", label, e);
        return;
    }
    let id = NEXT_PULL_ID.fetch_add(1, Ordering::Relaxed);

    let fd_handle = handle.clone();
    let fd_source = Generic::new(File::from(fd), Interest::READ, Mode::Level);
    let fd_token = match handle.insert_source(fd_source, move |_, file, data: &mut TawcState| {
        Ok(pull_readable(&fd_handle, data, id, file.as_ref()))
    }) {
        Ok(token) => token,
        Err(e) => {
            warn!("clipboard: failed to register {} pull source: {}", label, e);
            return;
        }
    };

    let timer_handle = handle.clone();
    let timer = Timer::from_duration(PULL_TIMEOUT);
    // If the deadline and the pipe's final readable event land in the same
    // poll batch with the timer ordered first, a completed transfer is
    // miscounted as a timeout. Accepted: it needs the source to finish in
    // the same wakeup as the 5s deadline, and costs one copy.
    let timer_token = match handle.insert_source(timer, move |_, _, data: &mut TawcState| {
        if let Some(pull) = take_pull_if_current(data, id) {
            timer_handle.remove(pull.fd_token);
            warn!("clipboard: timed out waiting for {} selection source", pull.label);
            PULL_TIMEOUTS_TOTAL.fetch_add(1, Ordering::Relaxed);
        }
        TimeoutAction::Drop
    }) {
        Ok(token) => token,
        Err(e) => {
            warn!("clipboard: failed to register {} pull timer: {}", label, e);
            handle.remove(fd_token);
            return;
        }
    };

    state.clipboard_pull = Some(ActivePull {
        id,
        fd_token,
        timer_token,
        buf: Vec::new(),
        label,
    });
}

/// Drop any in-flight pull. Closing the pipe is the cancellation: the
/// selection owner sees EPIPE and stops writing.
pub fn cancel_pull(handle: &LoopHandle<'static, TawcState>, state: &mut TawcState) {
    if let Some(pull) = state.clipboard_pull.take() {
        handle.remove(pull.fd_token);
        handle.remove(pull.timer_token);
    }
}

fn take_pull_if_current(state: &mut TawcState, id: u64) -> Option<ActivePull> {
    if state.clipboard_pull.as_ref().is_some_and(|p| p.id == id) {
        state.clipboard_pull.take()
    } else {
        None
    }
}

/// Terminal-path helper for the fd-source callback: detach the pull from
/// state and deregister its timer. The caller publishes or drops it, and
/// removes the fd source itself by returning `PostAction::Remove`.
fn finish_pull(
    handle: &LoopHandle<'static, TawcState>,
    state: &mut TawcState,
    id: u64,
) -> ActivePull {
    let pull = take_pull_if_current(state, id).unwrap();
    handle.remove(pull.timer_token);
    pull
}

fn pull_readable(
    handle: &LoopHandle<'static, TawcState>,
    state: &mut TawcState,
    id: u64,
    mut file: &File,
) -> PostAction {
    if !state.clipboard_pull.as_ref().is_some_and(|p| p.id == id) {
        return PostAction::Remove;
    }

    let mut chunk = [0u8; 8192];
    loop {
        match file.read(&mut chunk) {
            Ok(0) => {
                publish_to_android(finish_pull(handle, state, id));
                return PostAction::Remove;
            }
            Ok(n) => {
                let pull = state.clipboard_pull.as_mut().unwrap();
                pull.buf.extend_from_slice(&chunk[..n]);
                if pull.buf.len() > MAX_TEXT_BYTES {
                    let pull = finish_pull(handle, state, id);
                    warn!(
                        "clipboard: {} selection exceeded {} byte cap; dropping",
                        pull.label, MAX_TEXT_BYTES
                    );
                    return PostAction::Remove;
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                return PostAction::Continue;
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(e) => {
                let pull = finish_pull(handle, state, id);
                warn!("clipboard: failed reading {} selection: {}", pull.label, e);
                return PostAction::Remove;
            }
        }
    }
}

fn publish_to_android(pull: ActivePull) {
    match String::from_utf8(pull.buf) {
        Ok(text) => {
            info!(
                "clipboard: mirrored {} text to Android ({} bytes)",
                pull.label,
                text.len()
            );
            crate::set_android_clipboard_text(&text);
        }
        Err(e) => warn!("clipboard: {} selection was not valid UTF-8: {}", pull.label, e),
    }
}

pub fn write_text_to_fd(fd: OwnedFd, text: String) {
    if let Err(e) = std::thread::Builder::new()
        .name("clipboard-write-android".into())
        .spawn(move || {
            let mut file = File::from(fd);
            let _ = file.write_all(text.as_bytes());
        }) {
        error!("clipboard: failed to spawn writer: {}", e);
    }
}

fn set_nonblocking(fd: i32) -> std::io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(std::io::Error::last_os_error());
    }
    let rc = unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
    if rc < 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
}
