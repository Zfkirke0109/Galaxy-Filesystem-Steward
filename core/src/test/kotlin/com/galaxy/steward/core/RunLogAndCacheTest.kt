package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.RollbackSummary
import com.galaxy.steward.core.hash.HashCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RunLogAndCacheTest {
    @Test
    fun scanSummaryHasCountsSizesAndPhases() = runTest {
        TestFs().use { fs ->
            fs.random("DCIM/Camera/a.jpg", 4096, 1)
            fs.random("Download/a (1).jpg", 4096, 1)
            fs.text("Download/partial.crdownload", "x")
            val report = Steward(fs.rootPath, testSettings, TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan(null)
            val line = RunLog.scan(report, listOf(ScanPhase.MAPPING to 1_250L, ScanPhase.HASHING to 40L))
            assertTrue(line, line.startsWith("scan done in "))
            assertTrue(line, line.contains(": 3 files, "))
            assertTrue(line, line.contains("duplicate files 1 (4.0 KiB)"))
            assertTrue(line, line.contains("clutter 1 "))
            assertTrue(line, line.endsWith("; phases mapping 1.3 s, hashing 0.0 s"))
            assertFalse("no file names in the log", line.contains("a.jpg"))
        }
    }

    @Test
    fun runSummariesUseThousandsSeparatorsAndSeconds() {
        val applied = ExecutionSummary(
            runId = "r", completedItemIds = emptySet(), partialItemIds = emptySet(), moved = 12_345, deduped = 2, quarantined = 3,
            removedDirs = 4, skipped = 5, failed = 0, bytesFreed = 2L * MIB, bytesQuarantined = 0, bytesMoved = 0,
            messages = emptyList(), changedPaths = emptyList(), cleared = 6,
        )
        assertEquals(
            "run \"Autopilot\" (autopilot) done in 61.5 s: moved 12,345 (0 B), deduplicated 2, quarantined 3 (0 B), cleared 6, " +
                "removed 4 empty folders, freed 2.0 MiB; skipped 5, failed 0",
            RunLog.applied("Autopilot", "autopilot", applied, 61_500),
        )
        assertEquals(
            "undo \"Autopilot\" done in 0.3 s: restored 7, skipped 1, failed 0",
            RunLog.rolledBack("Autopilot", RollbackSummary(7, 1, 0, emptyList(), emptyList()), 250),
        )
    }

    @Test
    fun cachesSavedAtTheSameTimeLeaveOneWholeFile() {
        TestFs().use { fs ->
            val file = File(fs.stateDir, "hash-cache.tsv")
            val caches = (0 until 4).map { n ->
                HashCache(file).apply { repeat(2_000) { putFull("/storage/$n/file-$it", it.toLong(), 1L, "%064x".format(it)) } }
            }
            val start = CountDownLatch(1)
            caches.map { cache -> thread { start.await(); cache.save() } }.also { start.countDown() }.forEach { it.join() }

            val lines = file.readLines()
            assertTrue(lines.isNotEmpty())
            // Every line is one complete entry written by a single cache; no two saves were interleaved.
            assertTrue(lines.all { it.split('\t').size == 5 })
            assertEquals(1, lines.map { it.substringBefore("/file-") }.distinct().size)
            assertEquals(listOf(file.name), fs.stateDir.list()!!.toList())
        }
    }
}
