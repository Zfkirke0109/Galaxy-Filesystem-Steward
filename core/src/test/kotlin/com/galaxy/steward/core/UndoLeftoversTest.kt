package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Undo used to mark a run undone even when something stayed in the quarantine: its Undo button went away, and what
 * stayed was lost when the quarantine was emptied a week later.
 */
class UndoLeftoversTest {
    @Test
    fun aQuarantinedItemWhoseNameIsTakenComesBackBesideIt() = runTest {
        TestFs().use { fs ->
            fs.text("Download/notes.txt", "mine")
            fs.ageDirectories()
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(item(fs, "Download/notes.txt", 4)))
            assertEquals(1, run.quarantined)

            // A browser saves a new notes.txt before the user changes their mind.
            fs.text("Download/notes.txt", "a new download")
            RollbackEngine(fs.rootPath, journals).rollback(run.runId)

            assertEquals("a new download", File(fs.path("Download/notes.txt")).readText())
            assertEquals("mine", File(fs.path("Download/notes (restored).txt")).readText())
            assertNotNull(journals.info(run.runId)?.rolledBackAt)
        }
    }

    @Test
    fun anUndoThatLeavesSomethingInTheQuarantineCanBeTriedAgain() = runTest {
        TestFs().use { fs ->
            fs.text("Download/old/notes.txt", "mine")
            fs.ageDirectories()
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(item(fs, "Download/old/notes.txt", 4)))
            assertEquals(1, run.quarantined)

            // Its folder's name is a file now, so the note can't go back yet.
            File(fs.path("Download/old")).run {
                deleteRecursively()
                writeText("a file now")
            }
            RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertNull(journals.info(run.runId)?.rolledBackAt)
            assertTrue(journals.info(run.runId)!!.canRollback)

            // Once that is sorted out, the second try brings it back and the run is done.
            File(fs.path("Download/old")).delete()
            RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals("mine", File(fs.path("Download/old/notes.txt")).readText())
            assertNotNull(journals.info(run.runId)?.rolledBackAt)
        }
    }

    private fun item(fs: TestFs, rel: String, size: Long) =
        JunkItem("junk:$rel", JunkCategory.OLD_INSTALLERS, fs.path(rel), false, size, File(fs.path(rel)).lastModified(), "test")
}
