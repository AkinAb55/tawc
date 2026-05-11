//! gfxstream-bridge custom Vulkan WSI: server side of the
//! `tawc_gfxstream` Wayland protocol.
//!
//! See `notes/gfxstream-bridge.md` "WSI plan: custom Vulkan WSI" for
//! the surrounding design. The short version:
//!
//! The chroot's custom Vulkan WSI allocates swapchain images as
//! gfxstream ColorBuffers via the kumquat protocol's
//! `RESOURCE_CREATE_BLOB`. The resulting `u32 resource_id` names the
//! same object on three sides:
//!
//!   1. chroot resource handle (`VirtGpuKumquatResource::mResourceHandle`),
//!   2. kumquat server's `resources` map key,
//!   3. host gfxstream `FrameBuffer::m_colorbuffers` key (gfxstream's
//!      `HandleType` is `uint32_t`).
//!
//! On `vkQueuePresentKHR` the chroot binds the colorbuffer_id to a
//! `wl_buffer` via this protocol. The compositor (same process as the
//! gfxstream host renderer) calls `tawc_gfxstream_lookup_ahb`
//! (defined in our gfxstream patch `02-tawc-lookup-ahb.patch`) to
//! resolve the id to the underlying `AHardwareBuffer*`, then wraps it
//! in a `WleglBufferData` so the existing AHB-to-GLES-texture import
//! path picks it up exactly as it does for the libhybris/android_wlegl
//! backend.
//!
//! No fds are exchanged. The AHB lives in the compositor's address
//! space already; swapchain images are `DEVICE_LOCAL` so the chroot
//! never asks for CPU access.

#[cfg(target_os = "android")]
unsafe extern "C" {
    /// Look up a gfxstream ColorBuffer by handle and return its
    /// backing `AHardwareBuffer*` with refcount += 1 (caller must
    /// release). Returns null on miss.
    ///
    /// Defined in `deps/gfxstream/host/frame_buffer.cpp` by our patch
    /// `02-tawc-lookup-ahb.patch`. Lives inside `libgfxstream_backend.so`
    /// which the compositor pulls in transitively via `kumquat_virtio`'s
    /// `gfxstream` feature.
    fn tawc_gfxstream_lookup_ahb(handle: u32) -> *mut ndk_sys::AHardwareBuffer;
}

#[cfg(not(target_os = "android"))]
unsafe fn tawc_gfxstream_lookup_ahb(_handle: u32) -> *mut ndk_sys::AHardwareBuffer {
    // Host-side test builds don't link libgfxstream_backend.so. The
    // dispatch impl below would never be exercised on a host build
    // (the `tawc_gfxstream` global is only useful with a real
    // gfxstream backend behind it), but the linker still needs a
    // definition.
    std::ptr::null_mut()
}

use log::{error, info};
use smithay::reexports::wayland_server::{
    Client, DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource,
};

use crate::compositor::TawcState;
use crate::protocol::tawc_gfxstream::server::{
    tawc_gfxstream::{self, TawcGfxstream},
};
use crate::wlegl::WleglBufferData;

// ---------------------------------------------------------------------------
// Global dispatch
// ---------------------------------------------------------------------------

impl GlobalDispatch<TawcGfxstream, ()> for TawcState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &Client,
        resource: New<TawcGfxstream>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
        info!("gfxstream_present: client bound tawc_gfxstream");
    }
}

impl Dispatch<TawcGfxstream, ()> for TawcState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        resource: &TawcGfxstream,
        request: tawc_gfxstream::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            tawc_gfxstream::Request::CreateBuffer {
                id,
                colorbuffer_id,
                width,
                height,
                format,
            } => {
                // SAFETY: tawc_gfxstream_lookup_ahb is a plain C entry
                // point inside libgfxstream_backend.so. Returns a
                // refcounted AHB on success or null on miss.
                let ahb = unsafe { tawc_gfxstream_lookup_ahb(colorbuffer_id) };
                if ahb.is_null() {
                    error!(
                        "gfxstream_present: unknown colorbuffer_id={}; killing client",
                        colorbuffer_id,
                    );
                    resource.post_error(
                        tawc_gfxstream::Error::UnknownColorbuffer,
                        format!("colorbuffer_id={} not in FrameBuffer", colorbuffer_id),
                    );
                    return;
                }
                // Adopt the AHB ref into a WleglBufferData; its Drop
                // releases on wl_buffer destroy. The wl_buffer's
                // user-data is what the renderer's existing
                // `wlegl::wlegl_buffer_data` lookup pulls out, so
                // reusing the same type means render.rs doesn't need
                // a per-protocol switch.
                let data = WleglBufferData::from_ahb(ahb, width, height);
                let buffer = data_init.init(id, data);
                info!(
                    "gfxstream_present: bound colorbuffer_id={} as wl_buffer id={} {}x{} fmt=0x{:08x}",
                    colorbuffer_id,
                    buffer.id().protocol_id(),
                    width,
                    height,
                    format,
                );
            }
            tawc_gfxstream::Request::Destroy => {
                // Destructor; live wl_buffers stay alive until they're
                // destroyed independently. Smithay tears down the
                // resource for us.
            }
        }
    }
}

// `Dispatch<WlBuffer, WleglBufferData>` is already provided by
// `crate::wlegl` for the libhybris path — we share it. The renderer
// looks up `WleglBufferData` via the wl_buffer's user-data slot
// regardless of which protocol minted the buffer, which is the whole
// point of pushing both paths through the same userdata type.
