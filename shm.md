# SHM Buffer Support: SELinux Issue and Solutions

## The Problem

Wayland's `wl_shm` protocol works by having the **client** create a shared memory region
(via `memfd_create()` or a tmpfile), then pass the file descriptor to the compositor via
`wl_shm.create_pool(fd, size)`. The compositor mmaps the fd to access pixel data.

On Android, this fails because of SELinux. The compositor runs as an `untrusted_app`, and
the chroot client's memfd gets the SELinux label `u:object_r:tmpfs:s0`. Android's SELinux
policy denies `untrusted_app` from reading/writing `tmpfs` files from other contexts:

```
avc: denied { read write } for comm="me.phie.tawc"
  path=/memfd:weston-shared (deleted)
  scontext=u:r:untrusted_app:s0:c65,c257,c512,c768
  tcontext=u:object_r:tmpfs:s0
  tclass=file permissive=0
```

### How It Breaks

The failure is **silent and catastrophic**. When smithay's SHM handler tries to mmap the
received fd and fails, it posts a Wayland protocol error (`InvalidFd`). But the real damage
is that the fd was already consumed from the socket's SCM_RIGHTS ancillary data. The
wayland-backend protocol parser gets out of sync, and **all subsequent messages from that
client are silently dropped**. The client appears to hang, and the compositor never processes
any further requests (surface creation, commits, etc.) from that connection.

This means even if a client primarily uses EGL/AHB for rendering, if it also binds `wl_shm`
and creates an SHM pool (e.g., for cursor themes), the entire connection is broken.

### Symptoms

- `weston-simple-egl` or `weston-simple-shm` connects but hangs after initial setup
- Compositor logs show "New Wayland client connected" but never "New toplevel surface"
- The custom test-wayland-client (which doesn't use wl_shm) works fine
- `dmesg | grep avc` shows the denial above

## Current Workaround

Set `untrusted_app` to SELinux permissive mode (requires root, persists until reboot):

```bash
su -c 'supolicy --live "permissive untrusted_app"'
```

This must be run **before** any SHM client connects. It makes ALL untrusted apps permissive,
which is a significant security relaxation. Fine for development, not suitable for production.

### Testing Commands

After launching the compositor app, test SHM:
```bash
adb shell "su -c 'supolicy --live \"permissive untrusted_app\" && \
  chroot /data/local/arch-chroot /bin/bash -lc \
  \"WAYLAND_DISPLAY=/data/data/me.phie.tawc/wayland-0 weston-simple-shm\"'"
```

Test EGL (needs libhybris + WSI layer):
```bash
adb shell "su -c 'supolicy --live \"permissive untrusted_app\" && \
  chroot /data/local/arch-chroot /bin/bash -lc \
  \"HYBRIS_PATCH_TLS=1 \
  WAYLAND_DISPLAY=/data/data/me.phie.tawc/wayland-0 \
  LD_LIBRARY_PATH=/tmp/tawc-wsi:\\\$LD_LIBRARY_PATH \
  weston-simple-egl\"'"
```

Note: `weston-simple-egl` doesn't need the SELinux fix if no SHM clients are running
(it uses AHB, not SHM). But it WILL hang if it tries to load cursor themes via SHM
internally -- weston-simple-egl currently avoids this because no cursor support is active.

## Proper Solutions (Not Yet Implemented)

### Option 1: ASharedMemory (ashmem) -- Recommended

Android's native shared memory mechanism. `untrusted_app` CAN mmap ashmem fds from other
processes -- this is how Binder shares large data and is explicitly allowed by SELinux policy.

**Approach:** LD_PRELOAD a small shim in the chroot that intercepts `memfd_create()` and
replaces it with `ASharedMemory_create()` (from libnativewindow or direct ioctl on
`/dev/ashmem`). libwayland-client internally calls `memfd_create()` to create SHM pools,
so this transparent replacement makes SHM work without modifying any client code.

```c
// Conceptual shim (untested)
#include <dlfcn.h>
int memfd_create(const char *name, unsigned int flags) {
    // Use Android's ashmem instead
    int fd = open("/dev/ashmem", O_RDWR);
    ioctl(fd, ASHMEM_SET_NAME, name);
    return fd;
}
```

The ashmem fd needs to be sized via `ASHMEM_SET_SIZE` ioctl before the first mmap. Since
libwayland does `ftruncate()` to set the size, the shim would also need to intercept
`ftruncate()` on ashmem fds and translate to `ASHMEM_SET_SIZE`.

**Pros:** No SELinux changes, no root needed for the workaround itself, transparent to all
Wayland clients.
**Cons:** Requires building and deploying the shim, ashmem is deprecated in favor of memfd
on newer Android versions (but still works).

### Option 2: Compositor-Created Shared Memory

Instead of the client creating the memfd, have the compositor create it and send the fd
to the client. This requires a custom Wayland protocol extension (not compatible with
standard `wl_shm`).

**Pros:** Compositor owns the fd so SELinux is happy.
**Cons:** Standard Wayland clients won't work without modification. Only viable for our
own test clients.

### Option 3: File-Backed SHM in App Directory

Create shared memory files in the compositor's data directory
(`/data/data/me.phie.tawc/shm/`) instead of using memfd. The compositor owns this
directory and can mmap any file in it.

Would require intercepting `memfd_create()` in the client and creating files in the
compositor's directory instead. Similar to Option 1 but uses the filesystem instead of
ashmem.

**Pros:** No SELinux changes needed.
**Cons:** Requires the client to know the compositor's data directory path. Disk-backed
(not purely in-memory), though the kernel may keep it in page cache.

### Option 4: Targeted SELinux Rule

Instead of making all `untrusted_app` permissive, add a narrow rule:

```bash
su -c 'supolicy --live "allow untrusted_app tmpfs file { read write open getattr map }"'
```

In testing, `supolicy --live` with specific rules didn't fully work (denials continued with
different permissions). May need more investigation or a custom SELinux policy module.

