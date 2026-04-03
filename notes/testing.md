# Testing Infrastructure

## Overview

Automated integration tests for the compositor. A GTK3 debug app runs on the phone in the chroot, producing structured output. Rust integration tests on the host drive it via adb.

```
testing/
  gtk3-debug-app/         C + GTK3, runs on phone in chroot
  integration/            Rust tests, runs on host
  build-debug-app.sh      Manual build script
```

## Debug App (`gtk3-debug-app`)

A C program with subcommands for testing specific compositor features. Compiles inside the chroot with `gcc` + `pkg-config --cflags --libs gtk+-3.0`.

### Output Protocol

Every test-relevant line is prefixed `TAWC_DEBUG:` to filter from GTK/Wayland noise:
```
TAWC_DEBUG:READY                    Window mapped, text view focused
TAWC_DEBUG:TEXT_CHANGED:<text>      Full buffer contents after change
TAWC_DEBUG:CURSOR_POS:<offset>     Cursor position (character offset) after mark-set
TAWC_DEBUG:PREEDIT:<text>           Current composing/preedit string
```

### Commands

| Command | Description |
|---------|-------------|
| `text-input` | Opens a GtkTextView, reports text changes |

### Building

```bash
# From host (pushes source, builds in chroot):
bash testing/build-debug-app.sh

# Or manually in chroot:
cd /tmp/gtk3-debug-app && bash build.sh
```

### Running Manually

```bash
adb shell su -c "/system_ext/bin/bash /data/local/tmp/arch-chroot-run '/tmp/gtk3-debug-app/gtk3-debug-app text-input'"
```

## Integration Tests

Rust tests using `std::process::Command` to call adb. Zero external dependencies.

### Running

```bash
cd testing/integration
cargo test -- --nocapture --test-threads=1
```

Tests require:
- Phone connected via adb
- Compositor APK installed and running
- SELinux permissive (`adb shell su -c setenforce 0`)
- `arch-chroot-run` pushed to phone

### Test Input Mechanism

Tests inject input via Android broadcast intents, not `adb shell input text`:

```bash
# Text input (goes through nativeCommitText -> text_input_v3):
adb shell am broadcast -a me.phie.tawc.TEXT_INPUT --es text "hello"

# Key event (goes through nativeSendKeyEvent -> wl_keyboard):
adb shell am broadcast -a me.phie.tawc.KEY_EVENT --ei keycode 67
```

This is more reliable than `adb shell input text` which gets intercepted by the IME (Gboard) and may not reach the InputConnection. The broadcast approach goes directly through the same JNI path as real IME input.

### Architecture

```
Host (cargo test)                    Phone
  │                                    │
  ├─ adb push source ──────────────────┤ (one-time build)
  ├─ adb shell (build in chroot) ──────┤
  │                                    │
  ├─ adb shell (start debug app) ──────┤──→ gtk3-debug-app
  │     └─ piped stdout ←──────────────┤     └─ TAWC_DEBUG:READY
  │                                    │
  ├─ am broadcast TEXT_INPUT ──────────┤──→ BroadcastReceiver
  │                                    │     └─ nativeCommitText
  │                                    │       └─ text_input_v3
  │                                    │         └─ GTK text view
  │     └─ TAWC_DEBUG:TEXT_CHANGED ←───┤
  │                                    │
  ├─ adb shell input tap X Y ─────────┤──→ SurfaceView.onTouchEvent
  │                                    │     └─ nativeOnTouchEvent
  │                                    │       └─ wl_touch → GDK_TOUCH_BEGIN
  │                                    │         └─ GtkGestureMultiPress
  │                                    │           └─ cursor move
  │     └─ TAWC_DEBUG:CURSOR_POS ←────┤
  │                                    │
  └─ assert text/cursor == expected    │
```

### Key Modules

- **`adb.rs`**: Shell commands, chroot execution, broadcast-based input injection
- **`chroot.rs`**: Push source, ensure build deps, build debug app
- **`debug_app.rs`**: Start/stop lifecycle, stdout reader thread, `wait_for()` with timeout
- **`compositor.rs`**: Ensure compositor is running

## Adding New Tests

1. Add a new command to `gtk3-debug-app.c` (new function + entry in `commands[]`)
2. Define protocol messages (`TAWC_DEBUG:YOUR_EVENT:value`)
3. Add a new test file in `testing/integration/tests/`
4. Use the same `DebugApp` harness (`start`, `wait_ready`, `wait_for`)

## Design Decisions

- **C for debug app**: Sub-second builds, no cargo on phone, `base-devel` already in chroot
- **Broadcast intents over `adb shell input text`**: The IME (Gboard) intercepts `input text` key events and may buffer/autocorrect them. Broadcasts go directly through `nativeCommitText`, the same JNI path as real IME input
- **Reader thread + mpsc channel**: adb stdout is a blocking stream. Thread drains it continuously, mpsc gives timeout-based waiting
- **`killall` in Drop**: Process chain `adb -> su -> bash -> chroot -> bash -> app` doesn't propagate signals
- **Full buffer in TEXT_CHANGED**: Tests assert exact equality without tracking incremental changes
- **`--test-threads=1`**: Tests share the phone and compositor, can't run in parallel
