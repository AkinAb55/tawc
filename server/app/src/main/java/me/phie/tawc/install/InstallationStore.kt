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
 *       installations/
 *         <id>/
 *           metadata.json
 *           rootfs/                    # the chroot itself
 *
 * The default install id is `arch`. Future multi-install support just
 * varies the id; nothing else here cares.
 */
class InstallationStore(context: Context) {
    val baseDir: File = File(context.dataDir, "installations")
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
}
