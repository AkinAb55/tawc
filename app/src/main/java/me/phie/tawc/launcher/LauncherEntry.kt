package me.phie.tawc.launcher

import me.phie.tawc.compositor.NativeBridge
import org.json.JSONArray

/**
 * One launchable Linux application discovered inside a chroot rootfs.
 * Mirrors the JSON shape returned by [NativeBridge.nativeLauncherScan] —
 * the Rust scanner is the source of truth for what counts as launchable
 * (Type=Application, not NoDisplay/Hidden, has Exec).
 */
data class LauncherEntry(
    /** Filename minus `.desktop`, used as a stable id. */
    val id: String,
    val name: String,
    val comment: String,
    /** Exec line with field codes (`%f`, `%u`, …) already stripped. */
    val exec: String,
    val terminal: Boolean,
    /**
     * Absolute path to a PNG icon file inside the rootfs, or empty if
     * none was findable. The Rust scanner only ever returns PNGs (Android
     * can't decode SVG natively); SVG-only icons end up empty here and
     * the row renders without an icon.
     */
    val iconPath: String,
    /**
     * Absolute host path of the `.desktop` file this entry was parsed
     * from. Distinguishes managed (user-editable) entries from distro
     * ones; empty only for malformed scanner output.
     */
    val path: String = "",
) {
    companion object {
        /**
         * Scan [rootfs] with the Rust scanner and parse the result — the
         * one entry point every consumer (launcher list, shortcut
         * trampoline, debug broker) goes through. A native failure yields
         * an empty list, same as "no apps". Blocking file I/O; call on
         * [kotlinx.coroutines.Dispatchers.IO] from UI code.
         */
        fun scan(rootfs: String): List<LauncherEntry> =
            parseList(runCatching { NativeBridge.nativeLauncherScan(rootfs) }.getOrNull())

        /**
         * Hidden-state + search filtering, the pure core of the launcher's
         * list state. Entries with an id in [hiddenIds] are dropped unless
         * [showHidden]. [query] is a case-insensitive substring match
         * against name + id + comment; name-prefix matches sort first (so
         * typing "fire" surfaces Firefox above "WireFire"), everything
         * else keeps the scanner's name order.
         */
        fun filter(
            entries: List<LauncherEntry>,
            hiddenIds: Set<String>,
            showHidden: Boolean,
            query: String,
        ): List<LauncherEntry> {
            val visible = if (showHidden) entries else entries.filter { it.id !in hiddenIds }
            val q = query.trim().lowercase()
            if (q.isEmpty()) return visible
            val prefix = ArrayList<LauncherEntry>()
            val other = ArrayList<LauncherEntry>()
            for (e in visible) {
                val n = e.name.lowercase()
                if (n.startsWith(q)) prefix.add(e)
                else if (n.contains(q) || e.id.lowercase().contains(q) ||
                    e.comment.lowercase().contains(q)) other.add(e)
            }
            return prefix + other
        }

        fun parseList(json: String?): List<LauncherEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            LauncherEntry(
                                id = o.optString("id"),
                                name = o.optString("name"),
                                comment = o.optString("comment"),
                                exec = o.optString("exec"),
                                terminal = o.optBoolean("terminal", false),
                                iconPath = o.optString("iconPath"),
                                path = o.optString("path"),
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
