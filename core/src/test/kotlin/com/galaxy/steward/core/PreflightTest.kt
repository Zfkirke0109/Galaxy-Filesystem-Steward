package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.Preflight
import com.galaxy.steward.core.exec.PreflightResult
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.plan.DeleteDuplicateOp
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.PlanItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The dry run: what applying a scan's suggestions would find now, without changing anything. */
class PreflightTest {
    private fun tree(fs: TestFs) = fs.root.walkTopDown().map { Triple(it.path, it.length(), it.lastModified()) }.toList()

    /** A dry run that is checked to change nothing: every file and folder, with its size and date, is as before. */
    private suspend fun dry(fs: TestFs, items: List<PlanItem>, pins: List<String> = emptyList()): PreflightResult {
        val before = tree(fs)
        return Preflight.check(fs.rootPath, items, pins).also { assertEquals(before, tree(fs)) }
    }

    @Test
    fun aDryRunSaysWhatChangedSinceTheScanAndChangesNothing() = runTest {
        TestFs().use { fs ->
            val photo = Random(1).nextBytes(50_000)
            fs.file("Pictures/a.jpg", photo)
            fs.file("Download/a (1).jpg", photo)
            fs.text("Download/boarding pass.pdf", "BP")
            fs.text("Download/old.crdownload", "partial")
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings, TestEnv, File(fs.stateDir, "cache.tsv")).scan()
            val items = report.allItems().filter { it.defaultSelected }
            // The extra copy of the photo, the boarding pass to file and the stale download: one step each.
            assertEquals(3, items.sumOf { it.operations.size })

            val asScanned = dry(fs, items)
            assertEquals(asScanned.stale.toString(), 0, asScanned.skipped)
            assertEquals(3, asScanned.total)
            assertEquals(3, asScanned.ready)

            // Since the scan: the download finished (it's gone) and the boarding pass was edited.
            File(fs.path("Download/old.crdownload")).delete()
            File(fs.path("Download/boarding pass.pdf")).writeText("BP, edited")
            val now = dry(fs, items)
            assertEquals(mapOf(Preflight.CHANGED to 1), now.stale)
            // A step whose file is already gone is done, not skipped: a run counts it the same way.
            assertEquals(2, now.ready)

            // Pinning the kept copy's folder keeps that copy; removing the other one is still fine, as in a run.
            assertEquals(mapOf(Preflight.CHANGED to 1), dry(fs, items, listOf("Pictures")).stale)
            // Pinning Download stops every step there, the gone download's too (a run checks the pin first).
            assertEquals(mapOf(Preflight.PROTECTED to 3), dry(fs, items, listOf("Download")).stale)

            // The kept copy went: its duplicate stays.
            val kept = File(fs.path("Pictures/a.jpg"))
            val keptBytes = kept.readBytes()
            kept.delete()
            assertEquals(mapOf(Preflight.KEPT_MISSING to 1, Preflight.CHANGED to 1), dry(fs, items).stale)
            kept.writeBytes(keptBytes)

            // And what it said is what a run does: the edited boarding pass stays, the rest goes ahead.
            val run = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Run", "test", items)
            assertEquals(run.messages.joinToString("\n"), 0, run.failed)
            assertEquals(1, run.skipped)
            assertTrue(File(fs.path("Download/boarding pass.pdf")).exists())
            assertTrue(!File(fs.path("Download/a (1).jpg")).exists())
        }
    }

    /** Steps meet each other as a run takes them: a folder holding the copy kept for an earlier duplicate stays. */
    @Test
    fun aFolderHoldingACopyKeptEarlierInTheRunStays() = runTest {
        TestFs().use { fs ->
            val bytes = Random(2).nextBytes(10_000)
            fs.file("Download/Old stuff/a.jpg", bytes)
            fs.file("Pictures/a.jpg", bytes)
            fs.ageDirectories()
            val oldStuff = fs.path("Download/Old stuff")
            val dedupe = OptimizeItem(
                "dedupe", OptimizeKind.FLATTEN_WRAPPER, fs.path("Pictures"), "t", "d", 1, true,
                listOf(DeleteDuplicateOp(fs.path("Pictures/a.jpg"), fs.path("Download/Old stuff/a.jpg"), 10_000, Hashing.sha256(fs.path("Pictures/a.jpg")))),
            )
            val folder = File(oldStuff)
            val junk = JunkItem("junk", JunkCategory.STALE_DOWNLOADS, oldStuff, true, 10_000, folder.lastModified(), "old")
            assertEquals(mapOf(Preflight.KEPT_COPY to 1), dry(fs, listOf(dedupe, junk)).stale)
            // In the other order the folder goes first, and the duplicate stays because its kept copy is gone.
            assertEquals(mapOf(Preflight.KEPT_MISSING to 1), dry(fs, listOf(junk, dedupe)).stale)

            val run = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Run", "test", listOf(dedupe, junk))
            assertEquals(1, run.skipped)
            assertTrue(File(fs.path("Download/Old stuff/a.jpg")).exists())
        }
    }
}
