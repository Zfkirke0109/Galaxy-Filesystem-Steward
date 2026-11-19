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

    /**
     * Emptying by age deletes what the journal records, and only inside the quarantine: a folder of the run replaced by a
     * link to DCIM must not lead it to the photo the journal's path names there.
     */
    @Test
    fun emptyingByAgeNeverDeletesThroughALinkedFolder() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            val run = quarantineRun(fs, journals, "DCIM/Camera/IMG_1.jpg", finishedDaysAgo = 10)
            // Undone by hand, and the run's DCIM folder swapped for a link to the real one.
            val inQuarantine = q.dirFor(run).resolve("DCIM/Camera/IMG_1.jpg").toFile()
            val photo = File(fs.path("DCIM/Camera/IMG_1.jpg"))
            inQuarantine.copyTo(photo)
            q.dirFor(run).resolve("DCIM").toFile().deleteRecursively()
            Files.createSymbolicLink(q.dirFor(run).resolve("DCIM"), File(fs.path("DCIM")).toPath())

            q.purgeExpired(retentionDays = 7)
            assertTrue(photo.exists())
        }
    }

    /**
     * A debug build installed beside the app has its own quarantine: emptying it, or putting back what no journal of its
     * own records, leaves the app's runs alone. They shared one, so each took the other's runs for runs without a journal.
     */
    @Test
    fun aDebugBuildNeverEmptiesTheAppsQuarantine() = runTest {
        TestFs().use { fs ->
            val appJournals = JournalStore(File(fs.stateDir, "journals"))
            val run = quarantineRun(fs, appJournals, "Download/a.apk", finishedDaysAgo = 1)
            val inAppQuarantine = QuarantineManager(PathGuard(fs.rootPath), appJournals).dirFor(run).resolve("Download/a.apk").toFile()
            assertTrue(inAppQuarantine.exists())
            try {
                SafetyPolicy.forBuild("com.galaxy.steward.debug")
                val debug = QuarantineManager(PathGuard(fs.rootPath), JournalStore(File(fs.stateDir, "debug-journals")))
                assertTrue(debug.orphans().isEmpty())
                debug.purgeAll()
                assertTrue(inAppQuarantine.exists())
            } finally {
                SafetyPolicy.forBuild("com.galaxy.steward")
            }
            assertEquals(SafetyPolicy.QUARANTINE_DIR, SafetyPolicy.quarantineDir)
        }
    }

    /**
     * History counts what an interrupted run left in the quarantine without a journal line while Put back may be moving
     * it: a file gone in between counts as nothing instead of ending the app. Here a link to nothing stands in for it.
     */
    @Test
    fun countingWhatNoJournalRecordsSurvivesFilesGoingMeanwhile() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            val run = journals.newId()
            com.galaxy.steward.core.exec.JournalWriter(journals.fileFor(run)).use { it.meta("started", "1") }
            journals.fileFor(run).setLastModified(System.currentTimeMillis() - 3_600_000)
            // Listed on its own, as a file beside a recorded one would be, and gone before it is measured.
            val dir = q.dirFor(run).also { Files.createDirectories(it) }
            val link = Files.createSymbolicLink(dir.resolve("gone.jpg"), dir.resolve("nowhere.jpg"))
            assertEquals(listOf(run), q.interruptedWithUnrecorded())
            // Counted as what is there (the link itself, never what it names), where it used to throw.
            val own = Files.readAttributes(link, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).size()
            assertEquals(own, q.unrecordedBytes(run))
        }
    }

    /**
     * A run still going (the weekly upkeep, say, while you open History) has items in the quarantine whose lines are
     * about to come: it isn't offered as interrupted, so Put back can't take back what it is recording.
     */
    @Test
    fun aRunStillGoingIsNotTakenForAnInterruptedOne() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            val run = journals.newId()
            com.galaxy.steward.core.exec.JournalWriter(journals.fileFor(run)).use { it.meta("started", "1") }
            File(q.dirFor(run).toFile(), "Download/moving.jpg").apply { parentFile.mkdirs() }.writeText("its line comes next")
            assertEquals(emptyList<String>(), q.interruptedWithUnrecorded())
            // Quiet for longer than a run takes between two lines: stopped.
            assertEquals(listOf(run), q.interruptedWithUnrecorded(now = System.currentTimeMillis() + QuarantineManager.ACTIVE_MS))
        }
    }

    /**
     * Put back takes only what no journal records, and tidies only the folders it emptied: a folder the journal records,
     * with an empty sub-folder, comes back with Undo as it went. Tidying the whole run's folder took its empty folders.
     */
    @Test
    fun puttingBackLeavesARecordedFolderAsItWent() = runTest {
        TestFs().use { fs ->
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val q = QuarantineManager(PathGuard(fs.rootPath), journals)
            fs.text("Download/Old/a.txt", "a")
            fs.dir("Download/Old/empty")
            fs.ageDirectories()
            val folder = File(fs.path("Download/Old"))
            val item = JunkItem("junk:old", JunkCategory.NEAR_COPIES, folder.path, true, 1, folder.lastModified(), "test")
            val run = ActionExecutor(fs.rootPath, journals).execute("Cleanup", "junk", listOf(item))
            assertEquals(1, run.quarantined)
            // Stopped just after a second move, before its line, a while ago.
            File(q.dirFor(run.runId).toFile(), "Download/Later/lost.jpg").apply { parentFile.mkdirs() }.writeText("not recorded")
            val file = journals.fileFor(run.runId)
            file.writeText(file.readLines().filterNot { it.startsWith("#finished") }.joinToString("\n", postfix = "\n"))
            file.setLastModified(System.currentTimeMillis() - 3_600_000)

            val put = q.unrecordedEntries(run.runId)
            assertEquals(1, RollbackEngine(fs.rootPath, null).rollbackEntries(put).restored)
            q.pruneUp(run.runId, put.map { it.b })
            assertFalse(q.dirFor(run.runId).resolve("Download/Later").toFile().exists())

            val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals(undo.messages.joinToString("\n"), 1, undo.restored)
            assertTrue(File(fs.path("Download/Old/empty")).isDirectory)
            assertEquals("a", File(fs.path("Download/Old/a.txt")).readText())
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
            // Stopped a while ago: a journal written in the last minutes may belong to a run still going.
            journals.fileFor(run.runId).setLastModified(System.currentTimeMillis() - 3_600_000)

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
