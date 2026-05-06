package me.phie.tawc.tasks

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import me.phie.tawc.install.ChrootMethod
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore

/**
 * Single source of truth for "what guest processes are running, and
 * which install do they belong to?". Backs both the task manager UI
 * and (via [killAllInRootfs]) the uninstall-time process sweep in
 * [me.phie.tawc.install.RootfsCleaner].
 *
 * One [scan] call covers every install in one pass — the Kotlin
 * walker over `/proc` runs once for the whole system, and the
 * (chroot-only) `su` walker runs at most once per call regardless of
 * how many chroot installs are in the list. No N×install latency.
 *
 * The su path is guarded behind a single `hasChrootInstalls && Su.rootAvailable()`
 * check; everything else is plain Kotlin against the app uid's view of
 * `/proc`. Tawcroot- or proot-only systems never call into [SuProcfsScanner]
 * and never trigger a Magisk prompt. See that file's header for the
 * "build-time disablable" note.
 */
object ProcessScanner {

    /**
     * Snapshot of running guest processes, scoped to [installs] +
     * orphans. Empty list ⇒ no guests running (or no installs to
     * scan).
     */
    data class ScanResult(val processes: List<ProcessInfo>) {
        /** `installId -> processes`; orphans are *not* in this map. */
        fun groupedByInstall(): Map<String, List<ProcessInfo>> =
            processes.filter { it.ownerInstallId != null }
                .groupBy { it.ownerInstallId!! }

        /** Processes belonging to a now-uninstalled rootfs slot. */
        fun orphans(): List<ProcessInfo> =
            processes.filter { it.ownerInstallId == null }
    }

    /**
     * Scan `/proc` and classify every guest process found against the
     * given [installs]. The [context] is used to derive the
     * `<distros>` parent dir for orphan detection.
     */
    fun scan(context: Context, installs: List<Installation>): ScanResult {
        val store = InstallationStore(context)
        val pairs: List<Pair<String, String>> = installs.map { inst ->
            canonicalize(store.rootfsDir(inst.id).absolutePath) to inst.id
        }
        val knownIds = installs.map { it.id }.toSet()
        val orphan = AppUidProcfsScanner.OrphanPattern(
            parentPath = canonicalize(store.baseDir.absolutePath),
            knownIds = knownIds,
        )

        val ownPid = Process.myPid()
        val appProcs = AppUidProcfsScanner.scan(pairs, orphan, ownPid)

        // Only invoke su if there's at least one chroot install. This
        // is the gate that keeps tawcroot-/proot-only systems off the
        // su path.
        val chrootInstalls = installs.filter { it.method == ChrootMethod.KEY }
        val suProcs = if (chrootInstalls.isNotEmpty()) {
            val chrootPairs = chrootInstalls.map {
                canonicalize(store.rootfsDir(it.id).absolutePath) to it.id
            }
            SuProcfsScanner.scan(chrootPairs)
        } else {
            emptyList()
        }

        // Defensive dedupe; prefer app-uid records (killable without su).
        val seen = HashSet<Int>(appProcs.size + suProcs.size)
        val merged = ArrayList<ProcessInfo>(appProcs.size + suProcs.size)
        for (p in appProcs) if (seen.add(p.pid)) merged += p
        for (p in suProcs) if (seen.add(p.pid)) merged += p
        merged.sortBy { it.pid }
        return ScanResult(merged)
    }

    /**
     * Stop one process. Returns true if it's gone by the end of the
     * call (or was already gone).
     *
     * Asymmetric by [ProcessInfo.requiresSu]:
     *  - **app-uid:** SIGTERM, [graceMs] grace, then SIGKILL — polite
     *    enough that well-behaved guests get a chance to flush.
     *  - **root (su):** single SIGKILL. Two su invocations doubles the
     *    (slow) magisk handshake, and chroot daemons that prompted a
     *    Stop click are usually wedged anyway — the user wants them
     *    gone, not negotiated with.
     */
    fun stop(proc: ProcessInfo, graceMs: Long = 1000): Boolean {
        if (proc.requiresSu) {
            SuProcfsScanner.kill(proc.pid)
        } else {
            sendSignal(proc.pid, OsConstants.SIGTERM)
            if (waitForExit(proc.pid, graceMs)) return true
            sendSignal(proc.pid, OsConstants.SIGKILL)
        }
        return waitForExit(proc.pid, graceMs)
    }

