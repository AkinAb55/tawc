package me.phie.tawc.dev

import android.util.Log
import java.io.File
import me.phie.tawc.install.InstallationStore

/**
 * Broker actions for the gfxstream-bridge daemon (kumquat).
 *
 * Why this exists: the kumquat server uses SCM_RIGHTS over an
 * AF_UNIX SEQPACKET socket to hand the guest Mesa client a memfd that
 * backs each blob resource. Android's SELinux policy silently drops the
 * fd half of the cmsg when the sender and receiver live in different
 * domains (e.g. `magisk` <-> `untrusted_app`); the only AVCs are
 * dontaudit'd, so the symptom is a successful sendmsg on the server,
 * a recvmsg on the client that returns the bytes but no fds, and a
 * MesaError::Unsupported → -EINVAL bubbling out as
 * `DRM_VIRTGPU_KUMQUAT_RESOURCE_CREATE_BLOB failed with Invalid
 * argument` from the Mesa side. Fixing the SELinux policy needs
 * `magiskpolicy` device-side hacks; running both ends in the same
 * domain doesn't.
 *
 * Keeping the daemon in the app's process tree (broker spawns it as
 * the app uid + untrusted_app context) means the chroot client's
 * Tube and the server's Tube share an SELinux domain — fd passing
 * just works. The kumquat binary ships as `libkumquat.so` inside
 * `nativeLibraryDir` (jniLib trick — `apk_data_file` label is the only
 * SELinux file label `untrusted_app` can `execute`); see the
 * `buildBridge` Gradle task in `app/build.gradle.kts` and
 * `scripts/build-kumquat-server.sh`.
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `start-bridge-daemon` | `install=<id>` (optional) | Spawns kumquat detached if not running. Default install picked from [InstallationStore.list] when there's exactly one. |
 * | `stop-bridge-daemon`  | — | SIGKILL the daemon if our PID file points at a live process. |
 *
 * The daemon's lifecycle is intentionally per-app-process: app death
 * (force-stop, OOM kill) takes the daemon with it. That's fine —
 * the test runner force-stops the app between runs anyway, and a
 * stale daemon with no clients on the other end is harmless.
 */
internal object BridgeActions {

    private const val TAG = "tawc-bridge"

    fun registerAll() {
        ActionRegistry.register("start-bridge-daemon", StartBridgeDaemon)
        ActionRegistry.register("stop-bridge-daemon", StopBridgeDaemon)
    }

    /** PID file lives in the app's no-backup files dir; survives broker
     *  restarts but not process death. */
    private fun pidFile(appContext: android.content.Context): File =
        File(appContext.noBackupFilesDir, "kumquat.pid")

    /** Probe `/proc/<pid>/comm` to confirm the recorded PID is still
     *  our kumquat (and not someone we'd race-recycle). The kernel
     *  truncates `comm` to 15 chars, so the actual `libkumquat.so`
     *  exec name lands as itself (under 15). Using the disguised
     *  name — the binary ships as `libkumquat.so` (jniLib trick;
     *  see [BridgeActions]'s top-of-file rationale). */
    private fun isLiveKumquat(pid: Int): Boolean {
        val comm = runCatching { File("/proc/$pid/comm").readText().trim() }.getOrNull()
        return comm == "libkumquat.so"
    }

    private object StartBridgeDaemon : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val app = ctx.appContext
            val store = InstallationStore(app)
            val installId = args["install"] ?: run {
                val ids = store.list().map { it.id }
                when (ids.size) {
                    1 -> ids.first()
                    0 -> { ctx.err("start-bridge-daemon: no installs present"); return 2 }
                    else -> {
                        ctx.err("start-bridge-daemon: multiple installs (${ids.joinToString()}); pass --arg install=<id>")
                        return 2
                    }
                }
            }
            val rootfsDir = store.rootfsDir(installId)
            if (!rootfsDir.isDirectory) {
                ctx.err("start-bridge-daemon: rootfs missing at $rootfsDir")
                return 2
            }

            // Daemon already up?
            val pidFile = pidFile(app)
            val existing = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull()
            if (existing != null && isLiveKumquat(existing)) {
                ctx.out("kumquat already running (pid=$existing)")
                return 0
            }

            val nativeDir = app.applicationInfo.nativeLibraryDir
            val kumquatBin = File(nativeDir, "libkumquat.so")
            if (!kumquatBin.canExecute()) {
                ctx.err("start-bridge-daemon: $kumquatBin not executable. " +
                    "Rebuild with `bash scripts/build-kumquat-server.sh` and reinstall the APK.")
                return 2
            }

            val socketPath = File(rootfsDir, "tmp/kumquat-gpu-0").absolutePath
            // Stale socket file from a previous run blocks bind(); kumquat
            // does its own remove in KumquatBuilder::build but only when
            // the path is reachable. Be defensive at this layer too.
            File(socketPath).delete()
            File(socketPath).parentFile?.mkdirs()

            val logFile = File(app.noBackupFilesDir, "kumquat.log")

            val pb = ProcessBuilder(
                kumquatBin.absolutePath,
                "--gpu-socket-path", socketPath,
            )
            pb.environment().clear()
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            // Inheriting the broker's stdio would tie the daemon's
            // lifetime to the broker connection (host Ctrl-C closes the
            // socket, the broker pumps EOF, the daemon gets SIGPIPE on
            // its next stderr write). Redirect to a real file so the
            // daemon outlives the action invocation.
            pb.redirectOutput(logFile)
            pb.redirectErrorStream(true)

            val proc = try {
                pb.start()
            } catch (t: Throwable) {
                ctx.err("start-bridge-daemon: spawn failed: ${t.message}")
                return 1
            }
            // Drop our end of stdin so the child's read returns EOF if
            // it ever cares (kumquat doesn't, but this keeps it tidy).
            runCatching { proc.outputStream.close() }
            // `Process.pid()` doesn't resolve at our compileSdk — see
            // ExecBrokerSession.pidOf for the same reflection trick.
            val pid = try {
                proc.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(proc)
            } catch (_: Throwable) {
                ctx.err("start-bridge-daemon: couldn't read child pid via reflection")
                proc.destroy()
                return 1
            }
            pidFile.writeText("$pid\n")

            // Brief sanity wait — give kumquat a beat to bind the socket
            // so the caller doesn't have to. If it dies inside this
            // window the test would race anyway, so propagate as failure.
            repeat(30) {
                if (!proc.isAlive) {
                    val log = runCatching { logFile.readText() }.getOrNull().orEmpty()
                    ctx.err("start-bridge-daemon: kumquat exited (code=${proc.exitValue()})\n$log")
                    pidFile.delete()
                    return 1
                }
                if (File(socketPath).exists()) {
                    ctx.out("kumquat started (pid=$pid, socket=$socketPath)")
                    Log.i(TAG, "started kumquat pid=$pid socket=$socketPath")
                    return 0
                }
                Thread.sleep(100)
            }
            // Socket never appeared — server is hung in init. Don't
            // leave a half-alive daemon behind.
            ctx.err("start-bridge-daemon: socket $socketPath not bound within 3s")
            proc.destroy()
            pidFile.delete()
            return 1
        }
    }

    private object StopBridgeDaemon : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val pidFile = pidFile(ctx.appContext)
            val pid = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull()
            if (pid == null || !isLiveKumquat(pid)) {
                pidFile.delete()
                ctx.out("kumquat not running")
                return 0
            }
            android.os.Process.killProcess(pid)
            pidFile.delete()
            ctx.out("kumquat stopped (pid=$pid)")
            return 0
        }
    }
}
