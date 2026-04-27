package me.phie.tawc.install

import android.content.Context
import java.io.File

/**
 * On-disk layout for installations.
 *
 * Everything lives under the app's private data dir so uninstalling tawc
 * automatically reclaims the space:
 *
 *     <app data>/
 *       cache/install/                 # downloaded bootstrap tarballs
 *       distros/
 *         <id>/
 *           metadata.json
 *           rootfs/                    # the chroot itself
 *
 * The default install id is `arch`. Future multi-install support just
 * varies the id; nothing else here cares.
 */
class InstallationStore(context: Context) {
    val baseDir: File = File(context.dataDir, "distros")
    val cacheDir: File = File(context.cacheDir, "install")

    fun installationDir(id: String): File = File(baseDir, id)
    fun rootfsDir(id: String): File = File(installationDir(id), "rootfs")
    fun metadataFile(id: String): File = File(installationDir(id), "metadata.json")

    /**
     * Path to the auto-generated `enter.sh` (mount + chroot wrapper).
     * Sibling of `rootfs/` so it lives in the app-uid-owned dir
     * (writable from in-app code without root) but is invoked via
     * `su -c '<path>'` so root drives the chroot exec.
     */
    fun enterScriptFile(id: String): File = File(installationDir(id), "enter.sh")

    /** Discover installations on disk by scanning [baseDir]. */
    fun list(): List<Installation> {
        val dir = baseDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                val meta = File(d, "metadata.json")
                if (!meta.exists()) return@mapNotNull null
                runCatching { Installation.fromJson(meta.readText()) }.getOrNull()
            }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    fun load(id: String): Installation? {
        val f = metadataFile(id)
        if (!f.exists()) return null
        return runCatching { Installation.fromJson(f.readText()) }.getOrNull()
    }

    fun save(installation: Installation) {
        installationDir(installation.id).mkdirs()
        metadataFile(installation.id).writeText(installation.toJson())
    }

    /**
     * Update the [Installation.state] field (and optional [failure]
     * detail) for [id], leaving every other field unchanged. The single
     * entry point through which the state machine moves; [InstallationService]
     * is the only caller.
     *
     * If no metadata exists yet the call is a no-op — install transitions
     * call [save] first to lay down the initial record, and uninstall
     * never moves a `(no dir)` slot.
     */
    fun setState(id: String, state: Installation.State, failure: String? = null) {
        val current = load(id) ?: return
        save(current.copy(state = state, failure = failure))
    }

    /**
     * Total bytes used by [id]'s installation dir (rootfs + metadata +
     * enter.sh). Uses `du -sk` via `su` because the rootfs is owned by
     * root after extraction, so app-uid `File.length()` traversal would
     * return 0 for everything inside it. Returns -1 on failure.
     *
     * Blocks on `su`; call from a background dispatcher.
     */
    fun computeSizeBytes(id: String): Long {
        val dir = installationDir(id)
        if (!dir.exists()) return 0L
        val r = Su.run("du -sk '${dir.absolutePath}' 2>/dev/null | awk '{print \$1}'")
        if (!r.ok) return -1L
        val kb = r.output.trim().toLongOrNull() ?: return -1L
        return kb * 1024L
    }
}
