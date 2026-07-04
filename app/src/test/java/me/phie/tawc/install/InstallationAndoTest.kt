package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata parsing for [Installation.andoEnabled] (notes/ando.md):
 * legacy-record default (absent → false), round-trip, and the
 * omit-when-default JSON shape.
 */
class InstallationAndoTest {

    private fun minimalRecord(extra: String = ""): String = """
        {
          "id": "arch",
          "arch": "arm64-v8a"
          $extra
        }
    """.trimIndent()

    @Test
    fun legacyRecordDefaultsToDisabled() {
        // Field absent in older metadata → fail-closed, ando off.
        assertFalse(Installation.fromJson(minimalRecord()).andoEnabled)
    }

    @Test
    fun andoEnabledRoundTripsThroughJson() {
        val enabled = Installation.fromJson(minimalRecord()).copy(andoEnabled = true)
        assertTrue(Installation.fromJson(enabled.toJson()).andoEnabled)

        val disabled = Installation.fromJson(minimalRecord()).copy(andoEnabled = false)
        assertFalse(Installation.fromJson(disabled.toJson()).andoEnabled)
    }

    @Test
    fun explicitFalseParses() {
        val inst = Installation.fromJson(minimalRecord(""", "andoEnabled": false"""))
        assertFalse(inst.andoEnabled)
        val on = Installation.fromJson(minimalRecord(""", "andoEnabled": true"""))
        assertTrue(on.andoEnabled)
    }

    @Test
    fun defaultDisabledOmittedFromJson() {
        // The additive field is only serialized when enabled, so legacy
        // records stay byte-identical after a load/save cycle.
        assertFalse(Installation.fromJson(minimalRecord()).toJson().contains("andoEnabled"))
    }

    @Test
    fun enabledSerializesTheField() {
        val json = Installation.fromJson(minimalRecord()).copy(andoEnabled = true).toJson()
        assertTrue(json.contains("andoEnabled"))
    }
}
