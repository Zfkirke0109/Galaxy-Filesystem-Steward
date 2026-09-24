package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.RollbackSummary
import com.galaxy.steward.core.hash.HashCache
import com.galaxy.steward.core.termux.TermuxGroup
import com.galaxy.steward.core.termux.TermuxItem
import com.galaxy.steward.core.termux.TermuxReport
import com.galaxy.steward.core.termux.TermuxRootfs
import com.galaxy.steward.core.termux.TermuxUsage
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
    fun termuxSummarySaysWhereTheSpaceGoesWithoutPaths() {
        val home = "/data/data/com.termux/files/home"
        val prefix = "/data/data/com.termux/files/usr"
        val report = TermuxReport(
            home = home, prefix = prefix, prootActive = false,
            usage = listOf(
                TermuxUsage("/data/data/com.termux/files", 30L * GIB),
                TermuxUsage(home, 12L * GIB),
                TermuxUsage(prefix, 18L * GIB),
                TermuxUsage("$home/llama.cpp", 4L * GIB),
            ),
            items = listOf(
                TermuxItem("apt-archives", "apt-archives", "Downloaded packages", TermuxGroup.SAFE, "$prefix/var/cache/apt/archives", 300L * MIB, 9, true, ""),
                TermuxItem("pycache", "pycache", "Python bytecode caches", TermuxGroup.DEV, home, 100L * MIB, 900, true, ""),
                TermuxItem("npm-cache", "npm-cache", "npm cache", TermuxGroup.DEV, "$home/.npm/_cacache", 924L * MIB, 5000, true, ""),
            ),
            rootfs = listOf(
                TermuxRootfs("$prefix/var/lib/proot-distro/installed-rootfs/debian", 8L * GIB, false),
                TermuxRootfs("$prefix/var/lib/proot-distro/installed-rootfs/ubuntu", 6L * GIB, false),
            ),
            largeFiles = emptyList(), warnings = emptyList(), unsafe = emptyList(),
        )
        val line = RunLog.termux(report, 148_400)
        assertEquals(
            "Termux audit done in 148.4 s: Termux uses 30.0 GiB (home 12.0 GiB, packages 4.0 GiB, 2 proot distros 14.0 GiB); " +
                "3 cleanable items, 1.3 GiB (safe 300 MiB, dev 1.0 GiB), 0 unsafe paths skipped",
            line,
        )
        assertFalse("no paths in the log", line.contains("llama") || line.contains("/data/"))
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
