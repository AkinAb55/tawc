package me.phie.tawc.install

import android.os.Build

/**
 * Bind-mount setup / teardown for a chroot installation. Mirrors the
 * mount section of `client/arch-chroot-run` and `arch-chroot-destroy`.
 *
 * Magisk's `su` runs each invocation in a private mount namespace, so
 * any bind mounts done in one `su` call are torn down when that call
 * exits. The legacy `arch-chroot-run` accommodates this by combining
 * mount setup and the chroot exec into a single `su -c "..."` shell
 * — the mounts live exactly as long as that shell's namespace.
 *
 * We follow the same pattern: this object exposes [mountScript] (a shell
 * snippet that performs the bind mounts) which callers concatenate with
 * the work they want done, then run via one `Su.run`. There is no
 * persistent "is mounted" state because there can't be — the mounts are
 * per-su-invocation.
 *
 * [unmount] still exists for the case where a previous run somehow
 * leaked mounts into the global namespace (e.g. via `su --mount-master`)
 * before we delete the rootfs.
 *
 * This is the only piece that knows about the difference between an
 * emulator and a real device — emulators skip the libhybris-only mounts
 * (`vendor`, `system`, `system_ext`, `apex`, `binderfs`, `linkerconfig`)
 * because libhybris doesn't run on the gfxstream GPU stack.
 */
object ChrootMounter {

    /** True if we look like we're running inside an Android emulator. */
    private val isEmulator: Boolean by lazy {
        Build.HARDWARE.contains("ranchu") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.PRODUCT.startsWith("sdk_") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK")
    }

