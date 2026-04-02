//! Custom zwp_text_input_v3 implementation.
//!
//! Bridges Android's InputConnection API and Wayland's text-input-v3 protocol.
//! Both are built around the same concepts (composing text, committing text,
//! deleting surrounding text, content type hints), but differ in details:
//!
//! - Android uses UTF-16 character counts; Wayland uses UTF-8 byte counts
//! - Android batches operations via beginBatchEdit/endBatchEdit; Wayland batches
//!   via done events
//! - Android's IME queries editor state (getTextBeforeCursor etc.); Wayland
//!   clients push state (set_surrounding_text)
//!
//! The compositor must maintain synchronized state between both sides:
//! - Store surrounding text from Wayland clients → serve to Android IME
//! - Track preedit text sent to clients → needed for finishComposingText
//! - Notify Android when cursor moves for non-IME reasons → updateSelection
//!
//! Bypasses Smithay's built-in TextInputManagerState which requires a Wayland
//! input-method client. Our IME is Android's Gboard via InputConnection/JNI.

use std::collections::HashMap;
use std::sync::Mutex;

use log::info;
use smithay::reexports::calloop::channel;
use smithay::reexports::wayland_server::backend::ObjectId;
use smithay::reexports::wayland_server::protocol::wl_surface::WlSurface;
use smithay::reexports::wayland_server::{
    Client, DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource,
};
use wayland_protocols::wp::text_input::zv3::server::{
    zwp_text_input_manager_v3::{self, ZwpTextInputManagerV3},
    zwp_text_input_v3::{self, ZwpTextInputV3},
};

use crate::compositor::TawcState;

// ---------------------------------------------------------------------------
// Android → Compositor text input events (via calloop channel)
// ---------------------------------------------------------------------------

/// Evdev keycode constants for keys sent as wl_keyboard events.
pub const EVDEV_KEY_ENTER: u32 = 28;

pub enum TextInputEvent {
    /// Insert finalized text (from commitText or tab key).
    CommitString { text: String },
    /// Set preedit/composing text (from setComposingText).
    SetPreeditString { text: String },
    /// Finalize current preedit (from finishComposingText).
    /// Unlike ClearPreedit, this commits the composing text rather than discarding it.
    FinishComposingText,
    /// Delete surrounding text (from Android IME's deleteSurroundingText).
    /// before/after are in Java characters (UTF-16 code units), NOT bytes.
    DeleteSurroundingText { before: u32, after: u32 },
    /// Send an actual wl_keyboard key event (evdev keycode).
    /// Used for Enter, Backspace, Delete, and other keys that should be real
    /// key events rather than text-input-v3 operations.
    KeyPress { keycode: u32 },
}

/// Global sender for text input events from JNI. Replaced on compositor restart.
static TEXT_INPUT_SENDER: Mutex<Option<channel::Sender<TextInputEvent>>> = Mutex::new(None);

/// Create the calloop channel pair. Returns the receiver for the event loop.
pub fn create_text_input_channel() -> channel::Channel<TextInputEvent> {
    let (sender, ch) = channel::channel();
    *TEXT_INPUT_SENDER.lock().unwrap() = Some(sender);
    ch
}

/// Send a text input event from JNI. No-op if the channel isn't set up yet.
pub fn send_text_input_event(event: TextInputEvent) {
    if let Some(sender) = TEXT_INPUT_SENDER.lock().unwrap().as_ref() {
        let _ = sender.send(event);
    }
}

// ---------------------------------------------------------------------------
// Compositor → Android (reverse JNI calls)
// ---------------------------------------------------------------------------

fn show_keyboard() {
    info!("text_input: requesting keyboard show");
    crate::call_native_bridge_void("onShowKeyboard", "()V", &[]);
}

fn hide_keyboard() {
    info!("text_input: requesting keyboard hide");
    crate::call_native_bridge_void("onHideKeyboard", "()V", &[]);
}

/// Notify Android IME that the editor's selection/cursor has changed.
/// This is critical after cursor movement by touch/click (change_cause=other)
/// so Gboard can reset its composing state and internal text model.
fn update_selection(sel_start: i32, sel_end: i32) {
    crate::call_native_bridge_void(
        "onUpdateSelection",
        "(IIII)V",
        &[
            jni::objects::JValue::Int(sel_start),
            jni::objects::JValue::Int(sel_end),
            jni::objects::JValue::Int(-1), // no composing span
            jni::objects::JValue::Int(-1),
        ],
    );
}

