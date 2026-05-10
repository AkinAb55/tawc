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
//! Built for both Android target arches. `libgfxstream_backend.so` is
//! cross-built per-ABI (`scripts/build-gfxstream-backend.sh --abi=both`)
//! and staged into `app/src/main/jniLibs/<abi>/`. The kumquat_virtio
//! dep itself is `target_os = "android"`-gated (compositor/Cargo.toml)
//! so host-side test builds (target_os = "linux") still compile.

#[cfg(not(target_os = "android"))]
pub fn spawn() {
    // Host-side test builds — no kumquat dep available.
    log::info!("kumquat: skipping spawn — not an Android target build");
}

#[cfg(target_os = "android")]
pub use android::spawn;

#[cfg(target_os = "android")]
mod android {
use kumquat_virtio::kumquat::KumquatBuilder;
use log::{error, info};

/// Host-side socket path the compositor binds. Must live under the
/// `share/` subdir that every install method bind-mounts into the
/// rootfs at `/usr/share/tawc/`. Don't move this without also updating
/// `RootfsEnv.kt`'s `VIRTGPU_KUMQUAT_GPU_SOCKET` value.
const KUMQUAT_SOCKET_PATH: &str = "/data/data/me.phie.tawc/share/kumquat-gpu-0";

/// Spawn the kumquat server on a dedicated thread. The thread blocks
/// in `Kumquat::run()`'s `wait_ctx.wait()` until a client connects,
/// then dispatches commands to the gfxstream renderer
/// (`libgfxstream_backend.so`).
///
/// Errors at build time are logged and swallowed: the compositor
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
    if let Some(parent) = std::path::Path::new(KUMQUAT_SOCKET_PATH).parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            error!("kumquat: mkdir {} failed: {}", parent.display(), e);
            return;
        }
    }
    // KumquatBuilder::build() already removes a stale socket file
    // before binding, so we don't need to.

    let mut kumquat = match KumquatBuilder::new()
        .set_capset_names("gfxstream-vulkan".into())
        .set_renderer_features(String::new())
        .set_gpu_socket(Some(KUMQUAT_SOCKET_PATH.into()))
        .build()
    {
        Ok(k) => k,
        Err(e) => {
            error!("kumquat: build failed: {:?}", e);
            return;
        }
    };

    info!("kumquat: listening on {}", KUMQUAT_SOCKET_PATH);
    loop {
        if let Err(e) = kumquat.run() {
            // The fork already drops per-client errors inside
            // Kumquat::run (rutabaga-patches/02-keep-server-alive-on-
            // client-error). Anything reaching here is the wait_ctx
            // itself failing, which is unrecoverable — log and exit
            // the thread.
            error!("kumquat: wait_ctx failed: {:?}; thread exiting", e);
            return;
        }
    }
}
} // mod android
