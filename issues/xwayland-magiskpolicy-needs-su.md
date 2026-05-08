# Xwayland startup uses `su` to grant SELinux exec, breaking the rootless story

`CompositorService.applyXwaylandExecPolicy()` (`app/src/main/java/me/phie/tawc/compositor/CompositorService.kt:108, 222-234`) runs on every compositor cold-start and shells out to Magisk's `su` to apply

```
magiskpolicy --live "allow untrusted_app app_data_file file execute_no_trans"
```

The reason: modern Android (10+) denies `execute_no_trans` from the `untrusted_app` SELinux domain onto `app_data_file`. We extract `Xwayland` and `xkbcomp` from `assets/xwayland/<abi>.tar` into `<filesDir>/xwayland/bin/`, which inherits `app_data_file`, so `Command::new("Xwayland")` returns EACCES. Granting that one rule via `magiskpolicy --live` lifts the restriction.

This is the only `su` consumer left on the tawcroot run path — every other Su user in the codebase is properly method-gated (see `Installer.kt:101`, `InstallationStore.kt:92`, `InstallationMethod.defaultForHost`, `ProcessScanner`, etc.). The xclock test (`tests/integration/tests/apps.rs:315 test_xwayland_xclock_renders_via_shm`) is what surfaces it: it's the only test that needs Xwayland, so it's the only place the Magisk "granted superuser" toast pops up mid-run.

Two real costs:
1. **Rootless devices can't run X11 clients.** `Su.rootAvailable()` returns false, the rule isn't applied, and the Xwayland exec hits EACCES. Wayland clients keep working, X11 clients silently fall over.
2. **Even on rooted devices, `su` cuts against the rest of the project.** The whole point of the broker / tawcroot redesign is "no `su` on the run path"; this single call walks that back for X11.

## Proposal

Ship Xwayland's exec'ables and runtime libraries as `jniLibs/<abi>/lib*.so` instead of bundling everything in an extracted asset. Files in `nativeLibraryDir` get the `apk_data_file` SELinux type, which **is** exec'able from `untrusted_app` — same trick `libproot.so` already uses (`AndroidManifest.xml:19-20`, `build.gradle.kts:222-252`).

### Layout split

| Today                                                | Proposed                                                                |
| ---------------------------------------------------- | ----------------------------------------------------------------------- |
| `assets/xwayland/<abi>.tar` (binaries + libs + share) | `jniLibs/<abi>/libxwayland.so` (was `bin/Xwayland`)                     |
|                                                      | `jniLibs/<abi>/libxkbcomp.so` (was `bin/xkbcomp`)                       |
|                                                      | `jniLibs/<abi>/lib*.so` (was `lib/lib*.so`, dereferenced)               |
|                                                      | `assets/xwayland-share.tar` (was `share/X11/`, `share/xkeyboard-config-2/`) |

Binaries and `.so` deps are exec'able / dlopen'able directly from `nativeLibraryDir` — no extraction. Only the small XKB data tree (~few MB of text files) still needs runtime extraction, since Xwayland reads it via `fopen` and the files reference each other by relative paths inside the tree (can't be flattened to fake `lib*.so` names).

### Implementation steps

1. **`app/build.gradle.kts`** — replace `packXwayland` with two tasks:
   - `stageXwaylandJniLibs`: copy `build/xwayland-aarch64/install/bin/{Xwayland,xkbcomp}` → `app/src/main/jniLibs/arm64-v8a/lib{xwayland,xkbcomp}.so`, and dereference-copy each `lib/lib*.so` (some are version-suffixed symlinks like `libxcb.so` → `libxcb.so.1.1.0` — jniLibs is flat) into the same dir.
   - `packXwaylandShare`: tar `share/X11` + `share/xkeyboard-config-2` (the two dirs Xwayland's `-Dxkb_dir` / `-Dxkb_bin_dir` baked-in paths point at) into `assets/xwayland-share.tar`.
2. **`CompositorService.ensureXwaylandExtracted`** — extract only `assets/xwayland-share.tar` into `<filesDir>/xwayland/share/`, then create symlinks:
   - `<filesDir>/xwayland/bin/Xwayland` → `<applicationInfo.nativeLibraryDir>/libxwayland.so`
   - `<filesDir>/xwayland/bin/xkbcomp` → `<applicationInfo.nativeLibraryDir>/libxkbcomp.so`
   The compositor's existing `PATH=$XWL_INSTALL_DIR/bin:$PATH` lookup finds the symlinks, `execve` follows to the real file, SELinux checks the target's domain → allowed.
3. **`compositor/src/xwayland.rs`** — change `LD_LIBRARY_PATH` from `<XWL_INSTALL_DIR>/lib` to `<nativeLibraryDir>`. Pass the latter from Kotlin via `Os.setenv("TAWC_NATIVE_LIB_DIR", ...)` (or equivalent) before `nativeStartCompositor`.
4. **Delete `applyXwaylandExecPolicy()`** (`CompositorService.kt:108, 222-234`) and its `import me.phie.tawc.install.Su` if no other reference remains in the file. Update `notes/xwayland.md` and the docstring on `Su.run`.

### Caveats / things to verify

- **APK packager strips `lib*.so` files.** Xwayland is `ELF pie executable, not stripped` today (`file build/xwayland-aarch64/install/bin/Xwayland`). libproot ships stripped (precedent works). Strip should be fine for a binary too, but worth confirming the result still execs.
- **`extractNativeLibs="true"`** is already set (`AndroidManifest.xml:34`) and `useLegacyPackaging=true` (`build.gradle.kts:103-105`), so jniLibs become real on-disk files — required since exec doesn't work on the in-APK path. No manifest change needed.
- **APK size**: roughly neutral. Same bytes, packaged differently. Slight win from skipping `tar` overhead.
- **Aarch64-only**: Xwayland is already gated to `arm64-v8a` (build.gradle.kts:284, 339). No change.
- **Auxiliary binaries** (`bdftruncate`, `cvt`, `ucs2any`) in the install tree are dev/font tools, never invoked at runtime. Excluded today via the explicit `tar` include list, exclude them the same way from the new staging task.

## Verification

- `apps::test_xwayland_xclock_renders_via_shm` passes on a **rootless** device/emulator (no `su` available) — that's the canary the test was missing.
- The same test passes on a rooted device **with no Magisk superuser toast** during the run.
- `Su.rootAvailable()` is no longer reachable from the compositor cold-start path (grep in `app/src/main/java/me/phie/tawc/compositor/`).
- Xwayland still spawns successfully and X11 clients still render via SHM (and via TAWC-DRI / AHB on phase-2-step-3 paths if enabled).

## Notes

- This is the only remaining run-path Su consumer. Once it's gone, the only remaining Su sites are install/uninstall fallbacks for the chroot method (and tawcroot's last-resort `find -delete` retry on uninstall) — all method-gated and never hit on the rootless path.
- `notes/xwayland.md` "Phase 1" text and `compositor/src/xwayland.rs` header refer to the current "extract everything to filesDir" model — update both to describe the jniLibs split.
