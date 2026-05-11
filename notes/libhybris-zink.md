# libhybris + Zink backend

**Status:** design only, not implemented. Sibling to (and likely
replacement for) `desktop-gl-dispatch.md`.

A new `GraphicsBackend.LibhybrisZink` that hands the chroot the distro's
own mesa for everything GL-family, configured to use **Zink** as the
Gallium driver, with **libhybris-vulkan as the only Vulkan ICD**. The
existing `Hybris` backend stays untouched as the GLES fast path.

## Why

`Hybris` is GLES-only; desktop-GL apps (kitty, alacritty, supertuxkart,
anything with `#version 140` shaders) fail to render. The `gl-shims/`
trick papers over linkage but can't make a GLES driver run desktop-GL
shaders. `desktop-gl-dispatch.md` proposed a per-context dispatcher in
libhybris's libEGL — ~1k LOC of new C with a long bug tail.

Zink translates desktop GL *and* GLES to Vulkan inside mesa. If we route
that Vulkan back through libhybris-vulkan (which already works
end-to-end including WSI), we get desktop GL with no new C code — only
configuration. The cost is GLES regression: GLES apps go through Zink
instead of straight libhybris GLES, which is single-digit % overhead on
most workloads. That's the whole tradeoff.

## Stack

```
GL/GLES app
  └─ libGL.so.1 / libEGL.so.1 / libGLESv2.so.2  →  distro mesa (libglvnd-fronted)
       └─ Gallium → Zink → SPIR-V
            └─ libvulkan.so.1 (mesa loader)
                 └─ ICD: libhybris's libvulkan.so
                      └─ Android Vulkan → Adreno HW

Native Vulkan app (unchanged)
  └─ libvulkan.so.1 → libhybris's libvulkan.so → ...
```

**No dmabuf anywhere.** Mesa's GL Wayland WSI (which wants dmabuf) is
sidestepped via mesa's **Kopper** path: `eglSwapBuffers` becomes
`vkQueuePresentKHR` on a libhybris-vulkan swapchain, so libhybris's
existing AHB-based WSI does presentation. Same WSI native Vulkan apps
use today, no compositor changes.

## What ships

- **ICD JSON** (~10 lines, new file at
  `app/src/main/assets/libhybris/libhybris_icd.json`):
  ```json
  { "file_format_version": "1.0.0",
    "ICD": { "library_path": "/usr/local/lib/libhybris/libvulkan.so",
             "api_version": "1.3.0" } }
  ```
- Mesa-with-Zink is already a distro package; usually pulled in by the
  GTK/mesa-utils deps `install-test-deps.sh` already installs. Check
  per-distro; add explicit `mesa-vulkan-drivers` (Debian) /
  `vulkan-mesa-layers` (Arch) if missing.

## Code changes

1. `Settings.kt`: add `LibhybrisZink` to `GraphicsBackend`.
2. Broker `GRAPHICS` header parser: accept `libhybris-zink`.
3. `RootfsEnv.kt`: new branch:
   - `LD_LIBRARY_PATH=/usr/local/lib` (drop the `gl-shims/` prefix so
     distro mesa wins for libEGL/libGL/libGLES*).
   - `VK_ICD_FILENAMES=/usr/local/lib/libhybris/libhybris_icd.json`
   - `MESA_LOADER_DRIVER_OVERRIDE=zink`
   - Keep all other libhybris-related env (bionic loader bridge,
     gralloc, etc.).
4. `LibhybrisInstallProvider.kt`: drop `libhybris_icd.json` into the
   staged libhybris tree at install time.

Total: ~30 LOC of Kotlin + one JSON file. No C, no libhybris fork
changes.

## Verification (in order)

1. `vulkaninfo` from a `LibhybrisZink` spawn — reports Adreno via the
   libhybris ICD, same physical device native Vulkan sees.
2. `eglinfo` / `glxinfo -B` — `GL_RENDERER` mentions "Zink" and
   "Adreno". **If it says "llvmpipe", Zink failed to init and mesa
   silently fell back to software — that's the failure mode to watch
   for.**
3. GTK4 demo renders correctly — GLES via Zink.
4. Firefox launches with WebGL — realistic browser path.
5. kitty / alacritty / glxgears — desktop-GL apps the dispatcher plan
   was trying to enable.

A startup probe in the rootfs entrypoint that logs `GL_RENDERER` and
warns if it's `llvmpipe` would catch silent regressions in CI.

## Selection in tests

Per-spawn via the broker `GRAPHICS` header:
`tawc-exec --in-rootfs <id> --graphics libhybris-zink …` or
`RootfsProcess::spawn_with(..., GraphicsBackend::LibhybrisZink)`. A
`libhybris_zink::` integration-test module mirrors `hybris::` /
`gfxstream::` / `cpu_graphics::`, with a representative subset of
tests rather than full reruns.

## Risks

- **Zink Vulkan feature gap.** Zink expects descriptor indexing, sync2,
  and a fairly thick feature set. Adreno's Vulkan covers this on modern
  devices, but if anything's missing Zink refuses to init and mesa
  falls back to llvmpipe — silent regression to software. Mitigated by
  the startup probe above; fixable in libhybris if a needed extension
  isn't exposed.
- **Mesa Kopper version.** Kopper (Vulkan-WSI under EGL for Zink) is
  default on Mesa ≥23 with Zink+Wayland. Older distro mesa may still
  try the dmabuf-flavored Wayland WSI and fail. Pick distros with
  recent mesa (Arch ARM, Debian testing); Debian stable may lag.
- **GLES regression.** Every GLES call now goes through Zink → SPIR-V →
  Vulkan instead of direct libhybris GLES. Single-digit % typical, can
  be more on draw-call-bound workloads. The `Hybris` backend stays
  available for fast-path GLES.
- **`gl-shims/` still ships** as part of the `Hybris` backend's runtime.
  Only the `LibhybrisZink` branch in `RootfsEnv` excludes it from
  `LD_LIBRARY_PATH`. We delete `gl-shims/` outright only if
  `LibhybrisZink` replaces `Hybris` as the default.

## Relationship to `desktop-gl-dispatch.md`

The dispatcher plan exists to add desktop-GL **without** regressing
GLES, by routing per-context. This plan accepts a small GLES
regression in exchange for ~1k LOC of complexity going away.

The question is empirical: how much does Zink-on-libhybris-vulkan
actually cost on GLES workloads? If it's small (<5% on realistic apps),
`LibhybrisZink` replaces both the dispatcher plan and the `gl-shims/`
hack outright, and `desktop-gl-dispatch.md` dies. If GLES regression is
unacceptable, the dispatcher plan comes back. Either way, doing
`LibhybrisZink` first gives us the measurement.