// ---------------------------------------------------------------------------
// State types
// ---------------------------------------------------------------------------

/// Marker data stored on the ZwpTextInputV3 wayland-server resource.
pub struct TextInputData;

/// Surrounding text reported by a Wayland client via set_surrounding_text.
/// All offsets are UTF-8 byte positions within `text`.
#[derive(Clone)]
struct SurroundingText {
    text: String,
    cursor: usize,
    anchor: usize,
}

/// Why the surrounding text changed, per the protocol's change_cause enum.
#[derive(Clone, Copy, PartialEq, Default)]
enum ChangeCause {
    /// The input method (compositor/IME) caused the change.
    #[default]
    InputMethod,
    /// Something else (user click, arrow keys, etc.) caused the change.
    Other,
}

/// Per-instance state for a zwp_text_input_v3 object.
/// State is double-buffered: pending fields are applied atomically on commit.
#[derive(Default)]
struct InstanceState {
    commit_count: u32,

    // Pending state (applied on next commit)
    pending_enable: bool,
    pending_disable: bool,
    pending_surrounding: Option<SurroundingText>,
    pending_change_cause: ChangeCause,

    // Current (committed) state
    enabled: bool,
    surrounding: Option<SurroundingText>,

    /// Last preedit text sent to this instance. Needed so finishComposingText
    /// can commit the composing text rather than discarding it.
    current_preedit: Option<String>,
}

/// Global text input state, stored in TawcState.
pub struct TextInputState {
    /// All active text input instances.
    instances: Vec<ZwpTextInputV3>,
    /// Per-instance mutable state, keyed by resource ObjectId.
    instance_state: HashMap<ObjectId, InstanceState>,
    /// The surface that currently has text input focus.
    pub focused_surface: Option<WlSurface>,
    /// Whether the Android keyboard is currently shown.
    /// Tracked to avoid redundant show/hide calls and handle rapid toggling.
    keyboard_visible: bool,
}

impl TextInputState {
    pub fn new() -> Self {
        Self {
            instances: Vec::new(),
            instance_state: HashMap::new(),
            focused_surface: None,
            keyboard_visible: false,
        }
    }

    // --- Instance management ---

    pub fn add_instance(&mut self, ti: ZwpTextInputV3) {
        if let Some(ref surface) = self.focused_surface {
            if ti.id().same_client_as(&surface.id()) {
                ti.enter(surface);
            }
        }
        self.instance_state.insert(ti.id(), InstanceState::default());
        self.instances.push(ti);
    }

    pub fn remove_instance(&mut self, id: &ObjectId) {
        self.instances.retain(|ti| ti.id() != *id);
        self.instance_state.remove(id);
    }

    // --- Client request handlers (called from protocol dispatch) ---

    pub fn set_pending_enable(&mut self, id: &ObjectId) {
        if let Some(state) = self.instance_state.get_mut(id) {
            state.pending_enable = true;
        }
    }

    pub fn set_pending_disable(&mut self, id: &ObjectId) {
        if let Some(state) = self.instance_state.get_mut(id) {
            state.pending_disable = true;
        }
    }

    pub fn set_pending_surrounding(&mut self, id: &ObjectId, text: String, cursor: i32, anchor: i32) {
        if let Some(state) = self.instance_state.get_mut(id) {
            state.pending_surrounding = Some(SurroundingText {
                text,
                cursor: cursor.max(0) as usize,
                anchor: anchor.max(0) as usize,
            });
        }
    }

    pub fn set_pending_change_cause(&mut self, id: &ObjectId, cause: u32) {
        if let Some(state) = self.instance_state.get_mut(id) {
            // Protocol enum: 0 = input_method, 1 = other
            state.pending_change_cause = if cause == 1 {
                ChangeCause::Other
            } else {
                ChangeCause::InputMethod
            };
        }
    }

