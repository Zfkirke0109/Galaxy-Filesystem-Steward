package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.plan.DeleteDuplicateOp
import com.galaxy.steward.core.plan.FolderMerge
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.RemoveEmptyDirOp
import com.galaxy.steward.core.plan.ScanReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Folders that share most of their content (FolderAnalyzer.overlaps): the merges a scan offers must not contradict each
 * other or the scan's other suggestions, whatever subset of them is applied.
 */
class OverlapMergeTest {
    private val settings = testSettings.copy(minDuplicateFolderBytes = 1)

    private suspend fun scan(fs: TestFs): ScanReport = Steward(fs.rootPath, settings, TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan()

    /** [count] shared photos of 1.1 MB (six make 6.6 MB, over the 5 MiB floor) in each folder of [dirs], and one file of its own. */
    private fun sharedPhotos(fs: TestFs, dirs: List<String>, seed: Int = 100, count: Int = 6) {
        for (i in 0 until count) {
            val bytes = Random(seed + i).nextBytes(1_100_000)
            dirs.forEach { fs.file("$it/p$seed-$i.jpg", bytes) }
        }
        dirs.forEachIndexed { n, d -> fs.random("$d/own-$seed-$n.jpg", 2_000, seed * 10 + n) }
    }

    private fun assertNoContradictions(merges: List<FolderMerge>) {
        val sources = merges.map { it.source }
        val targets = merges.map { it.target }.toSet()
        assertEquals("a folder is merged away twice: $sources", sources.size, sources.toSet().size)
        assertTrue("a folder is merged away and merged into: $merges", sources.none { it in targets })
        val moved = merges.flatMap { m -> m.operations.filterIsInstance<MoveFileOp>().map { it.src } }
        val deleted = merges.flatMap { m -> m.operations.filterIsInstance<DeleteDuplicateOp>().map { it.path } }
        val kept = merges.flatMap { m -> m.operations.filterIsInstance<DeleteDuplicateOp>().map { it.canonical } }.toSet()
        assertEquals("a file is moved by two merges", moved.size, moved.toSet().size)
        assertEquals("a file is deleted by two merges", deleted.size, deleted.toSet().size)
        assertTrue("a merge deletes the copy another keeps", deleted.none { it in kept })
        assertTrue("a merge moves a file another deletes", moved.none { it in deleted })
    }

    /** Three folders with the same photos: both copies go into the best one, not one into the other and on again. */
    @Test
    fun threeCopiesMergeIntoTheBestFolderOnce() = runTest {
        TestFs().use { fs ->
            sharedPhotos(fs, listOf("Pictures/Holiday", "Documents/Old phone/Lisbon", "Download/Lisbon-2024"))
            fs.ageDirectories()
            val merges = scan(fs).folderMerges.filterNot { it.sameName }
            assertNoContradictions(merges)
            assertEquals(setOf(fs.path("Pictures/Holiday")), merges.map { it.target }.toSet())
            assertEquals(setOf(fs.path("Documents/Old phone/Lisbon"), fs.path("Download/Lisbon-2024")), merges.map { it.source }.toSet())

            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Merge", "merge", merges)
            assertEquals(0, summary.skipped)
            assertEquals(0, summary.failed)
            for (i in 0 until 6) assertTrue(fs.exists("Pictures/Holiday/p100-$i.jpg"))
            (0..2).forEach { assertTrue("own-$it", fs.exists("Pictures/Holiday/own-100-$it.jpg")) }
            assertFalse(fs.exists("Download/Lisbon-2024"))
            assertFalse(fs.exists("Documents/Old phone/Lisbon"))
        }
    }

    /**
     * A shares photos with B and B other photos with C: one scan merges B away or into, never both. B holds more of A's
     * photos than of C's, so on its own each pair qualifies: B into A, and C into B.
     */
    @Test
    fun aChainIsNotPlannedInOneScan() = runTest {
        TestFs().use { fs ->
            sharedPhotos(fs, listOf("Pictures/Holiday", "Documents/Lisbon"), seed = 100, count = 8)
            sharedPhotos(fs, listOf("Documents/Lisbon", "Download/Porto"), seed = 300)
            fs.ageDirectories()
            val merges = scan(fs).folderMerges.filterNot { it.sameName }
            assertNoContradictions(merges)
            assertEquals(1, merges.size)

            val before = fs.root.walkTopDown().filter { it.isFile }.map { it.readBytes().contentHashCode() }.toSet()
            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Merge", "merge", merges)
            assertEquals(0, summary.skipped)
            assertEquals(0, summary.failed)
            // Every distinct file still has a copy outside the quarantine.
            val after = fs.root.walkTopDown().filter { it.isFile }.map { it.readBytes().contentHashCode() }.toSet()
            assertEquals(before, after)
        }
    }

    /** Applying every suggestion a scan makes, merges and duplicate groups together, skips nothing and keeps a copy of each file. */
    @Test
    fun aMergeAndTheDuplicateGroupsOfItsFilesKeepTheSameCopy() = runTest {
        TestFs().use { fs ->
            // Two folders of one zone and depth: the older folder is the merge's target, but each of its photos is
            // newer than its twin in the other folder, which on its own would make the duplicate groups keep that one.
            val old = System.currentTimeMillis() - 90 * DAY_MS
            for (i in 0 until 6) {
                val bytes = Random(500 + i).nextBytes(1_100_000)
                fs.file("Documents/Alpha/p$i.jpg", bytes, mtime = old + 10 * DAY_MS)
                fs.file("Documents/Beta/p$i.jpg", bytes, mtime = old)
            }
            fs.random("Documents/Beta/own.jpg", 2_000, 7)
            fs.ageDirectories()
            File(fs.root, "Documents/Alpha").setLastModified(old - DAY_MS)
            File(fs.root, "Documents/Beta").setLastModified(old)
            val report = scan(fs)
            val merge = report.folderMerges.single { !it.sameName }
            assertEquals(fs.path("Documents/Alpha"), merge.target)
            val shared = report.duplicates.filter { g -> g.copies.any { it.path.startsWith(merge.source + "/") } }
            assertEquals(6, shared.size)
            shared.forEach { g -> assertTrue(g.keepReason, g.keeper.path.startsWith(merge.target + "/")) }

            val before = fs.root.walkTopDown().filter { it.isFile }.map { it.readBytes().contentHashCode() }.toSet()
            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("All", "apply", report.duplicates + merge)
            assertEquals(summary.messages.joinToString("\n"), 0, summary.skipped)
            assertEquals(0, summary.failed)
            assertFalse(fs.exists("Documents/Beta"))
            val after = fs.root.walkTopDown().filter { it.isFile }.map { it.readBytes().contentHashCode() }.toSet()
            assertEquals(before, after)
        }
    }

    /** Filing doesn't move what a merge takes care of, nor file anything into a folder a merge empties. */
    @Test
    fun filingLeavesAMergesFoldersToTheMerge() = runTest {
        TestFs().use { fs ->
            sharedPhotos(fs, listOf("Pictures/Holiday", "Download/Lisbon-2024"))
            fs.text("Download/Lisbon-2024/boarding pass.pdf", "BP")
            fs.ageDirectories()
            val report = scan(fs)
            val merge = report.folderMerges.single { !it.sameName }
            assertEquals(fs.path("Download/Lisbon-2024"), merge.source)
            report.organize.forEach { move ->
                assertFalse(move.source, move.source == merge.source || move.source.startsWith(merge.source + "/"))
                assertFalse(move.destination, move.destination.startsWith(merge.source + "/"))
            }
        }
    }

    /** A merge whose source keeps something (a sub-folder, a hidden file) leaves the folder without calling it a failure. */
    @Test
    fun aSourceWithASubfolderIsMergedWithoutASkip() = runTest {
        TestFs().use { fs ->
            sharedPhotos(fs, listOf("Pictures/Holiday", "Download/Lisbon-2024"))
            fs.random("Download/Lisbon-2024/raw/r1.dng", 3_000, 77)
            fs.ageDirectories()
            val merge = scan(fs).folderMerges.single { !it.sameName }
            assertTrue(merge.operations.none { it is RemoveEmptyDirOp && it.path == merge.source })
            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Merge", "merge", listOf(merge))
            assertEquals(summary.messages.joinToString("\n"), 0, summary.skipped)
            assertTrue(summary.completedItemIds.contains(merge.id))
            assertTrue(fs.exists("Download/Lisbon-2024/raw/r1.dng"))
        }
    }
}
