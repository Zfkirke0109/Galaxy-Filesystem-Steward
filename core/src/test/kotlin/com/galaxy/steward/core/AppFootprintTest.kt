package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.footprint.AppFootprint
import com.galaxy.steward.core.footprint.FootprintPlace
import com.galaxy.steward.core.scan.TreeScanner
import com.galaxy.steward.core.termux.TermuxEntry
import com.galaxy.steward.core.termux.TermuxKept
import com.galaxy.steward.core.termux.TermuxReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Everything WoWee left behind, found by its name, the way the 9-30 phone had it. */
class AppFootprintTest {
    @Test
    fun anAppsNameGivesTheWordsItsFoldersCarry() {
        assertEquals(setOf("wowee"), AppFootprint.tokens("com.wowee.client", "WoWee"))
        assertEquals(setOf("layla", "laylalite"), AppFootprint.tokens("com.layla", "Layla Lite"))
        // Generic words name nobody's folders; what you type is used as it is.
        assertEquals(emptySet<String>(), AppFootprint.tokens("com.example.notes", "Notes"))
        assertEquals(setOf("bionicle"), AppFootprint.tokens(query = "Bionicle"))
        assertTrue(AppFootprint.matches("WoWee-Backup", setOf("wowee")))
        assertTrue(AppFootprint.matches("3Dwowee-v3.1.41-android-arm64.apk", setOf("wowee")))
        assertFalse(AppFootprint.matches("Documents", setOf("wowee")))
    }

    @Test
    fun itsFoldersFilesAndInstallersAreFoundAndProjectsAndKeysStay() = runTest {
        TestFs().use { fs ->
            repeat(20) { fs.random("WoWee-Backup/files/Data/expansions/patch-$it.MPQ", 2000, it) }
            fs.random("Download/3Dwowee-v3.1.41-android-arm64.apk", 5000, 99)
            fs.random("Pictures/Screenshots/wowee_login.png", 700, 98)
            fs.text("Documents/Projects/WoWee-src/.git/HEAD", "ref: refs/heads/main")
            fs.text("Documents/Projects/WoWee-src/main.cpp", "int main() {}")
            fs.text("Documents/Keys/wowee-release.jks", "key")
            fs.text("Documents/Notes/todo.txt", "nothing about it")
            fs.ageDirectories()
            val tree = TreeScanner(fs.rootPath, testSettings).scan()

            val hits = AppFootprint.inTree(tree, AppFootprint.tokens("com.wowee.client", "WoWee")).associateBy { it.path.removePrefix(fs.rootPath + "/") }
            assertEquals(
                setOf(
                    "WoWee-Backup", "Download/3Dwowee-v3.1.41-android-arm64.apk", "Pictures/Screenshots/wowee_login.png",
                    "Documents/Projects/WoWee-src", "Documents/Keys/wowee-release.jks",
                ),
                hits.keys,
            )
            assertEquals(40000L, hits.getValue("WoWee-Backup").bytes)
            assertNull(hits.getValue("WoWee-Backup").lock)
            assertNotNull(hits.getValue("Documents/Projects/WoWee-src").lock)
            assertNotNull(hits.getValue("Documents/Keys/wowee-release.jks").lock)

            // One run quarantines what isn't locked; History can undo it.
            val items = AppFootprint.toJunk(hits.values.toList(), "WoWee")
            assertEquals(3, items.size)
            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("WoWee", "footprint", items)
            assertEquals(3, summary.quarantined)
            assertFalse(fs.exists("WoWee-Backup") || fs.exists("Download/3Dwowee-v3.1.41-android-arm64.apk"))
            assertTrue(fs.exists("Documents/Projects/WoWee-src/main.cpp") && fs.exists("Documents/Keys/wowee-release.jks") && fs.exists("Documents/Notes/todo.txt"))
        }
    }

    @Test
    fun inTermuxTheTopmostMatchCountsAndKeptOrPackageFilesStayLocked() {
        val home = "/data/data/com.termux/files/home"
        val prefix = "/data/data/com.termux/files/usr"
        val report = TermuxReport(
            home, prefix, false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            entries = listOf(
                TermuxEntry("$home/WoWee", 300L shl 20, true),
                TermuxEntry("$home/WoWee/build", 200L shl 20, true),
                TermuxEntry("$home/wowee-relay", 2L shl 20, true),
                TermuxEntry("$home/other", 5L shl 20, true),
            ),
            kept = listOf(TermuxKept("$home/wowee-relay", 2L shl 20, "you chose to keep it")),
        )
        val hits = AppFootprint.inTermux(report, setOf("wowee"))
        assertEquals(listOf("$home/WoWee", "$home/wowee-relay"), hits.map { it.path })
        assertTrue(hits.all { it.place == FootprintPlace.TERMUX })
        assertNull(hits[0].lock)
        assertTrue(hits[1].lock!!.startsWith("Kept"))
    }
}
