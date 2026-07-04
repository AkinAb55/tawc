package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata parsing for [Installation.hiddenDesktopIds] (launcher hide/
 * unhide, notes/launcher.md): legacy-record default (absent → empty),
 * round-trip, omit-when-empty JSON shape, and the [Installation.withEntryHidden]
 * copy helper.
 */
class InstallationHiddenDesktopIdsTest {

    private fun minimalRecord(extra: String = ""): String = """
        {
          "id": "arch",
          "arch": "arm64-v8a"
          $extra
        }
    """.trimIndent()

    @Test
    fun legacyRecordDefaultsToEmpty() {
        assertTrue(Installation.fromJson(minimalRecord()).hiddenDesktopIds.isEmpty())
    }

    @Test
    fun roundTripsThroughJson() {
        val inst = Installation.fromJson(minimalRecord())
            .copy(hiddenDesktopIds = listOf("firefox", "org.gnome.Nautilus"))
        assertEquals(
            listOf("firefox", "org.gnome.Nautilus"),
            Installation.fromJson(inst.toJson()).hiddenDesktopIds,
        )
    }

    @Test
    fun explicitArrayParses() {
        val inst = Installation.fromJson(
            minimalRecord(""", "hiddenDesktopIds": ["xterm"]"""),
        )
        assertEquals(listOf("xterm"), inst.hiddenDesktopIds)
    }

    @Test
    fun emptyOmittedFromJson() {
        // Additive field is only serialized when non-empty, so legacy
        // records stay byte-identical after a load/save cycle.
        assertFalse(Installation.fromJson(minimalRecord()).toJson().contains("hiddenDesktopIds"))
    }

    @Test
    fun withEntryHiddenAddsAndRemoves() {
        val base = Installation.fromJson(minimalRecord())
        val hidden = base.withEntryHidden("firefox", true)
        assertEquals(listOf("firefox"), hidden.hiddenDesktopIds)
        // Idempotent add — no duplicate id.
        assertEquals(listOf("firefox"), hidden.withEntryHidden("firefox", true).hiddenDesktopIds)
        assertTrue(hidden.withEntryHidden("firefox", false).hiddenDesktopIds.isEmpty())
        // Removing an id that isn't there is a no-op.
        assertEquals(listOf("firefox"), hidden.withEntryHidden("xterm", false).hiddenDesktopIds)
    }
}
