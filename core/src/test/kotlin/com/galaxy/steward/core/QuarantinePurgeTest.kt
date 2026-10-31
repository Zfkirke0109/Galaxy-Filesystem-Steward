package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.QuarantineManager
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Emptying the quarantine is the one delete that can't be undone, and the weekly upkeep does it unattended: only what
 * a run of the steward put there goes, and never anything a link would lead it to.
 */
class QuarantinePurgeTest {
    private fun junk(fs: TestFs, rel: String) = File(fs.path(rel)).let {
        JunkItem("junk:$rel", JunkCategory.OLD_INSTALLERS, it.path, false, it.length(), it.lastModified(), "test")
    }

    private suspend fun quarantineRun(fs: TestFs, journals: JournalStore, rel: String, finishedDaysAgo: Int): String {
        fs.text(rel, "old $rel")
        val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(junk(fs, rel)))
        assertEquals(1, run.quarantined)
        journals.appendMeta(run.runId, "finished", (System.currentTimeMillis() - finishedDaysAgo * DAY_MS).toString())
        return run.runId
    }

    @Test
    fun emptyingByAgeTakesOnlyOldRuns() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            val old = quarantineRun(fs, journals, "Download/a.apk", finishedDaysAgo = 10)
            val recent = quarantineRun(fs, journals, "Download/b.apk", finishedDaysAgo = 1)
            // From before a reinstall: no journal, never emptied by age.
            val orphan = File(q.dirFor("20250101-000000-abcd").toFile(), "Download/c.apk").apply {
                parentFile.mkdirs()
                writeText("orphan")
            }

            assertEquals(0L, q.purgeExpired(retentionDays = 0))
            val freed = q.purgeExpired(retentionDays = 7)
            assertEquals("old Download/a.apk".length.toLong(), freed)
            assertNotNull(journals.info(old)?.purgedAt)
            assertNull(journals.info(recent)?.purgedAt)
            assertTrue(q.sizeOf(recent) > 0)
            assertTrue(orphan.exists())
        }
    }

    /** A link in a run's quarantine is removed, never what it leads to. */
    @Test
    fun aLinkInTheQuarantineIsRemovedNotItsTarget() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            val run = quarantineRun(fs, journals, "Download/a.apk", finishedDaysAgo = 1)
            val photo = fs.text("DCIM/Camera/IMG_1.jpg", "a photo")
            Files.createSymbolicLink(q.dirFor(run).resolve("link"), File(fs.path("DCIM")).toPath())
            q.purge(run)
            assertTrue(photo.exists())
            assertFalse(Files.exists(q.dirFor(run).resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }

    /** A quarantine folder that is a link (to the storage root, say) is not the quarantine: emptying it does nothing. */
    @Test
    fun aLinkedQuarantineIsNeverEmptied() = runTest {
        TestFs().use { fs ->
            val photo = fs.text("DCIM/Camera/IMG_1.jpg", "a photo")
            val steward = File(fs.root, SafetyPolicy.STEWARD_DIR).apply { mkdirs() }
            Files.createSymbolicLink(File(steward, SafetyPolicy.QUARANTINE_DIR).toPath(), fs.root.toPath())
            val q = QuarantineManager(PathGuard(fs.rootPath), JournalStore(File(fs.stateDir, "journals")))
            assertEquals(emptyList<String>(), q.runsWithContent())
            assertEquals(0L, q.purgeAll())
            assertEquals(0L, q.purge("DCIM"))
            assertTrue(photo.exists())
        }
    }

    /** Something no journal records stays through Undo and emptying by age, and Put back returns it. */
    @Test
    fun whatNoJournalRecordsCanBePutBack() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            fs.text("Download/a.apk", "recorded")
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(junk(fs, "Download/a.apk")))
            // The app was stopped just after a second move, before its line: an interrupted run.
            File(q.dirFor(run.runId).toFile(), "Download/lost.jpg").writeText("not recorded")
            journals.fileFor(run.runId).writeText(journals.fileFor(run.runId).readLines().filterNot { it.startsWith("#finished") }.joinToString("\n", postfix = "\n"))

            val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals(1, undo.restored)
            assertEquals(1, undo.remaining)
            assertNull(journals.info(run.runId)?.rolledBackAt)
            assertEquals(listOf(run.runId), q.interruptedWithUnrecorded())

            val putBack = RollbackEngine(fs.rootPath, null).rollbackEntries(q.unrecordedEntries(run.runId))
            assertEquals(1, putBack.restored)
            assertEquals("not recorded", File(fs.path("Download/lost.jpg")).readText())
            assertEquals(emptyList<String>(), q.interruptedWithUnrecorded())
        }
    }

    /** A journal that can't be read says nothing about the run: Undo fails instead of marking it undone. */
    @Test
    fun anUnreadableJournalIsNotUndone() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            fs.text("Download/a.apk", "old")
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(junk(fs, "Download/a.apk")))
            val file = journals.fileFor(run.runId)
            file.delete()
            file.mkdirs() // unreadable as a file
            val error = runCatching { RollbackEngine(fs.rootPath, journals).rollback(run.runId) }.exceptionOrNull()
            assertTrue("$error", error is IOException)
            assertTrue(QuarantineManager(PathGuard(fs.rootPath), journals).sizeOf(run.runId) > 0)
        }
    }
}
