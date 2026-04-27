package me.phie.tawc.install

import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Installs Arch Linux into an [InstallationStore] entry. Stages:
 *
 *  1. download bootstrap tarball  (DOWNLOADING)
 *  2. extract to rootfs           (EXTRACTING)
 *  3. write pacman/profile config (CONFIGURING)
 *  4. pacman-key init + populate  (PACMAN_INIT)
 *  5. pacman -Syu + base-devel    (PACMAN_INSTALL)
 *
 * There's no separate mount stage — bind mounts are set up inside the
 * `su` shell that runs each chroot command (see [ChrootRunner] /
 * [ChrootMounter.enterScript]).
 *
 * The installer trusts the state-machine gate ([InstallationService])
 * to only ever invoke `install` against a `(no dir)` slot, so the rootfs
 * is laid down on a clean directory and never overlaid. To re-install,
 * uninstall first. State transitions ([InstallationStore.setState]) are
 * driven from here:
 *
 *   install():    save(state=INSTALLING) → … → setState(READY)
 *   uninstall():  setState(UNINSTALLING) → RootfsCleaner.wipe → (no dir)
 *
 * On any throw the wrapper in [InstallationService] writes
 * [Installation.State.FAILED] with the exception message.
 */
class ArchInstaller(
    private val store: InstallationStore,
    private val id: String = Installation.DISTRO_ARCH,
) {
    companion object {
        /** Mirror used for the x86_64 bootstrap and the default mirrorlist. */
        private const val MIRROR_X86_64 =
            "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst"

        /** ALARM tarball URL. archlinuxarm.org doesn't redirect to HTTPS for /os/. */
        private const val MIRROR_AARCH64 =
            "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"

        private val BUILD_PKGS = listOf(
            "base-devel", "git", "libtool", "wayland", "wayland-protocols",
            "pkg-config", "autoconf", "automake", "patchelf",
            "weston", "gtk3", "gtk3-demos",
        )
    }

    /** Throws on failure. Reports progress + log lines via the callbacks. */
    fun install(
        progress: (InstallProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        val arch = primaryArch()
        val (url, isZst, stripPrefix) = selectBootstrap(arch)

        val rootfsDir = store.rootfsDir(id)
        val rootfsPath = rootfsDir.absolutePath

        // Lay down the metadata first thing, in INSTALLING. From this
        // moment forward the slot exists on disk and any failure parks
        // it in FAILED for the user to uninstall + retry. We use the
        // app uid (not root) for the parent dir so this writeText is a
        // plain Java file write — no su needed.
        store.installationDir(id).mkdirs()
        chownAppDirNonRecursive(store.installationDir(id))
        store.save(
            Installation(
                id = id,
                distro = Installation.DISTRO_ARCH,
                arch = arch,
                method = Installation.METHOD_CHROOT,
                installedAtMillis = System.currentTimeMillis(),
                sourceUrl = url,
                state = Installation.State.INSTALLING,
            )
        )

        // Stage 1: download.
        val cacheFile = File(store.cacheDir, "bootstrap-$arch.${if (isZst) "tar.zst" else "tar.gz"}")
        progress(InstallProgress(InstallStage.DOWNLOADING, "Downloading $arch bootstrap…"))
        log("download: $url -> ${cacheFile.absolutePath}")
        Downloader.download(url, cacheFile) { read, total ->
            val pct = total?.let { ((read * 100) / it).toInt().coerceIn(0, 100) }
            val totalLabel = total?.let { humanSize(it) } ?: "?"
            progress(
                InstallProgress(
                    InstallStage.DOWNLOADING,
                    "Downloading bootstrap: ${humanSize(read)} / $totalLabel",
                    pct,
                )
            )
        }

        // Stage 2: extract. The rootfs dir does not exist yet — the
        // gate only invokes install on a `(no dir)` slot — so tar lays
        // everything onto a fresh tree. Archive.extractAsRoot does not
        // wipe; never has reason to.
        progress(InstallProgress(InstallStage.EXTRACTING, "Extracting rootfs…"))
        log("extract: ${cacheFile.name} -> $rootfsPath (strip=$stripPrefix)")
        Archive.extractAsRoot(cacheFile, rootfsPath, stripPrefix) { line ->
            log("tar: $line")
        }

        // Stage 3: configure. DNS, pacman.conf, mirrorlist, profile.d,
        // and the auto-generated `enter.sh` (mount + chroot wrapper) the
        // host-side `client/tawc-chroot-run` invokes via `adb shell su`.
        // ChrootRunner.run also goes through enter.sh, so writing it here
        // is a precondition for every later pacman step.
        progress(InstallProgress(InstallStage.CONFIGURING, "Configuring chroot…"))
        configure(rootfsPath, log)
        writeEnterScript(rootfsPath, log)

        // Stages 4–5: pacman keyring + system. The state remains
        // INSTALLING throughout; if either pacman invocation fails the
        // service wraps it as FAILED and the only recovery is to
        // uninstall + install again.
        progress(InstallProgress(InstallStage.PACMAN_INIT, "Initializing pacman keyring…"))
        // Arch x86_64 ships the archlinux keyring; ALARM ships archlinuxarm.
        // Either way the keyring isn't strictly required since we set
        // SigLevel=Never above, but populating matches what the legacy
        // create script does and keeps `pacman -Syu` quiet.
        val populate = if (arch == "x86_64") {
            "pacman-key --populate archlinux 2>/dev/null || true"
        } else {
            "pacman-key --populate archlinuxarm 2>/dev/null || true"
        }
        val initRes = ChrootRunner.run(
            rootfsPath,
            """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            pacman-key --init
            $populate
            pacman -Syu --noconfirm
            """.trimIndent(),
            onLine = { log("pacman-key: $it") },
        )
        if (!initRes.ok) {
            throw IOException("pacman-key init / -Syu failed (exit=${initRes.exitCode})")
        }

        progress(
            InstallProgress(
                InstallStage.PACMAN_INSTALL,
                "Installing base packages (this takes a few minutes)…"
            )
        )
        val syncRes = ChrootRunner.run(
            rootfsPath,
            """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            pacman -S --noconfirm --needed ${BUILD_PKGS.joinToString(" ")}
            """.trimIndent(),
            onLine = { log("pacman: $it") },
        )
        if (!syncRes.ok) {
            throw IOException("pacman --needed install failed (exit=${syncRes.exitCode})")
        }

        // All stages succeeded — flip to READY. From this point the
        // gate refuses install and only allows uninstall.
        store.setState(id, Installation.State.READY)
        progress(InstallProgress(InstallStage.DONE, "Installation complete."))
    }

    /**
     * Permanently remove [id]: state → UNINSTALLING, [RootfsCleaner.wipe],
     * then the directory (including metadata.json) is gone. On a
     * `(no dir)` slot this is a no-op. Throws on wipe failure; the
     * service wraps as `FAILED` so a subsequent uninstall can retry.
     */
    fun uninstall(
        progress: (InstallProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        val installDir = store.installationDir(id)
        if (!installDir.exists()) {
            progress(InstallProgress(InstallStage.DONE, "Nothing to uninstall."))
            return
        }
        store.setState(id, Installation.State.UNINSTALLING)

        progress(InstallProgress(InstallStage.UNMOUNTING, "Unmounting chroot…"))
        // RootfsCleaner does the kill→unmount→find -xdev -depth -delete
        // sequence in one shot; we only need progress hints here.
        progress(InstallProgress(InstallStage.DELETING, "Deleting rootfs…"))
        RootfsCleaner.wipe(installDir, log)

        progress(InstallProgress(InstallStage.DONE, "Uninstalled."))
    }

    // -- helpers ----------------------------------------------------------

    /**
     * Render `<installation-dir>/enter.sh` from [ChrootMounter.enterScript].
     * Owned by app uid, so a plain file write is enough — no `su` needed.
     * Made +x so `su -c '<path>'` can exec it directly. Both
     * [ChrootRunner.run] and host tooling call this single file, so the
     * mount logic only lives in ChrootMounter.
     */
    private fun writeEnterScript(rootfsPath: String, log: (String) -> Unit) {
        val file = store.enterScriptFile(id)
        file.writeText(ChrootMounter.enterScript(rootfsPath))
        if (!file.setExecutable(true, false)) {
            log("warn: failed to chmod +x ${file.absolutePath}")
        } else {
            log("wrote ${file.absolutePath}")
        }
    }

    private fun primaryArch(): String =
        Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /**
     * Returns (url, is_zstd, stripPrefix). [stripPrefix] is the single
     * top-level dir inside the tarball that needs to be flattened into
     * the rootfs ("root.x86_64" for the Arch x86_64 bootstrap; null for
     * the ALARM tarball which is already flat).
     */
    private fun selectBootstrap(arch: String): Triple<String, Boolean, String?> = when (arch) {
        "x86_64" -> Triple(MIRROR_X86_64, true, "root.x86_64")
        "arm64-v8a" -> Triple(MIRROR_AARCH64, false, null)
        else -> throw IOException("Unsupported ABI: $arch")
    }

    /**
     * Write the per-rootfs configuration that the legacy create-script
     * wrote: DNS, pacman.conf tweaks (no SigLevel, no sandbox, no
     * CheckSpace, IgnorePkg for kernel/firmware), x86_64 mirrorlist,
     * and the profile.d scripts that fix PATH/TMPDIR and the tawc
     * Wayland env.
     */
    private fun configure(rootfs: String, log: (String) -> Unit) {
        val arch = primaryArch()
        val mirrorList = if (arch == "x86_64") {
            "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch"
        } else {
            // ALARM ships its own working mirrorlist; leave it alone.
            null
        }

        val script = buildString {
            appendLine("set -eu")
            appendLine("ROOTFS='$rootfs'")
            appendLine(
                """
                # DNS
                rm -f "${'$'}ROOTFS/etc/resolv.conf"
                echo nameserver 8.8.8.8 > "${'$'}ROOTFS/etc/resolv.conf"

                # pacman.conf
                sed -i 's/^SigLevel.*/SigLevel = Never/' "${'$'}ROOTFS/etc/pacman.conf"
                grep -q '^DisableSandbox' "${'$'}ROOTFS/etc/pacman.conf" || \
                    sed -i '/^SigLevel/a DisableSandbox' "${'$'}ROOTFS/etc/pacman.conf"
                sed -i 's/^CheckSpace/#CheckSpace/' "${'$'}ROOTFS/etc/pacman.conf"
                grep -q '^IgnorePkg' "${'$'}ROOTFS/etc/pacman.conf" || \
                    sed -i '/#CheckSpace/a IgnorePkg = linux linux-aarch64 linux-firmware linux-firmware-*' \
                        "${'$'}ROOTFS/etc/pacman.conf"
                """.trimIndent()
            )

            if (mirrorList != null) {
                appendLine(
                    """
                    # mirrorlist
                    cat > "${'$'}ROOTFS/etc/pacman.d/mirrorlist" <<'MIRROR_EOF'
                    $mirrorList
                    MIRROR_EOF
                    """.trimIndent()
                )
            }

            appendLine(
                """
                # profile.d/00-path.sh — the chroot bash inherits PATH
                # from the host (Android) which leaks /system/bin and
                # breaks everything; force a sane PATH/TMPDIR/HOME here.
                # 01-tawc.sh (Wayland env) is rewritten on every chroot
                # entry by ChrootMounter so env changes don't need a
                # reinstall — see notes/installation.md.
                mkdir -p "${'$'}ROOTFS/etc/profile.d"
                cat > "${'$'}ROOTFS/etc/profile.d/00-path.sh" <<'PROF1_EOF'
                # tawc: fix Android-leaked environment for the chroot
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export TMPDIR=/tmp
                export HOME=/root
                PROF1_EOF
                chmod 644 "${'$'}ROOTFS/etc/profile.d/00-path.sh"
                echo OK
                """.trimIndent()
            )
        }
        val r = Su.run(script) { log("conf: $it") }
        if (!r.ok) {
            throw IOException("Configure failed:\n${r.output}")
        }
    }

    /**
     * Reset ownership of [dir] (only the dir node itself, not its
     * contents) to the app's current uid:gid so the app process can
     * `open(O_WRONLY)` files inside it. We `stat` /data/data/<pkg> to
     * learn our uid since `Process.myUid()` would require importing
     * android.os.Process here just for one place.
     */
    private fun chownAppDirNonRecursive(dir: File) {
        val anchor = dir.parentFile?.parentFile ?: return // /data/data/me.phie.tawc
        Su.run(
            """
            ANCHOR='${anchor.absolutePath}'
            UIDGID=${'$'}(stat -c '%u:%g' "${'$'}ANCHOR")
            chown "${'$'}UIDGID" '${dir.absolutePath}'
            """.trimIndent()
        )
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
    }
}
