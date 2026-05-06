package me.phie.tawc.tasks

import android.system.Os
import java.io.File

/**
 * Walks `/proc` from the app uid (no `su`) and reports every process
 * whose kernel-side root / cwd / exe path lives inside a rootfs that
 * [installs] tells us about. Catches every `proot` and `tawcroot` guest
 * — both methods are pure ptrace fake-chroots, so the kernel sees real
 * paths under `<distros>/<id>/rootfs/` and reflects them through
 * `/proc/<pid>/{cwd,exe}` unmodified.
 *
 * Does not see real-`chroot(2)` guests on stock Android — those live
 * under uid 0 and the proc files are masked by hidepid. [SuProcfsScanner]
 * picks them up via `su`.
 *
 * One pass over `/proc`, regardless of how many installs are passed in.
 * Build a single path-prefix index up front, then each pid is
 * O(prefix-count) lookups (typically 1–3 installs).
 */
internal object AppUidProcfsScanner {

    /**
     * Walk `/proc` and return one [ProcessInfo] per matched pid.
     *
     * @param installs `(rootfsAbsPath, installId)` pairs for every
     *   slot we want to surface. The list is what gates classification:
     *   a process whose path matches none of these prefixes is dropped.
     *   Pass an empty list to get an empty result (no work done).
     * @param orphanPattern Optional `(rootfsParentPath, knownIds)` —
     *   processes whose path lives under `rootfsParentPath` (e.g.
     *   `/data/data/me.phie.tawc/distros`) but whose extracted slot id
     *   isn't in `knownIds` are reported as orphans (with the slot id
     *   preserved on [ProcessInfo.orphanRootfsId]). Pass `null` to
     *   disable orphan detection.
     * @param ownPid Skip this pid in the walk (the calling app's own
     *   pid) so the task manager never lists itself.
     * @param extraCmdlinePath Optional substring; processes whose
     *   `/proc/<pid>/cmdline` contains this are also included. Used
     *   by the install-cancel sweep to catch out-of-rootfs helpers
     *   (`tar`, `find`) running against the install dir from outside.
     *   Tagged with [extraCmdlineId] when matched only by cmdline.
     */
    fun scan(
        installs: List<Pair<String, String>>,
        orphanPattern: OrphanPattern?,
        ownPid: Int,
        extraCmdlinePath: String? = null,
        extraCmdlineId: String? = null,
    ): List<ProcessInfo> {
        if (installs.isEmpty() && orphanPattern == null && extraCmdlinePath == null) {
            return emptyList()
        }

        // Sort longest-first so a nested rootfs (hypothetical) wins over
        // its parent. Each suffix `/` ensures we don't match a sibling
        // dir that happens to share a name prefix.
        val sortedInstalls = installs
            .map { (path, id) -> path.trimEnd('/') + "/" to id }
            .sortedByDescending { it.first.length }
        val orphanPrefix = orphanPattern?.parentPath?.trimEnd('/')?.plus("/")

        val out = mutableListOf<ProcessInfo>()
        val procDir = File("/proc")
        val entries = procDir.list() ?: return emptyList()

        for (name in entries) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == ownPid) continue
            val procEntry = "/proc/$pid"

            var matchedInstall: String? = null
            var matchedOrphan: String? = null
            val classified = classify(procEntry, sortedInstalls, orphanPrefix, orphanPattern)
            if (classified != null) {
                matchedInstall = classified.first
                matchedOrphan = classified.second
            }
            // Only read cmdline if we either need it for the result
            // (a path match hit) or for the cmdline substring filter.
            val needCmdline = matchedInstall != null || matchedOrphan != null ||
                extraCmdlinePath != null
            val cmdline = if (needCmdline) readCmdline("$procEntry/cmdline") else ""
            if (matchedInstall == null && matchedOrphan == null && extraCmdlinePath != null) {
                if (cmdline.contains(extraCmdlinePath)) {
                    matchedInstall = extraCmdlineId
                }
            }
            if (matchedInstall == null && matchedOrphan == null) continue

            val comm = readSmall("$procEntry/comm")?.trim().orEmpty()
            out += ProcessInfo(
                pid = pid,
                ownerInstallId = matchedInstall,
                orphanRootfsId = matchedOrphan,
                comm = comm,
                cmdline = cmdline,
                requiresSu = false,
            )
        }
        return out
    }

    /**
     * `(installId-or-null, orphanId-or-null)`; null tuple ⇒ no match.
     * An install hit on any link beats an orphan hit on any other link,
     * so we sweep all three links for installs first, then fall through
     * to orphan detection.
     */
    private fun classify(
        procEntry: String,
        sortedInstalls: List<Pair<String, String>>,
        orphanPrefix: String?,
        orphanPattern: OrphanPattern?,
    ): Pair<String?, String?>? {
        val paths = arrayOf(
            readlinkOrNull("$procEntry/cwd"),
            readlinkOrNull("$procEntry/exe"),
            readlinkOrNull("$procEntry/root"),
        )
        for (path in paths) {
            if (path == null) continue
            for ((prefix, id) in sortedInstalls) {
                if (path.startsWith(prefix)) return id to null
            }
        }
        if (orphanPrefix != null && orphanPattern != null) {
            for (path in paths) {
                if (path == null || !path.startsWith(orphanPrefix)) continue
                val slot = path.substring(orphanPrefix.length).substringBefore('/')
                if (slot.isNotEmpty() && slot !in orphanPattern.knownIds) {
                    return null to slot
                }
            }
        }
        return null
    }

    private fun readlinkOrNull(path: String): String? = try {
        val raw = Os.readlink(path) ?: return null
        // The kernel appends " (deleted)" to readlink output for
        // unlinked-but-still-open files. Strip it so prefix matching
        // still works for orphans whose rootfs has been wiped.
        if (raw.endsWith(" (deleted)")) raw.removeSuffix(" (deleted)") else raw
    } catch (_: Throwable) {
        null
    }

    /** Up to 4 KiB; comm is short, cmdline is bounded by ARG_MAX. */
    private fun readSmall(path: String): String? = try {
        File(path).inputStream().use { s ->
            val buf = ByteArray(4096)
            val n = s.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    /** `/proc/<pid>/cmdline` is NUL-separated; render as space-separated. */
    private fun readCmdline(path: String): String {
        val raw = readSmall(path) ?: return ""
        return raw.trimEnd('\u0000').replace('\u0000', ' ')
    }

    /** See [scan]. */
    data class OrphanPattern(val parentPath: String, val knownIds: Set<String>)
}
