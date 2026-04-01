use std::collections::HashMap;
use std::os::fd::{AsFd, OwnedFd};
use std::os::unix::io::AsRawFd;
use std::os::unix::net::UnixStream;
use log::{error, info};

use smithay::backend::renderer::gles::{GlesRenderer, GlesTexProgram, GlesTexture};
use smithay::backend::renderer::{buffer_type, BufferType, ImportMemWl};
use smithay::delegate_compositor;
use smithay::delegate_data_device;
use smithay::delegate_output;
use smithay::delegate_seat;
use smithay::delegate_shm;
use smithay::delegate_xdg_shell;
use smithay::input::{Seat, SeatHandler, SeatState};
use smithay::reexports::wayland_server::protocol::wl_seat;
use smithay::reexports::wayland_server::protocol::wl_buffer;
use smithay::reexports::wayland_server::protocol::wl_surface::WlSurface;
use smithay::reexports::wayland_server::{
    Client, DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource,
};
use smithay::reexports::wayland_server::backend::{ClientData, ClientId, DisconnectReason};
use smithay::reexports::wayland_server::Display;
use smithay::utils::{Rectangle, Serial, Size};
use smithay::wayland::buffer::BufferHandler;
use smithay::wayland::compositor::{
    CompositorClientState, CompositorHandler, CompositorState, SurfaceAttributes,
    with_states, with_surface_tree_downward, TraversalAction,
};
use smithay::wayland::selection::data_device::{
    ClientDndGrabHandler, DataDeviceHandler, DataDeviceState, ServerDndGrabHandler,
};
use smithay::wayland::selection::{SelectionHandler, SelectionTarget, SelectionSource};
use smithay::wayland::shell::xdg::{
    PopupSurface, PositionerState, ToplevelSurface, XdgShellHandler, XdgShellState,
};
use smithay::wayland::output::OutputHandler;
use smithay::wayland::shm::{ShmHandler, ShmState};

use crate::ahb::AhbBuffer;
use crate::gl_import::AhbTextureImporter;
use crate::protocol::tawc_buffer_v1::server::{
    tawc_ahb_channel_v1::{self, TawcAhbChannelV1},
    tawc_buffer_manager_v1::{self, TawcBufferManagerV1},
};

/// Per-surface AHB channel state.
pub struct SurfaceAhbState {
    /// Our end of the side-channel socketpair for receiving AHBs.
    pub recv_socket: UnixStream,
    /// Pending AHB dimensions (set by attach, applied on commit).
    pub pending_width: Option<i32>,
    pub pending_height: Option<i32>,
    /// The currently committed (imported) texture for this surface.
    pub texture: Option<GlesTexture>,
    /// The currently committed AHB (kept alive so the texture remains valid).
    pub ahb: Option<AhbBuffer>,
    /// Width/height of the committed buffer.
    pub committed_width: i32,
    pub committed_height: i32,
}

/// Per-surface SHM buffer state.
pub struct SurfaceShmState {
    /// The currently committed (imported) texture for this surface.
    pub texture: Option<GlesTexture>,
    /// The wl_buffer we imported (kept so we can detect when a new buffer arrives
    /// and release the old one — we must NOT release the same buffer twice).
    pub current_buffer: Option<wl_buffer::WlBuffer>,
    /// Width/height of the committed buffer.
    pub committed_width: i32,
    pub committed_height: i32,
}

/// Data stored per tawc_ahb_channel_v1 resource.
pub struct ChannelData {
    pub surface: WlSurface,
}

/// Main compositor state.
pub struct TawcState {
    pub display_handle: DisplayHandle,
    pub compositor_state: CompositorState,
    pub shm_state: ShmState,
    pub xdg_shell_state: XdgShellState,
    pub data_device_state: DataDeviceState,
    pub seat_state: SeatState<Self>,
    pub seat: Seat<Self>,

    /// Per-surface AHB channel state, keyed by WlSurface id.
    pub surface_ahb: HashMap<WlSurface, SurfaceAhbState>,

    /// Per-surface SHM buffer state (for surfaces using wl_shm).
    pub surface_shm: HashMap<WlSurface, SurfaceShmState>,

    /// Custom shader for rendering SHM buffers with magenta tint.
    pub shm_tint_shader: Option<GlesTexProgram>,

    /// Toplevel surfaces (for rendering).
    pub toplevels: Vec<ToplevelSurface>,

    /// AHB texture importer (loaded once).
    pub importer: Option<AhbTextureImporter>,

    /// Raw EGL display pointer (needed for AHB import).
    pub raw_egl_display: *const std::ffi::c_void,
    /// Raw EGL context pointer.
    pub raw_egl_context: *const std::ffi::c_void,
}

unsafe impl Send for TawcState {}

