package me.phie.tawc.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Serializer round-trip + filename slug/collision for the `.desktop` editor. */
class DesktopEntryFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun serializeRoundTripsAllFields() {
        val draft = DesktopEntryFile.Draft(
            name = "My Script",
            exec = "/root/bin/run.sh --fast",
            comment = "Does things",
            icon = "utilities-terminal",
            terminal = true,
        )
        val text = DesktopEntryFile.serialize(draft)
        val parsed = DesktopEntryFile.parse(text)
        assertEquals(draft, parsed.draft)
        assertFalse(parsed.hasForeignContent)
    }

    @Test
    fun serializeOmitsEmptyOptionalsAndFalseTerminal() {
        val text = DesktopEntryFile.serialize(DesktopEntryFile.Draft(name = "X", exec = "x"))
        assertEquals("[Desktop Entry]\nType=Application\nName=X\nExec=x\n", text)
    }

    @Test
    fun serializeStripsNewlines() {
        val text = DesktopEntryFile.serialize(
            DesktopEntryFile.Draft(name = "a\nb", exec = "run\r\nnow"),
        )
        val parsed = DesktopEntryFile.parse(text)
        assertEquals("a b", parsed.draft.name)
        assertEquals("run  now", parsed.draft.exec)
    }

    @Test
    fun foreignKeysGroupsAndLocalesAreFlagged() {
        // Locale-qualified key.
        assertTrue(
            DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Application\nName=X\nName[fr]=Y\nExec=x\n",
            ).hasForeignContent,
        )
        // Unknown key.
        assertTrue(
            DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Application\nName=X\nExec=x\nStartupNotify=true\n",
            ).hasForeignContent,
        )
        // Extra group.
        assertTrue(
            DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Application\nName=X\nExec=x\n[Desktop Action new]\nName=N\n",
            ).hasForeignContent,
        )
        // Non-Application type.
        assertTrue(
            DesktopEntryFile.parse("[Desktop Entry]\nType=Link\nName=X\nExec=x\n").hasForeignContent,
        )
    }

    @Test
    fun commentsAndBlanksAreNotForeign() {
        val parsed = DesktopEntryFile.parse(
            "# created by hand\n\n[Desktop Entry]\nType=Application\nName=X\nExec=x\n",
        )
        assertFalse(parsed.hasForeignContent)
        assertEquals("X", parsed.draft.name)
    }

    @Test
    fun extraGroupKeysDontLeakIntoDraft() {
        val parsed = DesktopEntryFile.parse(
            "[Desktop Entry]\nType=Application\nName=X\nExec=x\n" +
                "[Desktop Action new]\nName=Other\nExec=y\n",
        )
        assertEquals("X", parsed.draft.name)
        assertEquals("x", parsed.draft.exec)
    }

    @Test
    fun newFileSlugsNameAndSuffixesOnCollision() {
        val dir = tmp.newFolder()
        val first = DesktopEntryFile.newFile(dir, "My Script!")
        assertEquals("my-script.desktop", first.name)
        first.writeText("x")
        val second = DesktopEntryFile.newFile(dir, "My Script!")
        assertEquals("my-script-2.desktop", second.name)
        second.writeText("x")
        assertEquals("my-script-3.desktop", DesktopEntryFile.newFile(dir, "My Script!").name)
    }

    @Test
    fun unslugifiableNameFallsBack() {
        assertEquals("program.desktop", DesktopEntryFile.newFile(tmp.newFolder(), "!!!").name)
    }

    @Test
    fun isManagedMatchesOnlyTheManagedDir() {
        val rootfs = File("/data/data/me.phie.tawc/distros/arch/rootfs")
        assertTrue(
            DesktopEntryFile.isManaged(
                "${rootfs.absolutePath}/root/.local/share/applications/foo.desktop", rootfs,
            ),
        )
        assertFalse(
            DesktopEntryFile.isManaged(
                "${rootfs.absolutePath}/usr/share/applications/foo.desktop", rootfs,
            ),
        )
        assertFalse(
            DesktopEntryFile.isManaged(
                "${rootfs.absolutePath}/usr/local/share/applications/foo.desktop", rootfs,
            ),
        )
    }
}
