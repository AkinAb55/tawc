package me.phie.tawc.install

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata parsing for [Installation.externalBinds] / [ExternalBind]:
 * legacy-record defaults, round-trip, forward compat, and the
 * structural validator every accepting surface shares.
 */
class InstallationExternalBindsTest {

    private fun minimalRecord(extra: String = ""): String = """
        {
          "id": "arch",
          "arch": "arm64-v8a"
          $extra
        }
    """.trimIndent()

    @Test
    fun legacyRecordParsesToNoBinds() {
        val inst = Installation.fromJson(minimalRecord())
        assertTrue(inst.externalBinds.isEmpty())
    }

    @Test
    fun bindsRoundTripThroughJson() {
        val binds = listOf(
            ExternalBind("/", "/android", label = "Android root"),
            ExternalBind("/storage/emulated/0", "/home/android"),
        )
        val inst = Installation.fromJson(minimalRecord()).copy(externalBinds = binds)
        val reparsed = Installation.fromJson(inst.toJson())
        assertEquals(binds, reparsed.externalBinds)
    }

    @Test
    fun emptyBindsListOmittedFromJson() {
        val inst = Installation.fromJson(minimalRecord())
        assertTrue(!inst.toJson().contains("externalBinds"))
    }

    @Test
    fun unknownBindKindIsSkipped() {
        val arr = """
            [
              {"kind": "saf-tree", "uri": "content://something"},
              {"hostPath": "/storage/emulated/0", "guestPath": "/home/android"}
            ]
        """.trimIndent()
        val binds = ExternalBind.fromJsonArray(JSONArray(arr))
        assertEquals(listOf(ExternalBind("/storage/emulated/0", "/home/android")), binds)
    }

    @Test
    fun missingKindDefaultsToPath() {
        val inst = Installation.fromJson(
            minimalRecord(""",  "externalBinds": [{"hostPath": "/", "guestPath": "/android"}]""")
        )
        assertEquals(listOf(ExternalBind("/", "/android")), inst.externalBinds)
    }

    @Test
    fun labelSurvivesRoundTrip() {
        val bind = ExternalBind("/storage/emulated/0", "/home/android", label = "Android home")
        val parsed = ExternalBind.fromJsonArray(ExternalBind.toJsonArray(listOf(bind)))
        assertEquals(listOf(bind), parsed)
    }

    @Test
    fun validBindPassesValidation() {
        assertNull(ExternalBind("/storage/emulated/0", "/home/android").validationError())
        assertNull(ExternalBind("/", "/android").validationError())
    }

    @Test
    fun invalidBindsFailValidation() {
        // Relative paths.
        assertNotNull(ExternalBind("storage", "/home/android").validationError())
        assertNotNull(ExternalBind("/storage/emulated/0", "home/android").validationError())
        // Guest root would alias the rootfs itself.
        assertNotNull(ExternalBind("/storage/emulated/0", "/").validationError())
        // ':' would split the tawcroot `-b src:dst` argv pair.
        assertNotNull(ExternalBind("/storage/a:b", "/home/android").validationError())
        assertNotNull(ExternalBind("/storage/emulated/0", "/home/a:b").validationError())
        // '..' escapes the rootfs on the guest-target mkdir path.
        assertNotNull(ExternalBind("/storage/../data", "/home/android").validationError())
        assertNotNull(ExternalBind("/storage/emulated/0", "/home/../../evil").validationError())
        // Control characters.
        assertNotNull(ExternalBind("/storage/emulated/0", "/home/an\ndroid").validationError())
    }
}