    /**
     * Shell snippet that performs all bind mounts for the chroot rooted
     * at [rootfs]. Aborts (with `exit 1`) if `$rootfs/usr` is missing.
     * The snippet assumes `set -eu` has been prepended (Su.run does that)
     * and is safe to source from a longer wrapper script — it leaves
     * `ROOTFS`, `MOUNTS`, `is_mounted`, and `mount_if_needed` defined for
     * the rest of the script to use.
     */
    fun mountScript(rootfs: String): String {
        val emulator = isEmulator
        val tawcData = "/data/data/me.phie.tawc"

        val sb = StringBuilder()
        sb.appendLine("ROOTFS='$rootfs'")
        sb.appendLine(
            """
            if [ ! -d "${'$'}ROOTFS/usr" ]; then
                echo "ERROR: rootfs not found: ${'$'}ROOTFS" >&2
                exit 1
            fi

            mkdir -p "${'$'}ROOTFS/dev" "${'$'}ROOTFS/dev/pts" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys"

            MOUNTS=${'$'}(cat /proc/mounts)
            is_mounted() { echo "${'$'}MOUNTS" | grep -q " ${'$'}1 "; }
            mount_if_needed() {
                src="${'$'}1"; dst="${'$'}2"
                is_mounted "${'$'}dst" || mount -o bind,rslave "${'$'}src" "${'$'}dst"
            }

            mount_if_needed /dev      "${'$'}ROOTFS/dev"
            mount_if_needed /dev/pts  "${'$'}ROOTFS/dev/pts"
            mount_if_needed /proc     "${'$'}ROOTFS/proc"
            mount_if_needed /sys      "${'$'}ROOTFS/sys"
            """.trimIndent()
        )

        if (!emulator) {
            sb.appendLine(
                """
                mkdir -p "${'$'}ROOTFS/dev/binderfs" "${'$'}ROOTFS/vendor" \
                         "${'$'}ROOTFS/system" "${'$'}ROOTFS/system_ext" \
                         "${'$'}ROOTFS/linkerconfig" "${'$'}ROOTFS/apex"

                mount_if_needed /dev/binderfs "${'$'}ROOTFS/dev/binderfs"
                mount_if_needed /vendor       "${'$'}ROOTFS/vendor"
                mount_if_needed /system       "${'$'}ROOTFS/system"
                mount_if_needed /system_ext   "${'$'}ROOTFS/system_ext"
                mount_if_needed /linkerconfig "${'$'}ROOTFS/linkerconfig"

                # /apex is recursive bind: each APEX is its own loop mount.
                is_mounted "${'$'}ROOTFS/apex" || mount -o rbind,rslave /apex "${'$'}ROOTFS/apex"

                # Belt-and-braces: com.android.runtime sometimes doesn't
                # propagate (private mounts). libhybris needs bionic libs.
                if [ -d /apex/com.android.runtime/lib64 ] && \
                   [ ! -d "${'$'}ROOTFS/apex/com.android.runtime/lib64" ]; then
                    mount --rbind /apex/com.android.runtime "${'$'}ROOTFS/apex/com.android.runtime"
                fi

                # Some vendor libbinder.so is older than system's; overlay system's.
                if [ -f /system/lib64/libbinder.so ] && \
                   [ -f "${'$'}ROOTFS/vendor/lib64/libbinder.so" ]; then
                    is_mounted "${'$'}ROOTFS/vendor/lib64/libbinder.so" || \
                        mount --bind /system/lib64/libbinder.so "${'$'}ROOTFS/vendor/lib64/libbinder.so" 2>/dev/null || true
                fi

                # SELinux transition for chroot-client memfds (no-op on
                # emulators where magiskpolicy isn't installed; setenforce 0
                # is used there instead, see notes/emulator.md).
                magiskpolicy --live "type_transition magisk tmpfs file appdomain_tmpfs" 2>/dev/null || true
                """.trimIndent()
            )
        }

        sb.appendLine(
            """
            # Mount the compositor's app data dir at the same path inside
            # the chroot so /data/data/me.phie.tawc/wayland-0 (the Wayland
            # socket) is reachable. The chroot's profile.d/01-tawc.sh
            # symlinks it to /tmp/wayland-0.
            #
            # Yes, this creates a recursion (the rootfs contains itself
            # via .../installations/<id>/rootfs/data/data/...). It's
            # benign because no tool recursively walks through it.
            if [ -d "$tawcData" ]; then
                mkdir -p "${'$'}ROOTFS$tawcData"
                mount_if_needed "$tawcData" "${'$'}ROOTFS$tawcData"
            fi
            """.trimIndent()
        )
        // Refresh profile.d/01-tawc.sh on every chroot entry so changes to
        // the Wayland env (LD_LIBRARY_PATH, HYBRIS_EGLPLATFORM, …) take
        // effect without reinstalling. Cheap (<1ms) and matches the legacy
        // arch-chroot-run behaviour. 00-path.sh is install-time-only because
        // it's identical for every install.
        sb.appendLine(
            """
            mkdir -p "${'$'}ROOTFS/etc/profile.d"
            cat > "${'$'}ROOTFS/etc/profile.d/01-tawc.sh" <<'TAWC_PROF_EOF'
            # tawc Wayland compositor environment (refreshed each chroot entry)
            export WAYLAND_DISPLAY=wayland-0
            export XDG_RUNTIME_DIR=/tmp
            export LD_LIBRARY_PATH=/tmp/gl-shims:/usr/local/lib
            export HYBRIS_EGLPLATFORM=wayland
            ln -sf /data/data/me.phie.tawc/wayland-0 /tmp/wayland-0 2>/dev/null
            TAWC_PROF_EOF
            chmod 644 "${'$'}ROOTFS/etc/profile.d/01-tawc.sh"
            """.trimIndent()
        )
        return sb.toString()
    }

    /**
     * Defensive cleanup: unmount anything that's currently bind-mounted
     * under [rootfs] in the global namespace and verify the dir is mount-
     * free. Called before deleting the rootfs so an `rm -rf` can never
     * traverse a live bind mount into real system files (deleting the
     * host's /dev/socket etc.).
     *
     * The path Kotlin gives us (`/data/user/0/<pkg>/...`) is a symlink
     * target; /proc/mounts reports the canonical `/data/data/<pkg>/...`
     * form. We `realpath` once before matching so both forms work, and
     * use a strict prefix check (not regex) so paths with dots don't
     * over-match.
     */
    fun unmount(rootfs: String): Su.Result {
        val script = """
            CANON=${'$'}(realpath '$rootfs' 2>/dev/null || echo '$rootfs')
            list_mounts() {
                awk -v r="${'$'}CANON" '${'$'}2 == r || index(${'$'}2, r"/") == 1 {print ${'$'}2}' /proc/mounts
            }
            for m in ${'$'}(list_mounts | sort -r); do
                umount "${'$'}m" 2>/dev/null || umount -l "${'$'}m" 2>/dev/null || true
            done
            remaining=${'$'}(list_mounts | wc -l)
            if [ "${'$'}remaining" -gt 0 ]; then
                echo "ERROR: ${'$'}remaining mount(s) still active under ${'$'}CANON:" >&2
                list_mounts >&2
                exit 1
            fi
            echo OK
        """.trimIndent()
        return Su.run(script)
    }
}
