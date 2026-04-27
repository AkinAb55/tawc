package me.phie.tawc.install

import java.io.File
import java.io.IOException

/**
 * The one and only path that deletes anything under `<distros>/<id>/`.
 *
 * The install pipeline never wipes (the state-machine gate guarantees
 * `install` only runs against a `(no dir)` slot), so every byte that
 * leaves the rootfs goes through here. The sequence is:
 *
 *   1. **Kill chroot processes** by dev:inode of `/proc/<pid>/root`
 *      (and cwd as a fallback). The canonical leak is the
 *      `gpg-agent --daemon` that `pacman-key --init` detaches; left
 *      alive it holds FDs into the rootfs and races the delete, which
 *      on Android 14 spins vold's FUSE accounting into a `vdc volume
 *      abort_fuse` storm bad enough to ANR system_server. We send
 *      SIGKILL twice with a beat between to catch fork-on-signal
 *      respawns.
 *   2. **Strict unmount** via [ChrootMounter.unmount] (mount-master
 *      mode). Refuses with a non-zero exit if any mount remains under
 *      the rootfs — we'd rather leave a `FAILED` install on disk than
 *      `find` through a live `/dev` bind.
 *   3. **`find -xdev -depth -delete`** — never `rm -rf`, since toybox
 *      `rm` has no `--one-file-system`. `-xdev` is belt-and-braces
 *      against any mount [ChrootMounter.unmount] missed (e.g. a leak
 *      via `client/tawc-chroot-run` that bound `/dev` at runtime).
 *
 * `find -delete` prints every deleted path on stdout; we silence that
 * with `>/dev/null` so only stderr (real errors) reach the log.
 */
object RootfsCleaner {

    /**
     * Wipe [installDir] and everything inside it. Idempotent — a
     * missing dir is a successful wipe. Throws [IOException] on the
     * first sub-step failure; the caller is expected to record `FAILED`
     * state if the dir survives.
     */
    fun wipe(installDir: File, log: (String) -> Unit = {}) {
        if (!installDir.exists()) return
        val rootfsPath = File(installDir, "rootfs").absolutePath
        val installPath = installDir.absolutePath

        log("kill: chroot processes (root=$rootfsPath)")
        val killRes = Su.run(killChrootProcessesScript(rootfsPath)) { log("kill: $it") }
        if (!killRes.ok) {
            // Don't abort — we're about to nuke the rootfs anyway and
            // the unmount step below catches any actual blockers.
            log("kill: warning, exit=${killRes.exitCode}")
        }

        log("unmount: $rootfsPath")
        val ur = ChrootMounter.unmount(rootfsPath)
        if (!ur.ok) {
            throw IOException("Unmount refused (active mounts):\n${ur.output}")
        }

        val delRes = Su.run("find '$installPath' -xdev -depth -delete >/dev/null") { log("rm: $it") }
        if (!delRes.ok) {
            throw IOException("delete failed:\n${delRes.output}")
        }
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
}
