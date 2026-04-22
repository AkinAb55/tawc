# Compositor doesn't scale wlegl/Vulkan buffers by output scale

## Problem

The tawc compositor renders wlegl (AHB) and Vulkan buffers 1:1 to physical pixels, ignoring the output scale factor. With a 2x output scale (1080x2400 physical = 540x1200 logical), apps that correctly query `wl_output` and create buffers at the logical size (540x1200) only cover a quarter of the physical display.

## Expected behavior

The compositor should scale wlegl buffers by the output scale factor when compositing, the same way it would for any Wayland buffer. A 540x1200 buffer at scale 1 on a 2x output should be scaled to fill the 1080x2400 physical display.

## Impact

- Vulkan apps (e.g. vkcube) that follow the Wayland spec and use the logical size from `wl_output` render at quarter-size in the top-left corner
- EGL apps may also be affected if they query `wl_output` geometry instead of using the `wl_egl_window` size directly

## Current workaround

Some apps (including vkcube) happen to choose the physical pixel size because the Android driver reports it through other channels, but this is not reliable. The libhybris Vulkan WSI correctly reports `currentExtent = {0xFFFFFFFF, 0xFFFFFFFF}` (undefined per Wayland Vulkan spec), so apps are free to choose any size.

## Fix

The compositor's rendering path for wlegl surfaces needs to apply the output scale transform when compositing. This likely involves the texture/surface geometry calculation in the Smithay-based renderer.
