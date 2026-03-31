use std::ffi::c_void;
use std::os::unix::fs::PermissionsExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jobject;
use log::info;

use smithay::backend::egl::EGLContext;
use smithay::backend::egl::EGLSurface;
use smithay::backend::renderer::gles::{GlesRenderer, Uniform, UniformName, UniformType, UniformValue};
use smithay::backend::renderer::{Bind, Color32F, Frame, Renderer};
use smithay::utils::{Point, Rectangle, Size, Transform};
use smithay::wayland::compositor::{
    with_surface_tree_downward, SurfaceAttributes, TraversalAction,
};

use wayland_server::{Display, ListeningSocket};

mod egl_android;
mod ahb;
mod gl_import;
mod protocol;
mod compositor;

use egl_android::AndroidNativeSurface;
use gl_import::AhbTextureImporter;
use compositor::{TawcState, ClientState};

/// Global state shared between JNI calls.
static RUNNING: AtomicBool = AtomicBool::new(false);

/// Wayland socket path accessible from chroot.
/// Using /data/data/<package> ensures the app has write access.
/// The chroot can access this via bind-mount or direct path (with root).
const WAYLAND_SOCKET_PATH: &str = "/data/data/me.phie.tawc/wayland-0";

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_NativeBridge_nativeOnSurfaceCreated(
    env: JNIEnv,
    _class: JClass,
    surface: jobject,
) {
    // Initialize Android logger
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("tawc-native"),
    );

    info!("nativeOnSurfaceCreated called");

    // Get ANativeWindow from the Java Surface
    let window_ptr = unsafe {
        let ptr = ndk_sys::ANativeWindow_fromSurface(env.get_raw(), surface);
        if ptr.is_null() {
            log::error!("Failed to get ANativeWindow from Surface");
            return;
        }
        ptr as *mut c_void
    };

    let width = unsafe { ndk_sys::ANativeWindow_getWidth(window_ptr as *mut _) };
    let height = unsafe { ndk_sys::ANativeWindow_getHeight(window_ptr as *mut _) };
    info!("Native window: {}x{}", width, height);

    RUNNING.store(true, Ordering::SeqCst);

    // ANativeWindow_fromSurface already acquires a ref. Acquire another for the render thread.
    unsafe { ndk_sys::ANativeWindow_acquire(window_ptr as *mut _) };
    // Release the ref from fromSurface (render thread owns it now)
    unsafe { ndk_sys::ANativeWindow_release(window_ptr as *mut _) };

    // Spawn render thread
    let window_addr = window_ptr as usize;
    std::thread::spawn(move || {
        let window_ptr = window_addr as *mut c_void;
        info!("Compositor thread started");

        if let Err(e) = run_compositor(window_ptr, width, height) {
            log::error!("Compositor failed: {}", e);
        }

        unsafe { ndk_sys::ANativeWindow_release(window_ptr as *mut _) };
        info!("Compositor thread exited");
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_NativeBridge_nativeOnSurfaceChanged(
    _env: JNIEnv,
    _class: JClass,
    _width: i32,
    _height: i32,
) {
    info!("nativeOnSurfaceChanged: {}x{}", _width, _height);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_phie_tawc_NativeBridge_nativeOnSurfaceDestroyed(
    _env: JNIEnv,
    _class: JClass,
) {
    info!("nativeOnSurfaceDestroyed");
    RUNNING.store(false, Ordering::SeqCst);
}

/// Main compositor loop: sets up Wayland display, listens for clients,
/// dispatches protocol events, and renders.
fn run_compositor(
    window_ptr: *mut c_void,
    width: i32,
    height: i32,
) -> Result<(), Box<dyn std::error::Error>> {
    // --- EGL setup (same as Phase 2) ---
    let (raw_display, raw_config, raw_context) =
        unsafe { egl_android::create_raw_egl_context()? };
    let egl_context = unsafe { EGLContext::from_raw(raw_display, raw_config, raw_context)? };
    let mut renderer = unsafe { GlesRenderer::new(egl_context)? };

    let native_surface = AndroidNativeSurface::new(window_ptr);
    let display_ref = renderer.egl_context().display();
    let pixel_format = renderer.egl_context().pixel_format().ok_or("No pixel format")?;
    let config_id = renderer.egl_context().config_id();
    let mut egl_surface =
        unsafe { EGLSurface::new(display_ref, pixel_format, config_id, native_surface)? };

    let importer = AhbTextureImporter::new()
        .map_err(|e| format!("Failed to load AHB importer: {}", e))?;
    info!("EGL + GlesRenderer + AHB importer ready");

    // Compile the magenta tint shader for SHM buffers (must be done before
    // renderer is borrowed mutably for other things, and after EGL context exists)
    let shm_tint_shader = match renderer.compile_custom_texture_shader(
        r#"
#version 100

//_DEFINES_

#if defined(EXTERNAL)
#extension GL_OES_EGL_image_external : require
#endif

precision mediump float;
#if defined(EXTERNAL)
uniform samplerExternalOES tex;
#else
uniform sampler2D tex;
#endif

uniform float alpha;
varying vec2 v_coords;
uniform float magenta_mix;

#if defined(DEBUG_FLAGS)
uniform float tint;
#endif

void main() {
    vec4 color = texture2D(tex, v_coords);

#if defined(NO_ALPHA)
    color = vec4(color.rgb, 1.0) * alpha;
#else
    color = color * alpha;
#endif

    // Apply magenta tint: boost red and blue, reduce green
    vec3 magenta = vec3(color.r * 1.0 + 0.3, color.g * 0.4, color.b * 1.0 + 0.3);
    color.rgb = mix(color.rgb, magenta, magenta_mix);

#if defined(DEBUG_FLAGS)
    if (tint == 1.0)
        color = vec4(0.0, 0.2, 0.0, 0.2) + color * 0.8;
#endif

    gl_FragColor = color;
}
"#,
        &[UniformName::new("magenta_mix", UniformType::_1f)],
    ) {
        Ok(program) => {
            info!("SHM magenta tint shader compiled successfully");
            Some(program)
        }
        Err(e) => {
            log::error!("Failed to compile SHM tint shader: {:?}", e);
            None
        }
    };

    // --- Wayland display setup ---
    let mut wl_display: Display<TawcState> = Display::new()?;
    let mut state = TawcState::new(&mut wl_display);
    state.importer = Some(importer);
    state.shm_tint_shader = shm_tint_shader;
    state.raw_egl_display = raw_display;
    state.raw_egl_context = raw_context;

    // Create output
    let output = smithay::output::Output::new(
        "tawc-0".to_string(),
        smithay::output::PhysicalProperties {
            size: (0, 0).into(),
            subpixel: smithay::output::Subpixel::Unknown,
            make: "tawc".into(),
            model: "Android".into(),
        },
    );
    let mode = smithay::output::Mode {
        size: (width, height).into(),
        refresh: 60_000,
    };
    output.change_current_state(
        Some(mode),
        Some(Transform::Normal),
        Some(smithay::output::Scale::Integer(1)),
        Some((0, 0).into()),
    );
    output.set_preferred(mode);
    let _output_global = output.create_global::<TawcState>(&state.display_handle);
    info!("Wayland output created: {}x{}", width, height);

    // --- Wayland listening socket ---
    // Remove stale socket
    let _ = std::fs::remove_file(WAYLAND_SOCKET_PATH);
    // Ensure parent directory exists
    if let Some(parent) = std::path::Path::new(WAYLAND_SOCKET_PATH).parent() {
        let _ = std::fs::create_dir_all(parent);
    }

    let listener = ListeningSocket::bind_absolute(WAYLAND_SOCKET_PATH.into())?;
    let _ = std::fs::set_permissions(WAYLAND_SOCKET_PATH, std::fs::Permissions::from_mode(0o777));
    info!("Wayland socket listening at {}", WAYLAND_SOCKET_PATH);

    // --- Main event loop ---
    let output_size = Size::from((width, height));
    let start_time = Instant::now();
    let mut frame_count: u64 = 0;

    while RUNNING.load(Ordering::SeqCst) {
        // Accept new client connections
        if let Some(stream) = listener.accept()? {
            info!("New Wayland client connected");
            wl_display
                .handle()
                .insert_client(stream, Arc::new(ClientState::default()))?;
        }

        // Dispatch Wayland protocol events
        match wl_display.dispatch_clients(&mut state) {
            Ok(_) => {},
            Err(e) => {
                log::error!("dispatch_clients error: {} -- client may be broken", e);
            }
        }
        match wl_display.flush_clients() {
            Ok(_) => {},
            Err(e) => {
                log::error!("flush_clients error: {}", e);
            }
        }

        // Import any pending AHBs (done here because we need &GlesRenderer)
        let surfaces_with_pending: Vec<_> = state
            .surface_ahb
            .iter()
            .filter(|(_, s)| s.pending_width.is_some())
            .map(|(surf, _)| surf.clone())
            .collect();

        for surface in surfaces_with_pending {
            state.import_pending_ahb(&surface, &renderer);
        }

        // Import any pending SHM buffers from toplevel surfaces
        // (surfaces not using AHB channels that have wl_shm buffers attached)
        let toplevel_surfaces: Vec<_> = state.toplevels.iter()
            .map(|t| t.wl_surface().clone())
            .filter(|s| !state.surface_ahb.contains_key(s))
            .collect();
        for surface in toplevel_surfaces {
            state.import_pending_shm(&surface, &mut renderer);
        }

        // --- Render ---
        let t = (frame_count as f32) / 240.0;
        let gray = (t.sin() * 0.15 + 0.2).clamp(0.0, 1.0);
        let bg_color = Color32F::new(gray * 0.3, gray * 0.1, gray * 0.3, 1.0);

        let mut target = renderer.bind(&mut egl_surface)?;
        let mut frame = renderer.render(&mut target, output_size, Transform::Normal)?;
        frame.clear(bg_color, &[Rectangle::from_size(output_size)])?;

        // Render ALL surfaces that have AHB textures
        let surfaces_to_render: Vec<_> = state
            .surface_ahb
            .values()
            .filter_map(|ahb_state| {
                ahb_state.texture.as_ref().map(|tex| {
                    (tex.clone(), ahb_state.committed_width, ahb_state.committed_height)
                })
            })
            .collect();

        for (texture, tex_w, tex_h) in &surfaces_to_render {
            let tex_x = (width - tex_w) / 2;
            let tex_y = (height - tex_h) / 2;
            let texture_size = Size::<i32, smithay::utils::Buffer>::from((*tex_w, *tex_h));
            let src_rect = Rectangle::from_size(texture_size.to_f64());
            let dst_rect = Rectangle::new(
                Point::from((tex_x, tex_y)),
                Size::from((*tex_w, *tex_h)),
            );
            let damage_rect = Rectangle::from_size(Size::from((*tex_w, *tex_h)));
            Frame::render_texture_from_to(
                &mut frame,
                texture,
                src_rect,
                dst_rect,
                &[damage_rect],
                &[],
                Transform::Normal,
                1.0,
            )?;
        }

        // Render SHM surfaces with magenta tint
        let shm_surfaces_to_render: Vec<_> = state
            .surface_shm
            .values()
            .filter_map(|shm_state| {
                shm_state.texture.as_ref().map(|tex| {
                    (tex.clone(), shm_state.committed_width, shm_state.committed_height)
                })
            })
            .collect();

        let shm_shader = state.shm_tint_shader.clone();
        for (texture, tex_w, tex_h) in &shm_surfaces_to_render {
            let tex_x = (width - tex_w) / 2;
            let tex_y = (height - tex_h) / 2;
            let texture_size = Size::<i32, smithay::utils::Buffer>::from((*tex_w, *tex_h));
            let src_rect = Rectangle::from_size(texture_size.to_f64());
            let dst_rect = Rectangle::new(
                Point::from((tex_x, tex_y)),
                Size::from((*tex_w, *tex_h)),
            );
            let damage_rect = Rectangle::from_size(Size::from((*tex_w, *tex_h)));
            frame.render_texture_from_to(
                texture,
                src_rect,
                dst_rect,
                &[damage_rect],
                &[],
                Transform::Normal,
                1.0,
                shm_shader.as_ref(),
                &[Uniform::new("magenta_mix", UniformValue::_1f(1.0))],
            )?;
        }

        let _ = frame.finish()?;
        drop(target);
        egl_surface.swap_buffers(None)?;

        // Send frame callbacks to all toplevel surfaces
        let time = start_time.elapsed().as_millis() as u32;
        for toplevel in &state.toplevels {
            send_frames_surface_tree(toplevel.wl_surface(), time);
        }

        // Remove toplevels whose xdg surface is no longer alive,
        // and clean up associated SHM state
        state.toplevels.retain(|t| {
            if t.alive() {
                true
            } else {
                state.surface_shm.remove(t.wl_surface());
                false
            }
        });

        frame_count += 1;
        if frame_count % 300 == 0 {
            info!(
                "Compositor: {} frames, {} toplevels, {} ahb channels, {} shm surfaces",
                frame_count,
                state.toplevels.len(),
                state.surface_ahb.len(),
                state.surface_shm.len(),
            );
        }

        // ~60fps
        std::thread::sleep(Duration::from_millis(16));
    }

    info!("Compositor finished after {} frames", frame_count);
    Ok(())
}

/// Send frame done callbacks to all surfaces in a surface tree.
fn send_frames_surface_tree(surface: &wayland_server::protocol::wl_surface::WlSurface, time: u32) {
    with_surface_tree_downward(
        surface,
        (),
        |_, _, &()| TraversalAction::DoChildren(()),
        |_surf, states, &()| {
            for callback in states
                .cached_state
                .get::<SurfaceAttributes>()
                .current()
                .frame_callbacks
                .drain(..)
            {
                callback.done(time);
            }
        },
        |_, _, &()| true,
    );
}
