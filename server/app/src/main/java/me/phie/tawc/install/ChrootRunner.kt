package me.phie.tawc.install

import android.util.Base64
import java.util.UUID

/**
 * Run a command inside an installed chroot.
 *
 * Mounts and the chroot exec live in the same `su` invocation so they
 * share Magisk's per-su mount namespace — exactly like the legacy
 * `client/arch-chroot-run` script does. The mounts come up before the
 * command runs and disappear when su exits, with no global leakage.
 *
 * To dodge quoting hell across host shell → su → chroot bash, the user
 * command is base64-encoded into the wrapper script and decoded inside
 * the chroot's `/tmp/`, so it can contain any bytes (single quotes,
 * heredoc markers, embedded newlines, …). Toybox `base64` is always
 * available on Android.
 */
object ChrootRunner {

    /**
     * Execute [command] inside the chroot at [rootfs]. Uses `bash -lc`,
     * which sources `/etc/profile.d/` scripts so PATH, TMPDIR and the
     * tawc Wayland env are set up — same as in the legacy script.
     *
     * Set [usePosixSh] when running before bash exists in the chroot
     * (early pacman bootstrap on a fresh tarball that only ships
     * `/bin/sh`).
     */
    fun run(
        rootfs: String,
        command: String,
        usePosixSh: Boolean = false,
        onLine: ((String) -> Unit)? = null,
    ): Su.Result {
        val shell = if (usePosixSh) "/bin/sh" else "/bin/bash"
        val flag = if (usePosixSh) "-c" else "-lc"
        val tmpName = "tawc-cmd-${UUID.randomUUID().toString().take(8)}.sh"
        val cmdB64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val script = buildString {
            // 1. Bind mounts (live for the duration of this su shell only).
            appendLine(ChrootMounter.mountScript(rootfs))
            // 2. Drop the user command into the chroot's /tmp/ as a script.
            appendLine(
                """
                TMP='$rootfs/tmp/$tmpName'
                mkdir -p '$rootfs/tmp'
                printf %s '$cmdB64' | base64 -d > "${'$'}TMP"
                chmod 755 "${'$'}TMP"
                """.trimIndent()
            )
            // 3. chroot + exec; remember exit code, clean up, exit with it.
            appendLine(
                """
                chroot '$rootfs' $shell $flag '/tmp/$tmpName'
                EC=${'$'}?
                rm -f "${'$'}TMP"
                exit ${'$'}EC
                """.trimIndent()
            )
        }
        return Su.run(script, onLine = onLine)
    }
}