    /// Process a commit from the client. Atomically applies all pending state.
    pub fn commit(&mut self, id: &ObjectId) {
        let state = match self.instance_state.get_mut(id) {
            Some(s) => s,
            None => return,
        };

        state.commit_count += 1;

        // Apply enable/disable. Per spec, if both are pending, disable wins.
        // enable resets all state from the previous enable/disable cycle.
        if state.pending_disable {
            info!("text_input: commit disable");
            state.enabled = false;
            state.surrounding = None;
            state.current_preedit = None;
        } else if state.pending_enable {
            info!("text_input: commit enable");
            state.enabled = true;
            // Per spec: enable resets all associated state
            state.surrounding = None;
            state.current_preedit = None;
        }

        // Apply pending surrounding text
        let change_cause = state.pending_change_cause;
        if let Some(surrounding) = state.pending_surrounding.take() {
            // When text/cursor changed for non-IME reasons (user touch, arrow keys),
            // notify Android so Gboard can reset its composing state.
            if change_cause == ChangeCause::Other && state.enabled {
                let sel_start = byte_offset_to_char_count(&surrounding.text, surrounding.cursor);
                let sel_end = byte_offset_to_char_count(&surrounding.text, surrounding.anchor);
                update_selection(sel_start as i32, sel_end as i32);
            }
            state.surrounding = Some(surrounding);
        }

        // Reset pending state
        state.pending_enable = false;
        state.pending_disable = false;
        state.pending_change_cause = ChangeCause::default();

        // Update keyboard visibility based on whether any instance is enabled
        self.sync_keyboard_visibility();
    }

    /// Show/hide Android keyboard to match the current enabled state.
    /// Avoids redundant calls and handles rapid disable+enable gracefully.
    fn sync_keyboard_visibility(&mut self) {
        let any_enabled = self.instance_state.values().any(|s| s.enabled);
        if any_enabled != self.keyboard_visible {
            if any_enabled {
                show_keyboard();
            } else {
                hide_keyboard();
            }
            self.keyboard_visible = any_enabled;
        }
    }

    // --- Focus management ---

    fn enter(&mut self, surface: &WlSurface) {
        self.focused_surface = Some(surface.clone());
        for ti in &self.instances {
            if ti.id().same_client_as(&surface.id()) {
                ti.enter(surface);
            }
        }
    }

    fn leave(&mut self, surface: &WlSurface) {
        for ti in &self.instances {
            if ti.id().same_client_as(&surface.id()) {
                ti.leave(surface);
            }
        }
        if self.focused_surface.as_ref() == Some(surface) {
            self.focused_surface = None;
        }
    }

    pub fn update_focus(&mut self, new_focus: Option<&WlSurface>) {
        if self.focused_surface.as_ref() == new_focus {
            return;
        }
        if let Some(old) = self.focused_surface.clone() {
            self.leave(&old);
        }
        if let Some(new) = new_focus {
            self.enter(new);
        }
    }

    // --- Android IME event handling ---

    /// Handle an incoming text input event from Android and send protocol
    /// events to the focused Wayland client.
    pub fn handle_android_event(&mut self, event: TextInputEvent) {
        let surface = match &self.focused_surface {
            Some(s) => s,
            None => return,
        };

        for ti in &self.instances {
            if !ti.id().same_client_as(&surface.id()) {
                continue;
            }

            let inst = match self.instance_state.get_mut(&ti.id()) {
                Some(s) => s,
                None => continue,
            };

            if !inst.enabled {
                continue;
            }

            match &event {
                TextInputEvent::CommitString { text } => {
                    // Clear preedit, then commit the text.
                    // Per done ordering: preedit cleared (step 1), then commit
                    // string inserted with cursor at its end (step 3).
                    ti.preedit_string(None, 0, 0);
                    ti.commit_string(Some(text.clone()));
                    inst.current_preedit = None;
                }
                TextInputEvent::SetPreeditString { text } => {
                    // Show composing text with cursor at end.
                    // cursor_begin=cursor_end=len means a caret at the end of
                    // the preedit, which is where the user is typing.
                    let len = text.len() as i32;
                    ti.preedit_string(Some(text.clone()), len, len);
                    inst.current_preedit = Some(text.clone());
                }
                TextInputEvent::FinishComposingText => {
                    // Finalize the current composing text: commit it, then
                    // clear the preedit. Without this, finishComposingText
                    // would discard the composing text entirely.
                    if let Some(ref preedit) = inst.current_preedit {
                        ti.commit_string(Some(preedit.clone()));
                    }
                    ti.preedit_string(None, 0, 0);
                    inst.current_preedit = None;
                }
                TextInputEvent::DeleteSurroundingText { before, after } => {
                    // Android sends character counts (UTF-16 code units).
                    // The Wayland protocol requires UTF-8 byte counts.
                    // Use the client's last reported surrounding text to convert.
                    let (before_bytes, after_bytes) = chars_to_bytes(
                        inst.surrounding.as_ref(),
                        *before,
                        *after,
                    );
                    ti.delete_surrounding_text(before_bytes, after_bytes);
                }
                TextInputEvent::KeyPress { .. } => {
                    unreachable!("KeyPress handled in event_loop.rs")
                }
            }

            ti.done(inst.commit_count);
        }
    }

