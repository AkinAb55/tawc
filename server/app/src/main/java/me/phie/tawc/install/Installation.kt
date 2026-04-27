package me.phie.tawc.install

import org.json.JSONObject
import java.io.File

/**
 * Persistent metadata for a single installed Linux environment. Stored as
 * `metadata.json` next to the rootfs in
 * `<app data>/distros/<id>/`.
 *
 * The set of fields is deliberately small but already factored to support
 * the directions called out in the original task:
 *  - alternative distros via [distro]
 *  - alternative install methods (chroot now, proot later) via [method]
 *  - multiple installations via [id]
 */
data class Installation(
    val id: String,
    val distro: String,
    val arch: String,
    val method: String,
    val installedAtMillis: Long,
    val sourceUrl: String,
) {
    fun rootfsDir(store: InstallationStore): File = store.rootfsDir(id)
    fun metadataFile(store: InstallationStore): File = store.metadataFile(id)

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("distro", distro)
        put("arch", arch)
        put("method", method)
        put("installedAtMillis", installedAtMillis)
        put("sourceUrl", sourceUrl)
    }.toString(2)

    companion object {
        const val DISTRO_ARCH = "arch"
        const val METHOD_CHROOT = "chroot"

        fun fromJson(text: String): Installation {
            val obj = JSONObject(text)
            return Installation(
                id = obj.getString("id"),
                distro = obj.getString("distro"),
                arch = obj.getString("arch"),
                method = obj.getString("method"),
                installedAtMillis = obj.optLong("installedAtMillis", 0L),
                sourceUrl = obj.optString("sourceUrl", ""),
            )
        }
    }
}
