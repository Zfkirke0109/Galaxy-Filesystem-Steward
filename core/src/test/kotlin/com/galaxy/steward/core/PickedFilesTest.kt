package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.ExecutorOptions
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.Preflight
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.pickedFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The storage map lists the largest files (on the 11-19 phone, 14.6 GiB of videos in Movies and a 3.2 GiB zip in
 * Download), and nothing could be done from there. Picked ones go to the quarantine, checked like any clutter, and
 * Undo brings them back.
 */
class PickedFilesTest {
    @Test
    fun picksGoToTheQuarantineAndComeBackWithUndo() = runTest {
        TestFs().use { fs ->
            val video = fs.random("Movies/long.mp4", 900_000, 1)
            val videoBytes = video.readBytes()
            fs.random("Download/Compressor/build-everything.zip", 700_000, 2)
            fs.random("Documents/small.txt", 1_000, 3)
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings, TestEnv, null).scan()
            assertEquals(fs.path("Movies/long.mp4"), report.summary.largestFiles.first().path)

            val items = pickedFiles(report.summary.largestFiles.take(2))
            assertTrue(items.all { it.category == JunkCategory.PICKED_FILES && !it.defaultSelected })
            assertEquals(0, Preflight.check(fs.rootPath, items).skipped)

            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Files you picked", "picked", items)
            assertEquals(run.messages.joinToString("\n"), 2, run.quarantined)
            assertEquals(1_600_000L, run.bytesQuarantined)
            assertFalse(video.exists())
            // The list no longer offers what went.
            val after = report.withoutFiles(run.changedPaths)
            assertEquals(listOf(fs.path("Documents/small.txt")), after.summary.largestFiles.map { it.path })

            val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals(undo.messages.joinToString("\n"), 0, undo.failed + undo.skipped)
            assertTrue(video.readBytes().contentEquals(videoBytes))
        }
    }

    /** A pick in a folder you pinned since stays where it is: the run checks pins again before each step. */
    @Test
    fun aPickInAPinnedFolderStays() = runTest {
        TestFs().use { fs ->
            fs.random("Movies/keep/long.mp4", 900_000, 1)
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings, TestEnv, null).scan()
            val items = pickedFiles(report.summary.largestFiles.take(1))
            assertEquals(mapOf(Preflight.PROTECTED to 1), Preflight.check(fs.rootPath, items, listOf("Movies/keep")).stale)
            val run = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals")), ExecutorOptions(protectedFolders = listOf("Movies/keep")))
                .execute("Files you picked", "picked", items)
            assertEquals(1, run.skipped)
            assertTrue(fs.exists("Movies/keep/long.mp4"))
        }
    }
}
