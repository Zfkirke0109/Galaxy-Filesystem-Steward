package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.JournalWriter
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.QuarantineManager
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.OrganizeMove
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android can stop the app at any moment of a run: swiped away, short of memory, updated. A change made just before
 * that, whose journal line never came, used to be unknown to Undo; one in the quarantine was then deleted unasked when
 * the quarantine was emptied by age. Changes are now journaled ahead too, and Undo reads the journal as it happened.
 */
class InterruptedRunTest {
    private fun junk(fs: TestFs, rel: String) = File(fs.path(rel)).let {
        JunkItem("junk:$rel", JunkCategory.OLD_INSTALLERS, it.path, false, it.length(), it.lastModified(), "test")
    }

    /** The journal as Android would leave it if it stopped the app right after the change, before its own line. */
    private fun stopBeforeLine(journals: JournalStore, runId: String, action: String) {
        val file = journals.fileFor(runId)
        val lines = file.readLines()
        val last = lines.indexOfLast { it.startsWith(action + "\t") }
        assertTrue("no $action line", last >= 0)
        assertTrue("the change wasn't journaled ahead", lines.take(last).any { it.startsWith(JournalAction.pending(action) + "\t") })
        file.writeText(lines.filterIndexed { i, l -> i != last && !l.startsWith("#finished") && !l.startsWith("#stats") }.joinToString("\n", postfix = "\n"))
    }

