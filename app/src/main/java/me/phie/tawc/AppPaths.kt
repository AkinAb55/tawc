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

    /** ando broker socket (notes/ando.md). Guests see it at
     *  `/usr/share/tawc/ando.sock` via the share bind — keep the
     *  basename in sync with the client default in
     *  `tools/ando/src/ando.c`. */
    val andoSocket: File get() = File(shareDir, "ando.sock")

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
