package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.AppPaths
import me.phie.tawc.GraphicsBackend
import me.phie.tawc.Settings
import java.io.File
import java.io.IOException

class TawcrootMethod(context: Context) : InstallationMethod {
    private val appPaths = AppPaths.from(context)
    private val tawcShare: String = appPaths.shareDir.absolutePath
    private val store = InstallationStore(context)

    val tawcrootBin: String =
        File(context.applicationInfo.nativeLibraryDir, "libtawcroot.so").absolutePath

    override val key: String = KEY
    override val displayName: String = "tawcroot (systrap, rootless)"
    override val requiresRoot: Boolean = false

    override fun isAvailable(context: Context): Boolean {
        val f = File(tawcrootBin)
        return f.exists() && f.canExecute()
    }

    override fun runOutside(script: String, onLine: ((String) -> Unit)?): MethodResult =
        Sh.run("set -eu\n$script", onLine)

    override fun startInside(rootfs: String, command: String?, graphics: GraphicsBackend?): Process {
        val externalBinds = externalBindsFor(rootfs)
        val andoHostDir = store.andoHostDir(rootfs)
        val tmpdir = prepareSpawn(rootfs, externalBinds)
        
        val argv = buildList {
            add("/system/bin/setsid")
            addAll(rootfsArgv(rootfs, graphics, externalBinds, andoHostDir))
            add("/bin/bash")
            if (command != null) {
                add("-lc"); add(command)
            } else {
                add("-l")
            }
        }
        
        return ProcessBuilder(argv)
            .directory(File(tmpdir))
            .also {
                it.environment().clear()
                it.environment()["TMPDIR"] = "$rootfs/tmp"
                it.environment()["TEMP"] = "$rootfs/tmp"
                it.environment()["TMP"] = "$rootfs/tmp"
            }
            .start()
    }

    fun ptyShellExec(
        rootfs: String,
        graphics: GraphicsBackend? = null,
        command: String? = null,
    ): PtyExec {
        val externalBinds = externalBindsFor(rootfs)
        val andoHostDir = store.andoHostDir(rootfs)
        val tmpdir = prepareSpawn(rootfs, externalBinds)
        
        val argv = buildList {
            addAll(rootfsArgv(rootfs, graphics, externalBinds, andoHostDir))
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            add("/bin/bash")
            if (command != null) {
                add("-lc"); add(command)
            } else {
                add("-l")
            }
        }
        
        return PtyExec(
            argv,
            listOf("TMPDIR=$rootfs/tmp", "TEMP=$rootfs/tmp", "TMP=$rootfs/tmp"),
            tmpdir
        )
    }

    private fun prepareSpawn(rootfs: String, externalBinds: List<ExternalBind>): String {
        File(rootfs, GUEST_TAWC_SHARE_DIR.removePrefix("/")).mkdirs()
        for (dir in LIBHYBRIS_BIND_DIRS) {
            File(rootfs, dir.removePrefix("/")).mkdirs()
        }
        for (bind in externalBinds) {
            File(rootfs, bind.guestPath.removePrefix("/")).mkdirs()
        }
        File(tawcShare).mkdirs()
        File("$tawcShare/xtmp/.X11-unix").mkdirs()
        val tmpdir = "$rootfs/tmp"
        File(tmpdir).mkdirs()
        return tmpdir
    }

    private fun rootfsArgv(
        rootfs: String,
        graphics: GraphicsBackend?,
        externalBinds: List<ExternalBind>,
        andoHostDir: String?,
    ): List<String> = buildList {
        add(tawcrootBin)
        addAll(listOf("-r", rootfs))
        for ((src, dst) in bindSpecs(externalBinds, andoHostDir)) addAll(listOf("-b", "$src:$dst"))
        add("--")
        addAll(RootfsEnv.envArgv(RootfsEnv.Method.TAWCROOT, graphics ?: Settings.graphicsBackend))
    }

    private fun externalBindsFor(rootfs: String): List<ExternalBind> {
        val id = store.idForRootfs(rootfs) ?: return emptyList()
        return store.load(id)?.externalBinds ?: emptyList()
    }

    private fun bindSpecs(
        externalBinds: List<ExternalBind>,
        andoHostDir: String?,
    ): List<Pair<String, String>> = buildList {
        add("/dev" to "/dev")
        add("/proc" to "/proc")
        add("/sys" to "/sys")
        for (dir in LIBHYBRIS_BIND_DIRS) add(dir to dir)
        add(tawcShare to GUEST_TAWC_SHARE_DIR)
        andoHostDir?.let { add(it to GUEST_ANDO_DIR) }
        add("$tawcShare/xtmp/.X11-unix" to "/tmp/.X11-unix")
        for (bind in externalBinds) add(bind.hostPath to bind.guestPath)
    }

    companion object {
        const val KEY = "tawcroot"
        const val GUEST_TAWC_SHARE_DIR = "/usr/share/tawc"
        const val GUEST_ANDO_DIR = "/run/tawc-ando"
        val LIBHYBRIS_BIND_DIRS: List<String> = listOf(
            "/apex", "/vendor", "/system", "/system_ext", "/linkerconfig"
        ).filter { File(it).exists() }
    }
}