    @Test
    fun anItemQuarantinedRightBeforeTheAppStoppedCanBeUndone() = runTest {
        TestFs().use { fs ->
            fs.text("Download/app-old.apk", "an old installer")
            fs.ageDirectories()
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(junk(fs, "Download/app-old.apk")))
            assertEquals(1, run.quarantined)
            stopBeforeLine(journals, run.runId, JournalAction.QUARANTINED)

            val info = journals.info(run.runId)!!
            assertTrue(info.canRollback)
            assertEquals(null, info.finishedAt)
            val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals(1, undo.restored)
            assertEquals(0, undo.remaining)
            assertEquals("an old installer", File(fs.path("Download/app-old.apk")).readText())
            assertNotNull(journals.info(run.runId)?.rolledBackAt)
        }
    }

    @Test
    fun aFileMovedRightBeforeTheAppStoppedCanBeUndone() = runTest {
        TestFs().use { fs ->
            val f = fs.text("Download/boarding pass.pdf", "BP")
            fs.ageDirectories()
            val dst = fs.path("Documents/Personal/Travel/boarding pass.pdf")
            val move = OrganizeMove(
                "move:1", f.path, dst, false, 2, 1, "test", "Documents/Personal/Travel", true,
                listOf(MoveFileOp(f.path, dst, 2, f.lastModified())),
            )
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Organize", "organize", listOf(move))
            assertEquals(1, run.moved)
            stopBeforeLine(journals, run.runId, JournalAction.MOVED)

            RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertTrue(fs.exists("Download/boarding pass.pdf"))
            assertFalse(fs.exists("Documents/Personal/Travel/boarding pass.pdf"))
        }
    }

    /** Stopped after the line ahead but before the change itself: nothing to undo, and nothing is touched. */
    @Test
    fun aChangeAnnouncedButNeverMadeIsLeftAlone() = runTest {
        TestFs().use { fs ->
            fs.text("Download/notes.txt", "mine")
            fs.ageDirectories()
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val id = journals.newId()
            val q = File(fs.root, "${SafetyPolicy.STEWARD_DIR}/${SafetyPolicy.QUARANTINE_DIR}/$id/Download/notes.txt").path
            JournalWriter(journals.fileFor(id)).use {
                it.meta("title", "Cleanup")
                it.entry(JournalEntry(JournalAction.pending(JournalAction.QUARANTINED), fs.path("Download/notes.txt"), q, 4, -1, null))
            }
            val undo = RollbackEngine(fs.rootPath, journals).rollback(id)
            assertEquals(0, undo.restored)
            assertEquals(0, undo.skipped)
            assertEquals(0, undo.failed)
            assertEquals("mine", File(fs.path("Download/notes.txt")).readText())
        }
    }

    @Test
    fun aCompleteRunReadsAsBefore() = runTest {
        TestFs().use { fs ->
            fs.text("Download/a.apk", "a")
            fs.text("Download/b.apk", "bb")
            fs.ageDirectories()
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(junk(fs, "Download/a.apk"), junk(fs, "Download/b.apk")))
            val info = journals.info(run.runId)!!
            // Two lines ahead and two of their own, read as the two changes.
            assertEquals(2, journals.entries(run.runId).count { it.action.startsWith(JournalAction.PENDING) })
            assertEquals(2, info.entryCount)
            assertEquals(2, info.restorableCount)
            val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals(2, undo.restored)
            assertEquals(0, undo.skipped)
        }
    }

    @Test
    fun settleReadsAJournalAsItHappened() {
        val a = "/s/Download/x"
        val b = "/s/Documents/x"
        fun e(action: String, from: String = a, to: String = b) = JournalEntry(action, from, to, 1, 1, null)
        val moved = e(JournalAction.MOVED)
        assertEquals(listOf(moved), JournalAction.settle(listOf(e(JournalAction.pending(JournalAction.MOVED)), moved)) { false })
        assertEquals(listOf(moved), JournalAction.settle(listOf(e(JournalAction.pending(JournalAction.MOVED)))) { true })
        assertEquals(emptyList<JournalEntry>(), JournalAction.settle(listOf(e(JournalAction.pending(JournalAction.MOVED)))) { false })
        // A folder rename given up for a file-by-file merge is no folder move, whatever the files look like now.
        val inner = e(JournalAction.MOVED, "$a/f", "$b/f")
        val merged = listOf(
            e(JournalAction.pending(JournalAction.MOVED_DIR)),
            e(JournalAction.abandoned(JournalAction.MOVED_DIR)),
            e(JournalAction.pending(JournalAction.MOVED), "$a/f", "$b/f"),
            inner,
        )
        assertEquals(listOf(inner), JournalAction.settle(merged) { true })
        // Journals written before this change have no lines ahead and read the same.
        val old = listOf(moved, e(JournalAction.RMDIR, "/s/Download", ""))
        assertEquals(old, JournalAction.settle(old) { true })
    }

    /** A journal whose last line was cut off mid-write: what is added later isn't glued to it and lost with it. */
    @Test
    fun aTornLastLineDoesntSwallowTheNextOne() {
        val dir = java.nio.file.Files.createTempDirectory("journals").toFile()
        try {
            val journals = JournalStore(dir)
            journals.fileFor("r1").writeText("#title\tCleanup\nMOVED\t/s/a\t/s/b\t1\t2\t-")
            journals.appendMeta("r1", "rolledback", "5")
            val info = journals.info("r1")!!
            assertEquals(5L, info.rolledBackAt)
            assertEquals(1, journals.entries("r1").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Emptying by age deletes only what the journal records; you can still empty everything yourself. */
    @Test
    fun emptyingByAgeLeavesWhatNoJournalRecords() = runTest {
        TestFs().use { fs ->
            fs.text("Download/a.apk", "recorded")
            fs.ageDirectories()
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(junk(fs, "Download/a.apk")))
            val quarantine = QuarantineManager(PathGuard(fs.rootPath), journals)
            // Something the journal doesn't know of: a move whose line was lost.
            val stray = File(quarantine.dirFor(run.runId).toFile(), "Download/lost.jpg").apply { writeText("not recorded") }
            journals.appendMeta(run.runId, "finished", (System.currentTimeMillis() - 40 * DAY_MS).toString())

            val freed = quarantine.purgeExpired(retentionDays = 30)
            assertEquals("recorded".length.toLong(), freed)
            assertTrue(stray.exists())
            assertEquals(null, journals.info(run.runId)?.purgedAt)

            quarantine.purge(run.runId)
            assertFalse(stray.exists())
            assertNotNull(journals.info(run.runId)?.purgedAt)
        }
    }

    @Test
    fun aZipLeftUnfinishedByAStoppedRunIsOfferedAsClutter() = runTest {
        TestFs().use { fs ->
            fs.text("Documents/Old project notes/a.txt", "a")
            fs.text("Documents/.Old project notes${ActionExecutor.PARTIAL_ZIP}", "PK partial")
            fs.text("Documents/.Unrelated${ActionExecutor.PARTIAL_ZIP}", "PK partial")
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings, TestEnv, File(fs.stateDir, "cache.tsv")).scan()
            val partial = report.junk.filter { it.path.endsWith(ActionExecutor.PARTIAL_ZIP) }
            assertEquals(listOf(fs.path("Documents/.Old project notes${ActionExecutor.PARTIAL_ZIP}")), partial.map { it.path })
            assertEquals(JunkCategory.STALE_DOWNLOADS, partial.single().category)
        }
    }
}
