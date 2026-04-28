package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.compositor.CompositorService
import java.io.File
import java.io.IOException

/**
 * Wire the APK-bundled libhybris tree into a freshly-installed rootfs
 * via symlinks under `<rootfs>/usr/local/lib/`. Each file/dir in the
 * extracted `filesDir/libhybris/lib/` is mirrored as a symlink with
 * an absolute target path; the chroot's bind mount of the app data
 * dir (set up by [ChrootMounter]) makes those targets reachable from
 * inside the chroot.
 *
 * The rootfs is owned by root (the bootstrap was extracted via [Su]),
 * so this runs through `Su.run` with `ln -sfn` rather than direct
 * `Os.symlink` — same pattern as [me.phie.tawc.install.distro.arch.ArchPacmanCommon.configure].
 *
 * This runs in the CONFIGURING stage of [Installer.install], so it
 * only fires on a fresh `(no dir)` install. Re-installs / upgrades of
 * existing chroots are out of scope.
 */
object LibhybrisLinker {
    /**
     * @return true if symlinks were created, false if no libhybris
     *   asset is shipped for this device's ABI (e.g. emulator builds
     *   where libhybris is unsupported).
     */
    fun link(context: Context, rootfsPath: String, log: (String) -> Unit): Boolean {
        if (!CompositorService.ensureLibhybrisExtracted(context)) {
            log("libhybris: no asset for this ABI — skipping rootfs symlinks")
            return false
        }
        // Symlink targets must use the canonical `/data/data/<pkg>/...`
        // path: inside the chroot only `/data/data/` is bind-mounted by
        // ChrootMounter, while `context.filesDir` returns the Android
        // `/data/user/0/...` view that doesn't exist in the chroot's
        // filesystem namespace.
        val srcLib = File(context.filesDir, "libhybris/lib").canonicalFile
        if (!srcLib.isDirectory) {
            log("libhybris: extracted tree missing $srcLib — skipping")
            return false
        }
        val entries = srcLib.listFiles()?.sortedBy { it.name }.orEmpty()
        if (entries.isEmpty()) {
            log("libhybris: $srcLib is empty — skipping")
            return false
        }

        // Build a single su script: mkdir + per-entry `ln -sfn`. Using
        // `ln -sfn` so re-runs replace existing symlinks atomically and
        // don't follow into a previously-symlinked dir (the `-n` flag).
        // `--` separates the absolute target from the destination so a
        // future `lib*.so` filename starting with a dash doesn't get
        // misparsed as a flag.
        val script = buildString {
            appendLine("set -eu")
            appendLine("DST='$rootfsPath/usr/local/lib'")
            appendLine("mkdir -p \"\$DST\"")
            for (src in entries) {
                val name = src.name.replace("'", "'\\''")
                // entries from listFiles() carry the parent's path
                // verbatim — re-canonicalise each so the literal
                // /data/data path goes into the symlink, not /data/user/0.
                val target = src.canonicalPath.replace("'", "'\\''")
                appendLine("ln -sfn -- '$target' \"\$DST/$name\"")
            }
            appendLine("echo OK")
        }
        val r = Su.run(script) { log("libhybris-link: $it") }
        if (!r.ok) {
            throw IOException("LibhybrisLinker failed:\n${r.output}")
        }
        log("libhybris: linked ${entries.size} entries into $rootfsPath/usr/local/lib")
        return true
    }
}
