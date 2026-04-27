package me.phie.tawc.install

import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Installs Arch Linux into an [InstallationStore] entry. The stage
 * sequence intentionally matches the legacy `arch-chroot-create` /
 * `arch-chroot-create-emulator` scripts so behaviour is portable:
 *
 *  1. download bootstrap tarball  (DOWNLOADING)
 *  2. extract to rootfs           (EXTRACTING)
 *  3. write pacman/profile config (CONFIGURING)
 *  4. pacman-key init + populate  (PACMAN_INIT)
 *  5. pacman -Syu + base-devel    (PACMAN_INSTALL)
 *
 * There's no separate mount stage — bind mounts are set up inside the
 * `su` shell that runs each chroot command (see ChrootRunner), the
 * same way `client/arch-chroot-run` does it.
 *
 * On a clean rerun (e.g. resumed after a crash) early stages skip work
 * already done — the tarball is cached, an existing rootfs is reused,
 * and base-devel installation is skipped if `make` is already present.
 */
class ArchInstaller(
    private val store: InstallationStore,
    private val id: String = Installation.DISTRO_ARCH,
) {
    companion object {
        private const val TAG = "tawc-install"

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

        // Create the installation parent dir as the app uid up front so
        // metadata.json can be written without root. If a previous failed
        // run left the dir root-owned (mkdir -p inside extractAsRoot),
        // chown it back so subsequent metadata writes succeed. The
        // rootfs subdir stays root-owned (set by su during extraction);
        // that's fine — root reads/writes everything anyway.
        store.installationDir(id).mkdirs()
        chownAppDirNonRecursive(store.installationDir(id))

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

        // Stage 2: extract. Fresh rootfs each time (we wipe and re-extract
        // so the install is a known-good state). Cache hit on the tarball
        // means the slow part (network) is skipped on re-runs.
        progress(InstallProgress(InstallStage.EXTRACTING, "Extracting rootfs…"))
        log("extract: ${cacheFile.name} -> $rootfsPath (strip=$stripPrefix)")
        // Make sure no stale mounts exist from a previous failed install.
        ChrootMounter.unmount(rootfsPath)
        Archive.extractAsRoot(cacheFile, rootfsPath, stripPrefix) { line ->
            log("tar: $line")
        }

        // Stage 3: configure. DNS, pacman.conf, mirrorlist, profile.d.
        progress(InstallProgress(InstallStage.CONFIGURING, "Configuring chroot…"))
        configure(rootfsPath, log)

        // Persist metadata now: even if the slow pacman steps fail, an
        // operator can recover (run again, run a manual command in the
        // chroot, …) and we'd like to know an installation exists.
        val installation = Installation(
            id = id,
            distro = Installation.DISTRO_ARCH,
            arch = arch,
            method = Installation.METHOD_CHROOT,
            installedAtMillis = System.currentTimeMillis(),
            sourceUrl = url,
        )
        store.save(installation)

        // No explicit mount step — ChrootRunner.run does mount + chroot
        // in one su invocation (same as the legacy `arch-chroot-run`).
        //
        // Stage 5–6: pacman keyring + system.
        if (hasMake(rootfsPath)) {
            log("base-devel already present, skipping pacman bootstrap")
            progress(InstallProgress(InstallStage.DONE, "Installation already complete"))
            return
        }

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
            """.trimIndent(),
            onLine = { log("pacman-key: $it") },
        )
        if (!initRes.ok) {
            throw IOException("pacman-key init failed (exit=${initRes.exitCode})")
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
            pacman -Syu --noconfirm
            pacman -S --noconfirm --needed ${BUILD_PKGS.joinToString(" ")}
            """.trimIndent(),
            onLine = { log("pacman: $it") },
        )
        if (!syncRes.ok) {
            throw IOException("pacman -Syu / base-devel install failed (exit=${syncRes.exitCode})")
        }

        progress(InstallProgress(InstallStage.DONE, "Installation complete."))
    }

    /**
     * Permanently remove [id]: unmount everything, then `rm -rf` the dir
     * (refuses if any mounts are still active to avoid deleting through
     * a bind mount into real system files).
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
        val rootfsPath = store.rootfsDir(id).absolutePath

        // Kill anything still running inside the chroot. `pacman-key --init`
        // spawns gpg-agent --daemon which detaches and survives the
        // installer shell; left running, it (a) holds open FDs into the
        // dir we're about to delete and (b) races `rm` by recreating its
        // sockets, which on Android 14 is enough to spin vold's FUSE
        // accounting into a `vdc volume abort_fuse` storm.
        log("kill: chroot processes (root=$rootfsPath)")
        val killRes = Su.run(killChrootProcessesScript(rootfsPath)) { log("kill: $it") }
        if (!killRes.ok) {
            // Don't abort — we're about to nuke the rootfs anyway.
            log("kill: warning, exit=${killRes.exitCode}")
        }

        progress(InstallProgress(InstallStage.UNMOUNTING, "Unmounting chroot…"))
        log("unmount: $rootfsPath")
        val ur = ChrootMounter.unmount(rootfsPath)
        if (!ur.ok) {
            throw IOException("Unmount refused (active mounts):\n${ur.output}")
        }

        // Forensic snapshot before delete: list anything in /proc/mounts
        // that mentions the install dir or the app data dir, plus the
        // file count we're about to delete and the FuseDaemon pid. This
        // is what we want in logcat the next time uninstall freezes.
        val forensics = Su.run(
            """
            TAWC_DATA=/data/data/me.phie.tawc
            echo "[forensics] /proc/mounts hits (install dir or app data dir):"
            awk -v p='${installDir.absolutePath}' -v d="${'$'}TAWC_DATA" '${'$'}2 ~ p || ${'$'}2 ~ d {print}' /proc/mounts || true
            echo "[forensics] file count under install dir:"
            find '${installDir.absolutePath}' 2>/dev/null | wc -l
            echo "[forensics] FuseDaemon pids:"
            pidof FuseDaemon || echo "(none)"
            echo "[forensics] vdc count:"
            ps -ef | grep -c 'vdc volume' || true
            """.trimIndent()
        )
        forensics.output.lineSequence().forEach { log("pre-rm: $it") }

        progress(InstallProgress(InstallStage.DELETING, "Deleting rootfs…"))
        // Plain `rm -rf` of the install dir. An earlier attempt tried to
        // `mv` the dir to /data/local/tmp/ first (so the user-perceived
        // delete was instant and so the slow rm wouldn't trip Android's
        // FuseDaemon under /storage/emulated/0/Android/data/<pkg>) — but
        // /data/data/<pkg>/ is a separate bind mount on the AVD and
        // `rename(2)` can't cross mounts even on the same block device,
        // so `mv` silently fell back to a recursive copy that doubled
        // disk use and was orders of magnitude slower than just deleting.
        // See git log for the move-then-delete attempt.
        val script = """
            rm -rf '${installDir.absolutePath}'
            echo "[forensics] FuseDaemon pids after rm:"
            pidof FuseDaemon || echo "(none — DIED during rm)"
            echo "[forensics] vdc count after rm:"
            ps -ef | grep -c 'vdc volume' || true
        """.trimIndent()
        val delRes = Su.run(script) { log("rm: $it") }
        if (!delRes.ok) {
            throw IOException("rm -rf failed:\n${delRes.output}")
        }

        progress(InstallProgress(InstallStage.DONE, "Uninstalled."))
    }

    /**
     * Shell snippet that kills (-KILL, twice with a beat between) every
     * process whose `/proc/<pid>/root` is the chroot rootfs. Compares by
     * device:inode rather than path string, because Android's app data
     * dir has two equivalent paths (`/data/data/<pkg>` vs
     * `/data/user/0/<pkg>`) — `/proc/<pid>/root` reports one and our
     * Kotlin `File.absolutePath` returns the other. Also matches
     * processes with cwd inside the rootfs, which catches anything that
     * chdir'd into the chroot but didn't chroot itself.
     */
    private fun killChrootProcessesScript(rootfs: String): String = """
        ROOT_DI=${'$'}(stat -c '%d:%i' '$rootfs' 2>/dev/null) || ROOT_DI=
        if [ -z "${'$'}ROOT_DI" ]; then
            echo "rootfs '$rootfs' not stat-able; skipping process cleanup"
            exit 0
        fi
        kill_chroot_procs() {
            killed=0
            scanned=0
            for entry in /proc/[0-9]*; do
                [ -e "${'$'}entry" ] || continue
                pid=${'$'}{entry##*/}
                if [ "${'$'}pid" = "$$" ] || [ "${'$'}pid" = "${'$'}PPID" ]; then
                    continue
                fi
                scanned=${'$'}((scanned + 1))
                hit=
                # `stat` *without* -L on /proc/<pid>/root returns the procfs
                # symlink's own inode, not the target's. Force-follow with -L.
                rdi=${'$'}(stat -L -c '%d:%i' "${'$'}entry/root" 2>/dev/null) || rdi=
                [ "${'$'}rdi" = "${'$'}ROOT_DI" ] && hit=1
                if [ -z "${'$'}hit" ]; then
                    cwd=${'$'}(readlink "${'$'}entry/cwd" 2>/dev/null) || cwd=
                    case "${'$'}cwd" in
                        "$rootfs"|"$rootfs"/*) hit=1 ;;
                    esac
                fi
                if [ -n "${'$'}hit" ]; then
                    cmd=${'$'}(cat "${'$'}entry/comm" 2>/dev/null)
                    echo "killing pid=${'$'}pid (cmd=${'$'}cmd)"
                    kill -KILL "${'$'}pid" 2>/dev/null || true
                    killed=${'$'}((killed + 1))
                fi
            done
            echo "swept ${'$'}scanned procs, killed ${'$'}killed (rootfs dev:ino=${'$'}ROOT_DI)"
        }
        kill_chroot_procs
        # Daemons sometimes respawn or fork on signal — wait, then re-sweep.
        sleep 1
        kill_chroot_procs
    """.trimIndent()

    // -- helpers ----------------------------------------------------------

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

    /** True if /usr/bin/make exists inside the rootfs (= base-devel installed). */
    private fun hasMake(rootfs: String): Boolean {
        val r = Su.run("test -x '$rootfs/usr/bin/make' && echo YES || echo NO")
        return r.ok && r.output.lineSequence().any { it.trim() == "YES" }
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
                mkdir -p "${'$'}ROOTFS/etc/profile.d"
                cat > "${'$'}ROOTFS/etc/profile.d/00-path.sh" <<'PROF1_EOF'
                # tawc: fix Android-leaked environment for the chroot
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export TMPDIR=/tmp
                export HOME=/root
                PROF1_EOF
                chmod 644 "${'$'}ROOTFS/etc/profile.d/00-path.sh"

                # profile.d/01-tawc.sh — Wayland compositor env. Same
                # contents as the legacy arch-chroot-run wrote.
                cat > "${'$'}ROOTFS/etc/profile.d/01-tawc.sh" <<'PROF2_EOF'
                # tawc Wayland compositor environment
                export WAYLAND_DISPLAY=wayland-0
                export XDG_RUNTIME_DIR=/tmp
                export LD_LIBRARY_PATH=/tmp/gl-shims:/usr/local/lib
                export HYBRIS_EGLPLATFORM=wayland
                ln -sf /data/data/me.phie.tawc/wayland-0 /tmp/wayland-0 2>/dev/null
                PROF2_EOF
                chmod 644 "${'$'}ROOTFS/etc/profile.d/01-tawc.sh"
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
        val r = Su.run(
            """
            ANCHOR='${anchor.absolutePath}'
            UIDGID=${'$'}(stat -c '%u:%g' "${'$'}ANCHOR")
            chown "${'$'}UIDGID" '${dir.absolutePath}'
            """.trimIndent()
        )
        if (!r.ok) {
            Log.w("tawc-install", "chown of ${dir.absolutePath} failed: ${r.output}")
        }
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