    /// Clean up instances for dead resources.
    pub fn cleanup(&mut self) {
        self.instances.retain(|ti| {
            if ti.is_alive() {
                true
            } else {
                self.instance_state.remove(&ti.id());
                false
            }
        });
    }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

/// Convert a UTF-8 byte offset within `text` to a character count.
/// Clamps to the text length if offset is out of bounds.
fn byte_offset_to_char_count(text: &str, byte_offset: usize) -> usize {
    let clamped = byte_offset.min(text.len());
    text[..clamped].chars().count()
}

/// Convert Android character counts (before/after cursor) to UTF-8 byte counts
/// using the stored surrounding text for accurate conversion.
/// Falls back to 1:1 mapping (ASCII assumption) if no surrounding text is available.
fn chars_to_bytes(
    surrounding: Option<&SurroundingText>,
    before_chars: u32,
    after_chars: u32,
) -> (u32, u32) {
    let st = match surrounding {
        Some(st) => st,
        // No surrounding text from client; assume 1 byte per char (ASCII).
        // This is wrong for non-ASCII but we have no text to measure against.
        None => {
            info!("chars_to_bytes: no surrounding text, assuming ASCII");
            return (before_chars, after_chars);
        }
    };
    let cursor = st.cursor.min(st.text.len());

    let before_bytes: usize = st.text[..cursor]
        .chars()
        .rev()
        .take(before_chars as usize)
        .map(|c| c.len_utf8())
        .sum();

    let after_bytes: usize = st.text[cursor..]
        .chars()
        .take(after_chars as usize)
        .map(|c| c.len_utf8())
        .sum();

    (before_bytes as u32, after_bytes as u32)
}

// ---------------------------------------------------------------------------
// Protocol dispatch: zwp_text_input_manager_v3
// ---------------------------------------------------------------------------

impl GlobalDispatch<ZwpTextInputManagerV3, ()> for TawcState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<ZwpTextInputManagerV3>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        info!("Client bound zwp_text_input_manager_v3");
    }
}

impl Dispatch<ZwpTextInputManagerV3, ()> for TawcState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &ZwpTextInputManagerV3,
        request: zwp_text_input_manager_v3::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            zwp_text_input_manager_v3::Request::GetTextInput { id, seat: _ } => {
                let ti = data_init.init(id, TextInputData);
                info!("Created zwp_text_input_v3: {:?}", ti.id());
                state.text_input_state.add_instance(ti);
            }
            zwp_text_input_manager_v3::Request::Destroy => {}
            _ => {}
        }
    }
}

// ---------------------------------------------------------------------------
// Protocol dispatch: zwp_text_input_v3
// ---------------------------------------------------------------------------

impl Dispatch<ZwpTextInputV3, TextInputData> for TawcState {
    fn request(
        state: &mut Self,
        _client: &Client,
        resource: &ZwpTextInputV3,
        request: zwp_text_input_v3::Request,
        _data: &TextInputData,
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        let id = resource.id();
        match request {
            zwp_text_input_v3::Request::Enable => {
                state.text_input_state.set_pending_enable(&id);
            }
            zwp_text_input_v3::Request::Disable => {
                state.text_input_state.set_pending_disable(&id);
            }
            zwp_text_input_v3::Request::SetSurroundingText { text, cursor, anchor } => {
                state.text_input_state.set_pending_surrounding(&id, text, cursor, anchor);
            }
            zwp_text_input_v3::Request::SetTextChangeCause { cause } => {
                state.text_input_state.set_pending_change_cause(&id, cause.into());
            }
            zwp_text_input_v3::Request::SetContentType { .. } => {}
            zwp_text_input_v3::Request::SetCursorRectangle { .. } => {}
            zwp_text_input_v3::Request::Commit => {
                state.text_input_state.commit(&id);
            }
            zwp_text_input_v3::Request::Destroy => {
                state.text_input_state.remove_instance(&id);
            }
            _ => {}
        }
    }
}
