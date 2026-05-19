//! Runtime output-scale tests. The client observes the compositor through
//! wp_fractional_scale_v1, so this covers the app/broker -> server -> Wayland
//! protocol path rather than a compositor-internal state query.

use std::time::Duration;

use tawc_integration::helpers::{assert_compositor_clean, start_wayland_debug_scale, TIMEOUT};
use tawc_integration::{adb, GraphicsBackend};

const BACKEND: GraphicsBackend = GraphicsBackend::Cpu;

struct ScaleRestore {
    previous: f32,
}

impl Drop for ScaleRestore {
    fn drop(&mut self) {
        let _ = adb::set_output_scale(self.previous);
    }
}

#[test]
fn test_fractional_scale_updates_reach_wayland_client() {
    let previous = adb::get_output_scale().expect("get initial output scale");
    let _restore = ScaleRestore { previous };

    let out = adb::set_output_scale(2.25).expect("set initial output scale");
    assert!(
        out.status.success(),
        "set-output-scale 2.25 failed before launch: {}",
        String::from_utf8_lossy(&out.stderr)
    );

    let mut app = start_wayland_debug_scale(BACKEND, "");
    app.wait_for("SCALE_CHANGED", TIMEOUT)
        .expect("initial fractional scale event");

    for step in 0..=6 {
        let scale = 0.5 + step as f32 * 0.25;
        set_scale_and_expect(&app, scale);
    }

    app.stop().expect("scale debug app failed to stop cleanly");
    assert_compositor_clean();
}

fn set_scale_and_expect(app: &tawc_integration::debug_app::DebugApp, scale: f32) {
    let before = app.count_with_tag("SCALE_CHANGED");
    let expected = format!("{scale:.2}");
    let out = adb::set_output_scale(scale).expect("set output scale");
    assert!(
        out.status.success(),
        "set-output-scale {expected} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    app.wait_for_tag_count("SCALE_CHANGED", before + 1, Duration::from_secs(5))
        .unwrap_or_else(|e| panic!("client did not observe {expected}x scale: {e}"));
    let last = app
        .payloads_with_tag("SCALE_CHANGED")
        .last()
        .cloned()
        .unwrap_or_default();
    assert_eq!(last, expected, "latest client scale event");
}
