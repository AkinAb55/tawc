# gfxstream Bridge Remaining Work

The physical-device Vulkan path works: `gfxstream::test_vkcube_renders_via_ahb`
passes through the custom WSI and presents AHBs to the compositor. Remaining
work is about GL/GLES routing and x86_64 AVD validation.

## GL/GLES Through Custom WSI

Zink translates GL to Vulkan. For GL apps to use the gfxstream bridge, Mesa's
EGL/GLX path must present through Vulkan WSI (`VkSurfaceKHR`,
`VkSwapchainKHR`, `vkQueuePresentKHR`) instead of allocating wl_buffers through
GBM.

Candidate paths:

1. **EGL over Vulkan WSI.** Find a Mesa configuration where EGL is layered on
   top of Vulkan with Zink as the GL backend. Then `eglSwapBuffers` invokes
   `vkQueuePresentKHR`, and GL apps ride the existing custom WSI. This needs
   Mesa build/config investigation and likely `RootfsEnv.kt` env changes.
2. **Small EGL shim.** Implement enough EGL Wayland surface support to back
   surfaces onto the custom Vulkan WSI. Heavier, but controlled by tawc.

Plain `MESA_LOADER_DRIVER_OVERRIDE=zink` is not enough today because Mesa's EGL
Wayland path still tries to open `/dev/dri/cardN`. Until this lands, the
gfxstream integration tests for GTK, Firefox, SuperTuxKart, weston-simple-egl,
and eglinfo stay red.

## x86_64 AVD Validation

The build pipeline is symmetric with aarch64 and compiles cleanly. On the AVD,
the chroot connects and `vulkaninfo` enumerates the device, but two upstream
failures were observed in the chain:

```text
vulkan.ranchu.so -> AVD-host gfxstream -> AMD radv
```

1. **Host-visible `vkAllocateMemory` rejected.** With `ExternalBlob`, the host
   path tags host-visible allocations with AHB export info. `vulkan.ranchu`
   returns `VK_ERROR_OUT_OF_HOST_MEMORY`. `SystemBlob:enabled` may route through
   `VK_EXT_external_memory_host`, but that puts physical Adreno on an untested
   path and does not solve the next blocker.
2. **Imported AHB texture samples as zero.** Even when allocation is unblocked,
   the compositor imports the colorbuffer and frames advance, but shader probes
   read `(0,0,0,0)` from the imported texture. The missing write/sync point was
   not pinned down.

This likely needs gfxstream-internal changes or a workaround that bypasses the
chained host path, such as CPU readback via gfxstream's `readColorBuffer`.

## Deferred Cleanup

The kumquat `RESOURCE_CREATE_BLOB` response always ships an fd. For
DEVICE_LOCAL allocations such as swapchain images, the chroot never maps that
fd. A future cleanup can send a long-lived `/dev/null` sentinel fd for those
allocations, or add an explicit no-fd response variant to kumquat.