impl TawcState {
    pub fn new(display: &mut Display<Self>) -> Self {
        let dh = display.handle();

        let compositor_state = CompositorState::new::<Self>(&dh);
        let xdg_shell_state = XdgShellState::new::<Self>(&dh);
        let shm_state = ShmState::new::<Self>(&dh, []);
        let data_device_state = DataDeviceState::new::<Self>(&dh);
        let mut seat_state = SeatState::new();
        let mut seat = seat_state.new_wl_seat(&dh, "tawc");
        // Advertise pointer capability so clients (esp. Firefox) will create windows
        seat.add_pointer();

        // Register tawc_buffer_manager_v1 global
        dh.create_global::<Self, TawcBufferManagerV1, ()>(1, ());

        Self {
            display_handle: dh,
            compositor_state,
            shm_state,
            xdg_shell_state,
            data_device_state,
            seat_state,
            seat,
            surface_ahb: HashMap::new(),
            surface_shm: HashMap::new(),
            shm_tint_shader: None,
            toplevels: Vec::new(),
            importer: None,
            raw_egl_display: std::ptr::null(),
            raw_egl_context: std::ptr::null(),
        }
    }

    /// Import pending AHB for a surface (called during commit).
    pub fn import_pending_ahb(&mut self, surface: &WlSurface, renderer: &GlesRenderer) {
        let Some(ahb_state) = self.surface_ahb.get_mut(surface) else {
            return;
        };

        let (Some(width), Some(height)) = (ahb_state.pending_width.take(), ahb_state.pending_height.take()) else {
            return;
        };

        // Receive one AHB from side channel (blocking socket, but we only
        // call this when pending_width is set, meaning client sent an attach).
        // Use non-blocking mode temporarily to drain all pending AHBs.
        let recv_fd = ahb_state.recv_socket.as_raw_fd();
        ahb_state.recv_socket.set_nonblocking(true).ok();
        let mut latest_ahb = None;
        loop {
            match AhbBuffer::recv_from_socket(recv_fd) {
                Ok(ahb) => {
                    latest_ahb = Some(ahb);
                }
                Err(_) => break,
            }
        }
        ahb_state.recv_socket.set_nonblocking(false).ok();

        match latest_ahb {
            Some(ahb) => {
                info!("Received AHB {}x{} for surface {:?}", ahb.width(), ahb.height(), surface.id());

                // Import as texture
                if let Some(ref importer) = self.importer {
                    // Make context current for GL operations
                    unsafe {
                        smithay::backend::egl::ffi::egl::MakeCurrent(
                            self.raw_egl_display,
                            smithay::backend::egl::ffi::egl::NO_SURFACE,
                            smithay::backend::egl::ffi::egl::NO_SURFACE,
                            self.raw_egl_context,
                        );
                    }
                    match importer.import_ahb(
                        renderer,
                        self.raw_egl_display,
                        ahb.as_raw(),
                        width,
                        height,
                    ) {
                        Ok(texture) => {
                            info!("AHB imported as texture for surface {:?}", surface.id());
                            ahb_state.texture = Some(texture);
                            ahb_state.ahb = Some(ahb);
                            ahb_state.committed_width = width;
                            ahb_state.committed_height = height;
                        }
                        Err(e) => error!("Failed to import AHB: {}", e),
                    }
                } else {
                    error!("No AHB importer available");
                }
            }
            None => {
                // No AHB available yet -- client sent attach but hasn't sent the AHB
            }
        }
    }

