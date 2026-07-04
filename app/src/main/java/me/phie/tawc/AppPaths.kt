package me.phie.tawc

import android.content.Context
import java.io.File

/**
 * App-owned filesystem layout resolved from Android runtime paths.
 *
 * `dataDir` can differ from `/data/data/<pkg>` under Android multi-user or
 * profile installs, so app-owned paths should be derived from Android's
 * runtime dirs rather than package-name literals.
 */
data class AppPaths(
    val dataDir: File,
    val filesDir: File,
    val cacheDir: File,
    val shareDir: File,
    val distrosDir: File,
    val xwaylandDir: File,
    val xkbDir: File,
) {
    val xwaylandRuntimeDir: File get() = File(shareDir, "xtmp")
    val waylandSocket: File get() = File(shareDir, "wayland-0")
    val kumquatSocket: File get() = File(shareDir, "kumquat-gpu-0")

    /** Legacy single shared ando socket node (pre per-distro ando,
     *  notes/ando.md). No longer bound; app startup unlinks any stale
     *  node left by an older version. */
    val legacyAndoSocket: File get() = File(shareDir, "ando.sock")

    companion object {
        fun from(context: Context): AppPaths {
            val appContext = context.applicationContext
            val dataDir = appContext.dataDir
            val filesDir = appContext.filesDir
            val cacheDir = appContext.cacheDir
            return AppPaths(
                dataDir = dataDir,
                filesDir = filesDir,
                cacheDir = cacheDir,
                shareDir = File(dataDir, "share"),
                distrosDir = File(dataDir, "distros"),
                xwaylandDir = File(filesDir, "xwayland"),
                xkbDir = File(filesDir, "xkb"),
            )
        }
    }
}
