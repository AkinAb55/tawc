//! Touch input delivery from Android to Wayland clients.
//!
//! Android touch events arrive on the JNI thread via nativeOnTouchEvent.
//! They're sent through a calloop channel to the compositor thread, which
//! delivers them as wl_touch events via Smithay's TouchHandle.

use std::sync::OnceLock;

use smithay::reexports::calloop::channel;

/// A touch event from Android, in physical pixel coordinates.
pub enum TouchEvent {
    Down { id: i32, x: f32, y: f32, time: u32 },
    Motion { id: i32, x: f32, y: f32, time: u32 },
    Up { id: i32, time: u32 },
}

/// Global sender. Set once when the compositor starts, used by JNI callbacks.
static TOUCH_SENDER: OnceLock<channel::Sender<TouchEvent>> = OnceLock::new();

/// Create the calloop channel pair. Returns the receiver (for the event loop).
/// The sender is stored globally for JNI access.
pub fn create_touch_channel() -> channel::Channel<TouchEvent> {
    let (sender, channel) = channel::channel();
    TOUCH_SENDER
        .set(sender)
        .unwrap_or_else(|_| log::warn!("Touch channel already initialized"));
    channel
}

/// Send a touch event from JNI. No-op if the channel isn't set up yet.
pub fn send_touch_event(event: TouchEvent) {
    if let Some(sender) = TOUCH_SENDER.get() {
        let _ = sender.send(event);
    }
}
