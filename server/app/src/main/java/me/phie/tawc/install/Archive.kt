package me.phie.tawc.install

import com.github.luben.zstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/**
 * Bootstrap-tarball helpers.
 *
 * Extraction is delegated to the system's `toybox tar` running as root
 * via [Su] so every file lands with root ownership and tar's special-file
 * handling (symlinks, hardlinks, devices) Just Works inside the chroot.
 *
 * We can't pipe the decompressed tar into the shell's stdin because the
 * shell pre-buffers stdin past the script and tar then sees garbage
 * ("tar: Not tar"). So if the source is compressed with zstd (which
 * toybox tar can't read), we decompress it to a transient `.tar` file
 * (path supplied by [BootstrapCache.tempPlainTarFor]) first, hand that
 * to tar, and delete it after.
 *
 * Toybox tar can't `--strip-components`, so when the bootstrap is wrapped
 * in a single top-level dir (`root.x86_64/`) we extract verbatim and
 * flatten the wrapper with `mv` afterwards.
 */
object Archive {

    /**
     * Extract [tarball] (a `.tar`, `.tar.gz`, or `.tar.zst`) into [destDir]
     * inside the device's filesystem. Creates [destDir] if it doesn't
     * exist; **does not** clear an existing directory — the install
     * pipeline's state-machine gate guarantees we only ever run against
     * a `(no dir)` slot. Root permissions are required.
     *
     * For `.tar.zst` inputs the decompressed bytes are written to
     * [tempPlainTar] (owned by the cache); the file is deleted on entry
     * (so a crash leftover never gets reused — those used to be silently
     * accepted on `length() != 0L`) and again in the `finally`. For
     * `.tar` / `.tar.gz` inputs [tempPlainTar] is unused.
     *
     * (Wiping is the sole job of [RootfsCleaner], called from the
     * uninstall path. If install ever wiped here, a single missed
     * unmount could let `rm` walk through a live `/dev` bind and unlink
     * host system nodes — see notes/installation.md.)
     *
     * If [stripPrefix] is non-null and exactly that single top-level
     * directory exists in the tarball, its contents are flattened into
     * [destDir] (and the now-empty prefix dir removed).
     *
     * @throws IOException on any extraction failure.
     */
    fun extractAsRoot(
        tarball: File,
        destDir: String,
        tempPlainTar: File,
        stripPrefix: String? = null,
        onLine: (String) -> Unit = {},
    ) {
        require(tarball.exists()) { "Tarball not found: $tarball" }

        val (tarFile, isTransient) = decompressIfNeeded(tarball, tempPlainTar)

        try {
            val script = buildString {
                appendLine("mkdir -p '$destDir'")
                // -z is autodetected by toybox tar when the file's first
                // bytes look like gzip; we don't need to spell it out.
                appendLine("tar -xf '${tarFile.absolutePath}' -C '$destDir'")
                if (stripPrefix != null) {
                    appendLine("if [ -d '$destDir/$stripPrefix' ]; then")
                    appendLine("    cd '$destDir/$stripPrefix'")
                    appendLine("    for entry in * .[!.]* ..?*; do")
                    appendLine("        [ -e \"\$entry\" ] || continue")
                    appendLine("        mv -- \"\$entry\" '$destDir/'")
                    appendLine("    done")
                    appendLine("    cd '$destDir'")
                    appendLine("    rmdir '$destDir/$stripPrefix'")
                    appendLine("fi")
                }
                appendLine("echo OK")
            }
            val result = Su.run(script, onLine = onLine)
            if (!result.ok) {
                throw IOException(
                    "Tar extraction failed (exit=${result.exitCode}). Output:\n${result.output}"
                )
            }
            if (!result.output.lineSequence().any { it.trim() == "OK" }) {
                throw IOException("Tar extraction did not report OK. Output:\n${result.output}")
            }
        } finally {
            if (isTransient) tempPlainTar.delete()
        }
    }

    /**
     * Returns `(tarFile, isTransient)`. For `.tar` / `.tar.gz` inputs
     * `tarFile = src` and `isTransient = false`. For `.tar.zst` decompresses
     * into [plainTmp] and returns it with `isTransient = true` — caller's
     * `finally` deletes. [plainTmp] is unconditionally deleted before write
     * so a crashed previous run can't get reused as if complete.
     */
    private fun decompressIfNeeded(src: File, plainTmp: File): Pair<File, Boolean> {
        val name = src.name.lowercase()
        return when {
            name.endsWith(".tar") -> src to false
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> src to false
            name.endsWith(".tar.zst") || name.endsWith(".tzst") -> {
                plainTmp.delete()
                BufferedInputStream(src.inputStream(), 256 * 1024).use { raw ->
                    ZstdInputStream(raw).use { zin ->
                        plainTmp.outputStream().use { out -> zin.copyTo(out) }
                    }
                }
                plainTmp to true
            }
            else -> throw IOException("Unsupported tarball extension: ${src.name}")
        }
    }

}