    /// Import SHM buffers from the surface tree of all toplevels.
    /// Called from the render loop where we have renderer access.
    /// Only imports NEW buffers (compares against the previously imported buffer)
    /// and properly releases old buffers when replaced.
    pub fn import_shm_from_surface_trees(&mut self, renderer: &mut GlesRenderer) {
        let toplevel_surfaces: Vec<_> = self.toplevels.iter()
            .map(|t| t.wl_surface().clone())
            .collect();

        // Collect (surface, buffer) pairs to import
        let mut to_import: Vec<(WlSurface, wl_buffer::WlBuffer)> = Vec::new();

        for root in &toplevel_surfaces {
            with_surface_tree_downward(
                root,
                (),
                |_, _, &()| TraversalAction::DoChildren(()),
                |surf, surf_states, &()| {
                    // Skip surfaces that use AHB channels
                    if self.surface_ahb.contains_key(surf) {
                        return;
                    }
                    let mut guard = surf_states.cached_state.get::<SurfaceAttributes>();
                    let attrs = guard.current();

                    // Log buffer state changes
                    static SCAN_COUNT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
                    let scan = SCAN_COUNT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    let has_buffer = attrs.buffer.is_some();
                    if has_buffer || scan < 10 {
                        let buf_desc = match &attrs.buffer {
                            Some(smithay::wayland::compositor::BufferAssignment::NewBuffer(b)) =>
                                format!("NewBuffer({:?}, type={:?})", b.id(), buffer_type(b)),
                            Some(smithay::wayland::compositor::BufferAssignment::Removed) => "Removed".to_string(),
                            None => "None".to_string(),
                        };
                        info!("tree_scan[{}]: {:?} buffer={}", scan, surf.id(), buf_desc);
                    }

                    if let Some(smithay::wayland::compositor::BufferAssignment::NewBuffer(ref buf)) = attrs.buffer {
                        if matches!(buffer_type(buf), Some(BufferType::Shm)) {
                            // Check if this is the SAME buffer we already imported
                            if let Some(existing) = self.surface_shm.get(surf) {
                                if let Some(ref current_buf) = existing.current_buffer {
                                    if current_buf.id() == buf.id() {
                                        return; // Already imported this exact buffer
                                    }
                                }
                            }
                            to_import.push((surf.clone(), buf.clone()));
                        }
                    }
                },
                |_, _, &()| true,
            );
        }

        // Now import the collected buffers (needs &mut renderer)
        for (surface, buf) in to_import {
            let dims = smithay::wayland::shm::with_buffer_contents(&buf, |_, _, data| {
                (data.width, data.height)
            });
            let (width, height) = match dims {
                Ok(d) => d,
                Err(e) => {
                    error!("Failed to get SHM buffer contents: {}", e);
                    continue;
                }
            };
            let damage = [Rectangle::from_size(Size::from((width, height)))];
            match renderer.import_shm_buffer(&buf, None, &damage) {
                Ok(texture) => {
                    let is_new = !self.surface_shm.contains_key(&surface);
                    let shm_state = self.surface_shm.entry(surface.clone()).or_insert(SurfaceShmState {
                        texture: None,
                        current_buffer: None,
                        committed_width: 0,
                        committed_height: 0,
                    });
                    // Release the OLD buffer if we had one
                    if let Some(old_buf) = shm_state.current_buffer.take() {
                        old_buf.release();
                    }
                    shm_state.texture = Some(texture);
                    shm_state.current_buffer = Some(buf);
                    shm_state.committed_width = width;
                    shm_state.committed_height = height;
                    if is_new {
                        info!("SHM buffer imported: {}x{} for {:?}", width, height, surface.id());
                    }
                }
                Err(e) => {
                    error!("SHM import failed: {}", e);
                }
            }
        }
    }

}

// --- BufferHandler ---

impl BufferHandler for TawcState {
    fn buffer_destroyed(&mut self, _buffer: &wl_buffer::WlBuffer) {}
}

// --- ShmHandler ---

impl ShmHandler for TawcState {
    fn shm_state(&self) -> &ShmState {
        &self.shm_state
    }
}

// --- CompositorHandler ---

impl CompositorHandler for TawcState {
    fn compositor_state(&mut self) -> &mut CompositorState {
        &mut self.compositor_state
    }

    fn client_compositor_state<'a>(&self, client: &'a Client) -> &'a CompositorClientState {
        &client.get_data::<ClientState>().unwrap().compositor_state
    }

    fn commit(&mut self, surface: &WlSurface) {
        // Check both pending and current for the buffer
        static COMMIT_COUNT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let count = COMMIT_COUNT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

        let (pending_buf, current_buf) = with_states(surface, |states| {
            let mut guard = states.cached_state.get::<SurfaceAttributes>();
            let p = match &guard.pending().buffer {
                Some(smithay::wayland::compositor::BufferAssignment::NewBuffer(b)) =>
                    Some(format!("NewBuffer({:?})", b.id())),
                Some(smithay::wayland::compositor::BufferAssignment::Removed) => Some("Removed".to_string()),
                None => None,
            };
            let c = match &guard.current().buffer {
                Some(smithay::wayland::compositor::BufferAssignment::NewBuffer(b)) =>
                    Some(format!("NewBuffer({:?})", b.id())),
                Some(smithay::wayland::compositor::BufferAssignment::Removed) => Some("Removed".to_string()),
                None => None,
            };
            (p, c)
        });

        if count < 20 || pending_buf.is_some() || current_buf.is_some() {
            info!("commit[{}]: {:?} pending={} current={}",
                count, surface.id(),
                pending_buf.as_deref().unwrap_or("None"),
                current_buf.as_deref().unwrap_or("None"));
        }
    }
}

// --- XdgShellHandler ---

impl XdgShellHandler for TawcState {
    fn xdg_shell_state(&mut self) -> &mut XdgShellState {
        &mut self.xdg_shell_state
    }

