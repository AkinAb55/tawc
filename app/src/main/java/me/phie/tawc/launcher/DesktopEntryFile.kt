package me.phie.tawc.launcher

import java.io.File
import me.phie.tawc.install.Installation

/**
 * Read/write model for the personal `.desktop` files the in-app editor
 * ([DesktopFileEditorActivity]) manages under [MANAGED_SUBDIR] — the
 * one rootfs dir whose entries are user-editable ("not package-managed"
 * is tracked by directory, nothing else). Deliberately minimal: the
 * five editor fields plus `Type=Application`, no locale keys, actions,
 * field codes or extra groups. Files the editor created round-trip
 * exactly; anything richer parses into the known subset and reports
 * [Parsed.hasForeignContent] so the UI can warn that saving rewrites
 * the file wholesale.
 */
internal object DesktopEntryFile {

    /** The managed dir under a rootfs: the XDG per-user applications
     *  dir (the guest runs as fake root, so `$HOME` is `/root`). */
    const val MANAGED_SUBDIR = "root/.local/share/applications"

    /**
     * Canonicalized so path comparisons work across the
     * `/data/user/0/<pkg>` (Kotlin's `context.dataDir`) vs
     * `/data/data/<pkg>` (symlinked; the Rust scanner canonicalizes its
     * walk roots, so [LauncherEntry.path] uses this form) split.
     */
    fun managedDir(rootfs: File): File = File(canonical(rootfs), MANAGED_SUBDIR)

    /** Is [entryPath] (a [LauncherEntry.path]) inside [rootfs]'s managed dir? */
    fun isManaged(entryPath: String, rootfs: File): Boolean =
        canonical(File(entryPath)).path.startsWith(managedDir(rootfs).path + "/")

    private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f)

    /** The editor's field set. Values are kept verbatim except newlines
     *  (stripped on serialize — a `.desktop` value is one line). */
    data class Draft(
        val name: String = "",
        val exec: String = "",
        val comment: String = "",
        val icon: String = "",
        val terminal: Boolean = false,
    )

    fun serialize(draft: Draft): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Name=${oneLine(draft.name)}")
        appendLine("Exec=${oneLine(draft.exec)}")
        oneLine(draft.comment).takeIf { it.isNotEmpty() }?.let { appendLine("Comment=$it") }
        oneLine(draft.icon).takeIf { it.isNotEmpty() }?.let { appendLine("Icon=$it") }
        if (draft.terminal) appendLine("Terminal=true")
    }

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()

    data class Parsed(
        val draft: Draft,
        /**
         * The file carries content [serialize] would drop: keys it
         * doesn't write (including locale-qualified `Name[xx]`), groups
         * other than `[Desktop Entry]`, or a non-Application `Type`.
         * Blank lines and `#` comments don't count as foreign even
         * though a save drops them too.
         */
        val hasForeignContent: Boolean,
    )

    fun parse(text: String): Parsed {
        var name = ""
        var exec = ""
        var comment = ""
        var icon = ""
        var terminal = false
        var foreign = false
        var inDesktopEntry = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inDesktopEntry = line == "[Desktop Entry]"
                if (!inDesktopEntry) foreign = true
                continue
            }
            if (!inDesktopEntry) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                foreign = true
                continue
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "Type" -> if (value != "Application") foreign = true
                "Name" -> name = value
                "Exec" -> exec = value
                "Comment" -> comment = value
                "Icon" -> icon = value
                "Terminal" -> terminal = value.equals("true", ignoreCase = true)
                else -> foreign = true
            }
        }
        return Parsed(Draft(name, exec, comment, icon, terminal), foreign)
    }

    /**
     * Pick a filename for a new entry named [name] in [dir]:
     * `<slug>.desktop` with a `-2` / `-3` … suffix on collision. The
     * filename is the entry id (pins and hidden-state reference it), so
     * editing an existing file must keep its name — this is only for
     * creation. Unslugifiable names (punctuation-only) fall back to
     * "program".
     */
    fun newFile(dir: File, name: String): File {
        val slug = Installation.slugifyLabel(name) ?: "program"
        var candidate = File(dir, "$slug.desktop")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$slug-$n.desktop")
            n++
        }
        return candidate
    }
}