**Pros:** Less broad than full permissive.
**Cons:** Still requires root, `supolicy` rule syntax may need refinement.

## Architecture Notes

### How Smithay Handles wl_shm

1. Client sends `wl_shm.create_pool(fd, size)` -- fd is a memfd, passed via SCM_RIGHTS
2. Smithay handler calls `mmap(fd, size, PROT_READ|PROT_WRITE, MAP_SHARED)` -- **this is
   where SELinux blocks it**
3. If mmap fails, smithay posts `InvalidFd` error, which kills the client
4. If mmap succeeds, a `Pool` object wraps the mapping
5. Client creates `wl_buffer` from pool (offset, width, height, stride, format)
6. On commit, compositor calls `import_shm_buffer()` which reads pixel data from the
   mmap'd region and uploads to a GL texture

### Compositor SHM Rendering Path

SHM textures are rendered with a magenta tint shader to visually distinguish them from
AHB (zero-copy GPU) surfaces. The shader is compiled once at startup:

```glsl
vec3 magenta = vec3(color.r * 1.0 + 0.3, color.g * 0.4, color.b * 1.0 + 0.3);
color.rgb = mix(color.rgb, magenta, magenta_mix);  // magenta_mix = 1.0
```

This tint is intentional for debugging. It should be removed or made optional once the
AHB path is mature enough that SHM fallback is rare.

### Why Standard Wayland Clients Need SHM

Even clients that primarily use EGL for rendering often need `wl_shm` for:
- **Cursor themes:** `wl_cursor_theme_load()` creates SHM pools for cursor images
- **Subsurfaces:** Some toolkits use SHM for popups, tooltips, and other non-GL surfaces
- **Fallback rendering:** When EGL is unavailable, clients fall back to SHM software rendering
- **GTK3/4:** Uses SHM for cursor images and sometimes for small auxiliary surfaces

A compositor that doesn't support `wl_shm` (or where SHM silently breaks) will fail to
work with most real-world Wayland applications beyond simple test programs.