    fn new_toplevel(&mut self, surface: ToplevelSurface) {
        info!("New toplevel surface: {:?}", surface.wl_surface().id());
        surface.with_pending_state(|state| {
            state.states.set(
                wayland_protocols::xdg::shell::server::xdg_toplevel::State::Activated,
            );
        });
        surface.send_configure();
        self.toplevels.push(surface);
    }

    fn new_popup(&mut self, _surface: PopupSurface, _positioner: PositionerState) {}

    fn grab(&mut self, _surface: PopupSurface, _seat: wl_seat::WlSeat, _serial: Serial) {}

    fn reposition_request(
        &mut self,
        _surface: PopupSurface,
        _positioner: PositionerState,
        _token: u32,
    ) {
    }
}

// --- OutputHandler ---

impl OutputHandler for TawcState {}

// --- SeatHandler ---

impl SeatHandler for TawcState {
    type KeyboardFocus = WlSurface;
    type PointerFocus = WlSurface;
    type TouchFocus = WlSurface;

    fn seat_state(&mut self) -> &mut SeatState<Self> {
        &mut self.seat_state
    }

    fn focus_changed(&mut self, _seat: &Seat<Self>, _focused: Option<&WlSurface>) {}
    fn cursor_image(
        &mut self,
        _seat: &Seat<Self>,
        _image: smithay::input::pointer::CursorImageStatus,
    ) {
    }
}

// --- tawc_buffer_manager_v1 GlobalDispatch ---

impl GlobalDispatch<TawcBufferManagerV1, ()> for TawcState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<TawcBufferManagerV1>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        info!("Client bound tawc_buffer_manager_v1");
    }
}

// --- tawc_buffer_manager_v1 Dispatch ---

impl Dispatch<TawcBufferManagerV1, ()> for TawcState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &TawcBufferManagerV1,
        request: tawc_buffer_manager_v1::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            tawc_buffer_manager_v1::Request::GetChannel { id, surface } => {
                info!("get_channel for surface {:?}", surface.id());

                // Create socketpair for AHB side channel
                let (compositor_sock, client_sock) = match UnixStream::pair() {
                    Ok(pair) => pair,
                    Err(e) => {
                        error!("Failed to create socketpair: {}", e);
                        return;
                    }
                };

                let channel_data = ChannelData {
                    surface: surface.clone(),
                };
                let channel = data_init.init(id, channel_data);

                // Send the client's end of the socketpair
                channel.channel_fd(client_sock.as_fd());
                info!("Sent side-channel fd to client");

                // Store compositor's end (keep blocking -- AHB recv needs it)
                state.surface_ahb.insert(
                    surface,
                    SurfaceAhbState {
                        recv_socket: compositor_sock,
                        pending_width: None,
                        pending_height: None,
                        texture: None,
                        ahb: None,
                        committed_width: 0,
                        committed_height: 0,
                    },
                );
            }
            tawc_buffer_manager_v1::Request::Destroy => {}
        }
    }
}

// --- tawc_ahb_channel_v1 Dispatch ---

impl Dispatch<TawcAhbChannelV1, ChannelData> for TawcState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &TawcAhbChannelV1,
        request: tawc_ahb_channel_v1::Request,
        data: &ChannelData,
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            tawc_ahb_channel_v1::Request::Attach { width, height } => {
                info!("AHB attach: {}x{} for surface {:?}", width, height, data.surface.id());
                if let Some(ahb_state) = state.surface_ahb.get_mut(&data.surface) {
                    ahb_state.pending_width = Some(width);
                    ahb_state.pending_height = Some(height);
                }
            }
            tawc_ahb_channel_v1::Request::Destroy => {
                info!("AHB channel destroyed for surface {:?}", data.surface.id());
                state.surface_ahb.remove(&data.surface);
            }
        }
    }
}

// --- Client state ---

#[derive(Default)]
pub struct ClientState {
    pub compositor_state: CompositorClientState,
}

impl ClientData for ClientState {
    fn initialized(&self, _client_id: ClientId) {
        info!("Wayland client initialized");
    }

    fn disconnected(&self, _client_id: ClientId, _reason: DisconnectReason) {
        info!("Wayland client disconnected: {:?}", _reason);
    }
}

// --- DataDeviceHandler (stub for GTK3 compatibility) ---

impl DataDeviceHandler for TawcState {
    fn data_device_state(&self) -> &DataDeviceState {
        &self.data_device_state
    }
}

impl ClientDndGrabHandler for TawcState {}
impl ServerDndGrabHandler for TawcState {}

impl SelectionHandler for TawcState {
    type SelectionUserData = ();
}

// --- Delegate macros ---

delegate_compositor!(TawcState);
delegate_data_device!(TawcState);
delegate_output!(TawcState);
delegate_shm!(TawcState);
delegate_xdg_shell!(TawcState);
delegate_seat!(TawcState);
