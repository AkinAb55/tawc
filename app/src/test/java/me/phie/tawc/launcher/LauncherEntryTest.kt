package me.phie.tawc.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LauncherEntry.parseList] against the Rust scanner's JSON shape. */
class LauncherEntryTest {

    @Test
    fun parsesFullEntry() {
        val json = """
            [{
              "id": "firefox",
              "name": "Firefox",
              "comment": "Browse the web",
              "exec": "firefox --new-window",
              "terminal": false,
              "iconPath": "/data/rootfs/usr/share/icons/hicolor/128x128/apps/firefox.png",
              "path": "/data/rootfs/usr/share/applications/firefox.desktop"
            }]
        """.trimIndent()
        val entries = LauncherEntry.parseList(json)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("firefox", e.id)
        assertEquals("Firefox", e.name)
        assertEquals("firefox --new-window", e.exec)
        assertEquals("/data/rootfs/usr/share/applications/firefox.desktop", e.path)
    }

    @Test
    fun missingPathParsesToEmpty() {
        // Pre-path scanner output (or hand-written test JSON) must not fail.
        val entries = LauncherEntry.parseList("""[{"id": "xterm", "exec": "xterm"}]""")
        assertEquals(1, entries.size)
        assertEquals("", entries[0].path)
    }

    @Test
    fun malformedJsonYieldsEmptyList() {
        assertTrue(LauncherEntry.parseList("not json").isEmpty())
        assertTrue(LauncherEntry.parseList(null).isEmpty())
        assertTrue(LauncherEntry.parseList("").isEmpty())
    }
}
