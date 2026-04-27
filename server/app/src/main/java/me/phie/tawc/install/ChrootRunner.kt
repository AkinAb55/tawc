package me.phie.tawc.install

import android.util.Base64

/**
 * Run a command inside an installed chroot.
 *
 * Mount + chroot live in the canonical `<installation-dir>/enter.sh`
 * (see [ChrootMounter.enterScript]); we just refresh that file's
 * contents, then invoke it via `su -c '<enter.sh> <b64-cmd>'`. The
 * mounts come up inside enter.sh's `su` shell and disappear when it
 * exits, exactly like the legacy `client/arch-chroot-run`.
 *
 * The user command is base64-encoded into `$1` so it can contain any
 * bytes (single quotes, heredoc markers, embedded newlines, …) without
 * quoting through the host shell → su → chroot bash chain. Toybox
 * `base64` decodes it on the device side.
 */
object ChrootRunner {

    /**
     * Execute [command] inside the chroot at [rootfs]. Routes through
     * the on-disk `enter.sh` (regenerated each call so code changes to
     * the mount script take effect without a reinstall). [rootfs] is
     * passed as a string for symmetry with the rest of the install
     * package; the matching enter.sh path is derived by walking up one
     * dir (rootfs lives at `<installation-dir>/rootfs/`, enter.sh at
     * `<installation-dir>/enter.sh`).
     */
    fun run(
        rootfs: String,
        command: String,
        onLine: ((String) -> Unit)? = null,
    ): Su.Result {
        // Refresh enter.sh in case the mount logic has changed since
        // last install. Cheap (<1 KB write to app-owned dir).
        val enterFile = java.io.File(java.io.File(rootfs).parentFile, "enter.sh")
        enterFile.writeText(ChrootMounter.enterScript(rootfs))
        enterFile.setExecutable(true, false)

        val cmdB64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Quote enter.sh's path single-quoted; the b64 payload is base64
        // (no shell metacharacters) so embedding it bare is safe.
        return Su.run("exec '${enterFile.absolutePath}' $cmdB64", onLine = onLine)
    }
}