    private fun sendSignal(pid: Int, signal: Int) {
        try { Os.kill(pid, signal) } catch (_: Throwable) {}
    }

    /**
     * `/proc/<pid>/cwd` reports the canonical path; `Context.dataDir`
     * on modern Android returns `/data/user/0/<pkg>` (a symlink to
     * `/data/data/<pkg>`). Without this normalisation prefix matches
     * miss every guest on every device.
     */
    private fun canonicalize(path: String): String = try {
        java.io.File(path).canonicalPath
    } catch (_: Throwable) {
        path
    }

    /**
     * Spaced 50ms so a fast-exiting process is reported gone within
     * one tick rather than holding the UI for the full grace period.
     */
    private fun waitForExit(pid: Int, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            val alive = try {
                Os.kill(pid, 0)
                true
            } catch (e: android.system.ErrnoException) {
                // ESRCH ⇒ gone; EPERM ⇒ still there but we can't
                // signal it (root-owned, but the requiresSu branch
                // handled that — getting EPERM here means we lost the
                // race and someone else owns the pid now, treat as
                // gone).
                e.errno != OsConstants.EPERM
            } catch (_: Throwable) {
                false
            }
            if (!alive) return true
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
    }

    /**
     * Scan + hard-kill every match for [installId], wait, re-scan,
     * hard-kill leftovers. Two passes catch fork-on-signal respawns
     * like `pacman-key`'s detached `gpg-agent`.
     *
     * Context-free so [me.phie.tawc.install.RootfsCleaner] (which has
     * no [Context]) can call in directly.
     *
     * **Guests only, not supervisors.** Catches everything running
     * with `cwd`/`exe`/`root` inside [rootfsPath] — i.e. the actual
     * programs the user launched inside the chroot/proot/tawcroot.
     * The host-side supervisor process (proot tracer, tawcroot leader
     * pre-`exec`) is *not* matched: its `exe` is the supervisor binary
     * in `nativeLibraryDir`, not in the rootfs. ProotMethod.wipe pairs
     * its own `pkill -f` against the proot binary's argv to cover that
     * gap; tawcroot processes use `PR_SET_PDEATHSIG(SIGKILL)` and
     * disappear with their parent.
     *
     * [includeChroot] gates the `su` branch — pass `true` from chroot
     * wipe paths, `false` from proot / tawcroot wipes (which can't
     * have root-owned guest pids and shouldn't pay the magisk-prompt
     * latency).
     *
     * [extraCmdlinePath] adds an OR-match on `/proc/<pid>/cmdline`
     * substring — used by the install-cancel sweep to also catch
     * out-of-rootfs helpers (`tar`, `find`) launched against the
     * install dir from outside.
     */
    fun killAllInRootfs(
        rootfsPath: String,
        installId: String,
        includeChroot: Boolean,
        extraCmdlinePath: String? = null,
        log: (String) -> Unit,
    ) {
        val pair = listOf(canonicalize(rootfsPath) to installId)

        fun killOne(p: ProcessInfo) {
            // Skip the polite SIGTERM — uninstall is forced teardown,
            // the rootfs is about to disappear from under these procs.
            if (p.requiresSu) {
                SuProcfsScanner.kill(p.pid)
            } else {
                sendSignal(p.pid, OsConstants.SIGKILL)
            }
        }

        fun sweep(): List<ProcessInfo> {
            val app = AppUidProcfsScanner.scan(
                installs = pair,
                orphanPattern = null,
                ownPid = Process.myPid(),
                extraCmdlinePath = extraCmdlinePath,
                extraCmdlineId = installId,
            )
            val su = if (includeChroot) {
                SuProcfsScanner.scan(
                    installs = pair,
                    extraCmdlinePath = extraCmdlinePath,
                    extraCmdlineId = installId,
                )
            } else emptyList()
            val seen = HashSet<Int>(app.size + su.size)
            return (app + su).filter { seen.add(it.pid) }
        }

        val first = sweep()
        if (first.isEmpty()) {
            log("no guest processes to clean up")
            return
        }
        for (p in first) {
            log("pid=${p.pid} (${p.comm})")
            killOne(p)
        }
        try { Thread.sleep(1000) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        for (p in sweep()) {
            log("rescan pid=${p.pid} (${p.comm})")
            killOne(p)
        }
    }
}
