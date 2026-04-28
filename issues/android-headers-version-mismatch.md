# android-headers version is faked, causing per-occurrence patch creep

We clone `Halium/android-headers` at branch `halium-11.0` and then sed-bump `ANDROID_VERSION_MAJOR` from `11` to `16` in `android-version.h`. The actual header content stays at the halium-11 era; only the version macro lies. This dual-purpose hack is doing two unrelated jobs at once and is starting to leak.

## The two jobs the sed-bump is doing

1. **Flip on libhybris's feature-conditional code paths.** Source files use `#if ANDROID_VERSION_MAJOR >= N` to opt into newer Android-era APIs (gralloc, native_window, sync). Bumping to 16 turns these on. Most cutoffs cluster at 4.x / 5 / 6 / 7 / 8 / 9 / 10 — almost nothing post-10 — so any `>= 10` value would suffice; 16 is just chosen to match "current".

2. **Pretend to be on halium-12+ headers** in a way that doesn't match the file content. This is the part that bites us.

## How it bites: libsync as the canonical example

Upstream libhybris commit `b5301ad` ("hybris: Add support for Android 12 and 13") narrowed a libsync guard from `>= 10` to `>= 10 && < 12`:

```c
#if (ANDROID_VERSION_MAJOR >= 10) && (ANDROID_VERSION_MAJOR < 12)
#include <linux/sync_file.h>
struct sync_file_info* sync_file_info(int32_t fd);
static inline struct sync_fence_info* sync_get_fence_info(...) { ... }
void sync_file_info_free(struct sync_file_info* info);
#endif
```

The intent was: at v12+, real halium-12+ headers expose those declarations themselves (via bionic's `<sync/sync.h>`), so libhybris's local forward decls become duplicates. Disabling them at v12+ lets the upstream headers' versions win.

In our setup, we tell libhybris "we're on v16" but the headers are halium-11 and *do not* expose those declarations. With the unmodified guard, libhybris's declarations are skipped at v16 and the call sites (in `>= 8` branches further down — `sync_get_fence_info` at line 311/363, `sync_file_info_free` at line 403) reference undeclared symbols → build break.

Workaround we currently apply: drop the `< 12` upper bound, both in our libhybris fork's source (committed via the in-progress libhybris-aarch64 cross-build) and historically in the chroot build script via runtime sed.

This pattern is the canonical shape of the leak: **libhybris was patched to defer to v12+ headers' declarations, but our headers don't actually have those declarations.** The same shape can recur anywhere libhybris adopts an "upstream now provides this" posture and we then have to undo it.

## Cleanest fixes, in preference order

1. **Switch to a current `halium-1X.0` android-headers branch.** Pull from a branch whose content actually matches the version we claim (the latest Halium branch is what we'd want). Drop the sed. Restore the `< 12` guard upstream-clean. The `b5301ad`-style "defer to upstream headers" patches in libhybris would then do their real job. This is the least surprising long-term position.
   - Risk: need to audit what changes in halium-1X vs halium-11. The Halium project is the practical owner of this header set; their branches often have device-specific tweaks. Verify on our actual target devices (Pixel 4a, OnePlus 9) that the newer headers don't break runtime calls into the vendor blobs they support.

2. **Stay on halium-11.0 honestly.** Drop the sed entirely; let `ANDROID_VERSION_MAJOR` be 11. Drop the libsync workaround (the `>= 10 && < 12` guard would be true at v11). Lose access to libhybris feature paths gated on `>= 12 / 13`, if any. Our minSdk is 29 (Android 10), and the `>=` cutoffs above 10 are sparse, so the practical cost may be small.
   - Risk: silent loss of v11+ code paths that we currently rely on. Audit `grep -rn 'ANDROID_VERSION_MAJOR' libhybris/hybris/` for cutoffs > 11; verify each is either unused or has an acceptable v11 alternative.

3. **Status quo.** Lie about version, fix collisions one at a time as they surface. Already proven to work and to leak — see libsync. Each new patch is small, but the libhybris fork accumulates "undo upstream's defer-to-headers" workarounds that have to live in our fork forever and get re-justified each rebase.

## Where this currently lives

- `android-headers/` (host-side, gitignored): cloned `halium-11.0`, sed-bumped to 16. See `client/build-libhybris-aarch64`.
- `client/build-libhybris-chroot.sh:51-52`: same sed-bump, applied at chroot build time.
- `libhybris/hybris/libsync/sync.c:26`: the `>= 10 && < 12` guard, edited locally to drop `< 12` (queued to land as a real fork commit).
- `client/build-libhybris-chroot.sh:60-63`: the historical chroot-time sed for the same libsync issue.
- `notes/building.md` "Why we sed-bump android-headers" and "libsync version-guard fix" notes.

## Suggested staging when this is picked up

1. Pick a target halium branch (most likely the latest available, e.g. `halium-13.0` or whatever exists at the time).
2. Diff `halium-11.0` against the target branch — both content and `android-version.h`. Identify any of our existing source patches that become unnecessary (i.e. would fall out of "use the right headers").
3. Update `client/build-libhybris-aarch64` and `client/build-libhybris-chroot.sh` (if still present) to clone the target branch with no sed-bump.
4. Restore the `< 12` upper bound in `libhybris/hybris/libsync/sync.c` — and probably others; audit `git log --grep='Android 12'` in the libhybris fork for similar guards.
5. Test build + runtime on both `device` and `emulator` targets.
6. Land as a cluster: one libhybris fork commit (or a reverted set), one tawc commit changing the clone branch and dropping the seds.

## Why we are not doing this now

- The current state works. Phase 2 of `issues/ship-libhybris-in-apk.md` (Gradle wiring + APK asset bundling) does not depend on this being clean.
- A header-branch swap touches the runtime calling convention into vendor blobs. Worth doing at a moment when we can spend a debug cycle on each target device, not bundled into another change.
- The "real" answer (option 1) requires picking the right Halium branch and validating against vendor blobs we depend on. That's a focused investigation, not a rushed cleanup.
