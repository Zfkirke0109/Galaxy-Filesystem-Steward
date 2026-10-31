package com.galaxy.steward.core

import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.QuarantineManager
import com.galaxy.steward.core.exec.RollbackEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A build signed with another key installs only after the old one is uninstalled, which wipes the journals in app
 * storage but not the quarantine in shared storage. What the old install's runs left there can still go back.
 */
class OrphanQuarantineTest {
    @Test
    fun whatRunsFromBeforeAReinstallLeftGoesBackWhereItWas() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            val old = "20261030-111654-ab12"
            fun put(run: String, rel: String, text: String) = q.dirFor(run).resolve(rel).toFile().apply {
                parentFile.mkdirs()
                writeText(text)
            }
            put(old, "Download/notes.txt", "notes")
            put(old, "Download/photo.jpg", "old photo")
            put(old, "Documents/Old/a.txt", "a")
            fs.text("Download/photo.jpg", "a new photo")
            fs.text("Documents/Old/b.txt", "b")
            // A run of this install has its journal, so History undoes it: not one of these.
            val mine = "20261031-090000-cd34"
            put(mine, "Download/mine.txt", "mine")
            journals.fileFor(mine).writeText("#title\tCleanup\n")

            assertEquals(listOf(old), q.orphans())
            val summary = RollbackEngine(fs.rootPath, null).rollbackEntries(q.orphanEntries(old))
            q.pruneEmpty(old)

            assertEquals(3, summary.restored)
            assertEquals(0, summary.remaining)
            assertEquals("notes", File(fs.path("Download/notes.txt")).readText())
            // Nothing is overwritten: what has the name now keeps it.
            assertEquals("a new photo", File(fs.path("Download/photo.jpg")).readText())
            assertEquals("old photo", File(fs.path("Download/photo (restored).jpg")).readText())
            assertEquals("a", File(fs.path("Documents/Old/a.txt")).readText())
            assertEquals("b", File(fs.path("Documents/Old/b.txt")).readText())
            assertTrue(q.orphans().isEmpty())
            assertFalse(q.dirFor(old).toFile().exists())
            assertTrue(q.dirFor(mine).resolve("Download/mine.txt").toFile().exists())
        }
    }
}
