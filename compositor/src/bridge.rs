//! gfxstream-bridge kumquat server, embedded in the compositor process.
//!
//! Spawns a dedicated thread that runs `kumquat_virtio::kumquat::Kumquat`
//! against an `AF_UNIX SEQPACKET` socket bound at `share/kumquat-gpu-0`
//! inside the app's private data dir. Each rootfs sees that socket at
//! `/usr/share/tawc/kumquat-gpu-0` via the per-method share bind (same
//! mechanism that exposes the wayland socket); chroot-side
//! `gfxstream-vk` finds it through our Mesa patch's
//! `VIRTGPU_KUMQUAT_GPU_SOCKET` env var, set by `RootfsEnv` in the
//! gfxstream-backend branch.
//!
//! Why in-process: kumquat's SCM_RIGHTS fd-passing requires the server
//! and the chroot client to share an SELinux domain. Running kumquat
//! as a thread of the compositor app makes both sides
//! `untrusted_app:s0:c<…>` trivially — no broker daemon spawn, no
//! pidfile, no lifecycle plumbing. See
//! `notes/gfxstream-bridge.md` "Why kumquat must run as untrusted_app".
//!
//! Built only when the compositor crate's `gfxstream` feature is
//! enabled for an Android target. Disabled builds do not depend on
//! `kumquat_virtio` or `libgfxstream_backend.so`.

#[cfg(not(all(target_os = "android", feature = "gfxstream")))]
pub fn spawn() {
    log::info!("kumquat: skipping spawn — gfxstream bridge not built");
}

#[cfg(all(target_os = "android", feature = "gfxstream"))]
pub use android::spawn;

#[cfg(all(target_os = "android", feature = "gfxstream"))]
mod android {
    use kumquat_virtio::kumquat::KumquatBuilder;
    use log::{error, info};

    /// Spawn the kumquat listener on a dedicated thread. The thread
    /// blocks in `Kumquat::run()`'s `wait_ctx.wait()` until a client
    /// connects; the patched rutabaga fork initializes the gfxstream
    /// renderer only after that first accept.
    ///
    /// Listener setup errors are logged and swallowed: the compositor
    /// keeps running so the libhybris path stays usable. A missing
    /// kumquat just means clients launched with the gfxstream backend
    /// will see `connect()` fail with ENOENT and surface that to the user.
    pub fn spawn() {
        std::thread::Builder::new()
            .name("kumquat-server".into())
            .spawn(run)
            .expect("failed to spawn kumquat-server thread");
    }

    fn run() {
        let socket_path = crate::app_paths::get().kumquat_socket_path.clone();
        if let Some(parent) = std::path::Path::new(&socket_path).parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                error!("kumquat: mkdir {} failed: {}", parent.display(), e);
                return;
            }
        }
        // KumquatBuilder::build() removes a stale socket file before
        // binding, but no longer initializes the gfxstream renderer.

        // Force-disable udmabuf backing — auto-enables on kernel >= 6.6 and
        // FATALs when SELinux denies the open on Android. See
        // notes/gfxstream-bridge.md Orientation.
        let mut kumquat = match KumquatBuilder::new()
            .set_capset_names("gfxstream-vulkan".into())
            .set_renderer_features("VulkanAllocateHostVisibleAsUdmabuf:disabled".into())
            .set_gpu_socket(Some(socket_path.clone().into()))
            .build()
        {
            Ok(k) => k,
            Err(e) => {
                error!("kumquat: build failed: {:?}", e);
                return;
            }
        };

        info!("kumquat: listening on {}", socket_path);
        loop {
            if let Err(e) = kumquat.run() {
                // The fork already drops per-client errors inside
                // Kumquat::run (rutabaga-patches/02-keep-server-alive-on-
                // client-error). Anything reaching here is listener,
                // wait_ctx, or first-connect renderer init failure; log
                // and exit the thread while keeping the app alive.
                error!("kumquat: server failed: {:?}; thread exiting", e);
                return;
            }
        }
    }
} // mod android
