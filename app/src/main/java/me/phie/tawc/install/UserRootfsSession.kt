package me.phie.tawc.install

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import me.phie.tawc.AppPaths
import me.phie.tawc.GraphicsBackend
import me.phie.tawc.compositor.CompositorService
import java.io.IOException

/**
 * Entry point for user-launched rootfs commands. Installer/package setup
 * calls [InstallationMethod.startInside] directly; this wrapper is for
 * commands that may open Wayland/X11 windows and therefore need the
 * compositor service and socket alive first.
 */
internal object UserRootfsSession {
    private const val SOCKET_WAIT_TIMEOUT_MS = 30_000L
    private const val SOCKET_WAIT_POLL_MS = 50L

    fun startInside(
        context: Context,
        method: InstallationMethod,
        rootfs: String,
        command: String?,
        graphics: GraphicsBackend? = null,
    ): Process {
        CompositorService.ensureRunning(context)
        waitForWaylandSocket(context)
        return method.startInside(rootfs, command, graphics)
    }

    fun runInside(
        context: Context,
        method: InstallationMethod,
        rootfs: String,
        command: String,
        onLine: ((String) -> Unit)? = null,
        graphics: GraphicsBackend? = null,
    ): MethodResult {
        val proc = startInside(context, method, rootfs, command, graphics)
        return MethodRunHelper.collectProcess(proc, onLine)
    }

    private fun waitForWaylandSocket(context: Context) {
        val socket = AppPaths.from(context).waylandSocket
        val deadline = System.currentTimeMillis() + SOCKET_WAIT_TIMEOUT_MS
        val address = LocalSocketAddress(
            socket.absolutePath,
            LocalSocketAddress.Namespace.FILESYSTEM,
        )
        while (!canConnect(address)) {
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("Wayland socket did not become ready at ${socket.absolutePath}")
            }
            try {
                Thread.sleep(SOCKET_WAIT_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted waiting for Wayland socket", e)
            }
        }
    }

    private fun canConnect(address: LocalSocketAddress): Boolean = try {
        LocalSocket().use { socket ->
            socket.connect(address)
        }
        true
    } catch (_: IOException) {
        false
    }
}
