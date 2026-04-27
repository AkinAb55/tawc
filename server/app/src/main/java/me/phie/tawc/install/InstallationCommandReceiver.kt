package me.phie.tawc.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets external tooling (adb shell, integration tests, …) drive the
 * installation system without going through the UI. Mirrors what
 * `client/arch-chroot-create*`, `arch-chroot-destroy`, and
 * `arch-chroot-run` do, with output sent back to logcat under the
 * `tawc-install` tag.
 *
 * Examples:
 *
 *   adb shell am broadcast -a me.phie.tawc.install.LIST
 *
 *   adb shell am broadcast -a me.phie.tawc.install.INSTALL \
 *       --es id arch
 *
 *   adb shell am broadcast -a me.phie.tawc.install.UNINSTALL \
 *       --es id arch
 *
 *   adb shell am broadcast -a me.phie.tawc.install.RUN \
 *       --es id arch \
 *       --es cmd 'uname -m'
 *
 * INSTALL / UNINSTALL kick off [InstallationService] (foreground) and
 * return immediately. The ongoing log appears under
 * `adb logcat -s tawc-install`.
 *
 * RUN runs synchronously inside the receiver and prints its result to
 * logcat (and to the broadcast result via setResultData, which
 * `am broadcast` prints when `-W` is used). Mounts come up inside the
 * RUN's su shell and disappear with it — there is no separate mount
 * lifecycle to manage (same model as `client/arch-chroot-run`).
 */
class InstallationCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getStringExtra("id") ?: Installation.DISTRO_ARCH
        val store = InstallationStore(app)
        val rootfs = store.rootfsDir(id).absolutePath
        Log.i(TAG, "command: ${intent.action} id=$id")

        when (intent.action) {
            ACTION_LIST -> {
                val installs = store.list()
                if (installs.isEmpty()) {
                    Log.i(TAG, "LIST: no installations")
                    setResult(0, "no installations", null)
                } else {
                    val text = installs.joinToString("\n") {
                        "${it.id} (distro=${it.distro} arch=${it.arch} method=${it.method})"
                    }
                    Log.i(TAG, "LIST:\n$text")
                    setResult(0, text, null)
                }
            }

            ACTION_INSTALL -> {
                // Try to launch the activity from here. This works when the
                // app is in the foreground or has Background Activity Launch
                // (BAL) privileges; otherwise Android 14 silently BAL_BLOCKs
                // it. The reliable cold-state CLI path is `am start` directly:
                //   am start -n me.phie.tawc/.install.ManageInstallationsActivity \
                //       --es autoAction install --es id <id>
                // (See notes/installation.md.) We attempt it here so callers
                // with the activity already up don't need a second command.
                launchActivityWithAction(app, id, "install")
                setResult(
                    0,
                    "Best-effort launch of ManageInstallationsActivity for install '$id'. " +
                        "If the app is cold, BAL may be blocked — fall back to: " +
                        "am start -n me.phie.tawc/.install.ManageInstallationsActivity " +
                        "--es autoAction install --es id $id",
                    null,
                )
            }

            ACTION_UNINSTALL -> {
                launchActivityWithAction(app, id, "uninstall")
                setResult(
                    0,
                    "Best-effort launch of ManageInstallationsActivity for uninstall '$id'. " +
                        "If the app is cold, fall back to: " +
                        "am start -n me.phie.tawc/.install.ManageInstallationsActivity " +
                        "--es autoAction uninstall --es id $id",
                    null,
                )
            }

            ACTION_RUN -> {
                val cmd = intent.getStringExtra("cmd")
                if (cmd.isNullOrEmpty()) {
                    Log.w(TAG, "RUN missing --es cmd '<command>'")
                    setResult(1, "missing --es cmd", null)
                    return
                }
                val r = ChrootRunner.run(rootfs, cmd)
                Log.i(TAG, "RUN exit=${r.exitCode}\n${r.output}")
                setResult(if (r.ok) 0 else 1, r.output, null)
            }

            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun launchActivityWithAction(context: Context, id: String, autoAction: String) {
        val i = Intent(context, ManageInstallationsActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_AUTO_ACTION, autoAction)
        context.startActivity(i)
    }

    companion object {
        private const val TAG = "tawc-install"
        const val ACTION_LIST = "me.phie.tawc.install.LIST"
        const val ACTION_INSTALL = "me.phie.tawc.install.INSTALL"
        const val ACTION_UNINSTALL = "me.phie.tawc.install.UNINSTALL"
        const val ACTION_RUN = "me.phie.tawc.install.RUN"
        const val EXTRA_ID = "id"
        const val EXTRA_AUTO_ACTION = "autoAction"
    }
}
