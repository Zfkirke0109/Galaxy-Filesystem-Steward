package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.learn.ScanMemory
import com.galaxy.steward.core.learn.StoragePoint
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.plan.Severity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What the steward remembers between scans: the moves you made yourself, and how storage grows. */
class ScanMemoryTest {
    private val settings = testSettings.copy(minDuplicateBytes = 1L shl 40)

    private fun layout(fs: TestFs) {
        for (d in listOf("tv-manual", "fridge-warranty", "router-manual")) fs.random("Documents/Reference/$d.pdf", 800, d.length)
        for (n in listOf("birds-of-europe", "tree-guide")) fs.random("Documents/Nature/$n.pdf", 700, n.length)
        for (g in listOf("Bionicle Heroes (USA)", "Jak and Daxter (USA)", "Sly Cooper (USA)")) fs.random("Documents/Games/PS2/$g.iso", 3000, g.length)
        fs.random("Download/zebra-migration.pdf", 700, 62)
        fs.random("Documents/Stuff/Ratchet Clank (USA)/Ratchet Clank (USA).iso", 3000, 70)
        fs.random("Documents/Stuff/Ratchet Clank (USA)/Ratchet Clank (USA).cue", 100, 71)
        fs.random("Documents/Stuff/notes.txt", 50, 72)
        fs.ageDirectories()
    }

    private suspend fun scan(fs: TestFs, memory: ScanMemory?): ScanReport = Steward(fs.rootPath, settings, TestEnv, null, memory).scan()

    private fun move(fs: TestFs, from: String, to: String) {
        val target = File(fs.root, to)
        target.parentFile.mkdirs()
        assertTrue(File(fs.root, from).renameTo(target))
    }

    @Test
    fun whatYouMoveYourselfTeachesAndStaysPut() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val memory = ScanMemory(File(fs.stateDir, "memory"), journals)
            val first = scan(fs, memory)
            assertTrue(first.insights.none { it.title.startsWith("You moved") })

            // You file the zebra PDF with the nature guides, and the PS2 game folder with the others.
            move(fs, "Download/zebra-migration.pdf", "Documents/Nature/zebra-migration.pdf")
            move(fs, "Documents/Stuff/Ratchet Clank (USA)", "Documents/Games/PS2/Ratchet Clank (USA)")
            fs.random("Download/zebra-herds-of-africa.pdf", 650, 80)
            fs.ageDirectories()
            val yours = memory.movesSince(com.galaxy.steward.core.scan.TreeScanner(fs.rootPath, settings).scan { _, _, _, _ -> })
            assertEquals(setOf(fs.path("Documents/Nature/zebra-migration.pdf"), fs.path("Documents/Games/PS2/Ratchet Clank (USA)")), yours.paths)
            assertEquals(3, yours.files)

            val second = scan(fs, memory)
            assertTrue(second.insights.any { it.title == "You moved 3 files yourself since the last scan" })
            // The next zebra PDF follows yours; without the memory of your move, its type would decide.
            val zebra = second.organize.single { it.source.endsWith("zebra-herds-of-africa.pdf") }
            assertEquals(fs.path("Documents/Nature/zebra-herds-of-africa.pdf"), zebra.destination)
            val forgetful = scan(fs, null).organize.single { it.source.endsWith("zebra-herds-of-africa.pdf") }
            assertEquals(fs.path("Documents/Reference/zebra-herds-of-africa.pdf"), forgetful.destination)
            // Nothing you just placed is suggested to move again.
            assertTrue(second.organize.none { it.source.contains("zebra-migration") || it.source.contains("Ratchet") })

            // A third scan: those moves are old news.
            assertTrue(scan(fs, memory).insights.none { it.title.startsWith("You moved") })
        }
    }

    @Test
    fun theStewardsOwnMovesAndUndoesAreNotYours() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val memory = ScanMemory(File(fs.stateDir, "memory"), journals)
            val report = scan(fs, memory)
            val zebra = report.organize.single { it.source.endsWith("zebra-migration.pdf") }
            val summary = ActionExecutor(fs.rootPath, journals).execute("Organize", "organize", listOf(zebra))
            assertEquals(1, summary.moved)
            assertTrue(scan(fs, memory).insights.none { it.title.startsWith("You moved") })

            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertTrue(fs.exists("Download/zebra-migration.pdf"))
            assertTrue(scan(fs, memory).insights.none { it.title.startsWith("You moved") })
        }
    }

    @Test
    fun aCopyIsNotAMove() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val memory = ScanMemory(File(fs.stateDir, "memory"), null)
            scan(fs, memory)
            File(fs.root, "Download/zebra-migration.pdf").copyTo(File(fs.root, "Documents/Nature/zebra-migration.pdf"))
            val tree = com.galaxy.steward.core.scan.TreeScanner(fs.rootPath, settings).scan { _, _, _, _ -> }
            assertTrue(memory.movesSince(tree).paths.isEmpty())
        }
    }

    @Test
    fun storageGrowthIsReportedWithItsPace() {
        val day = DAY_MS
        val gib = 1L shl 30
        val t0 = 1_790_000_000_000L
        val history = listOf(
            StoragePoint(t0, 100 * gib, 40 * gib, 256 * gib, mapOf("Download" to 10 * gib, "Movies" to 30 * gib)),
            StoragePoint(t0 + 5 * day, 102 * gib, 38 * gib, 256 * gib, mapOf("Download" to 11 * gib, "Movies" to 31 * gib)),
        )
        val now = StoragePoint(t0 + 10 * day, 110 * gib, 30 * gib, 256 * gib, mapOf("Download" to 18 * gib, "Movies" to 32 * gib))
        val insight = ScanMemory.growthInsight(history, now)!!
        assertEquals("Storage grew 10.0 GiB in 10 days", insight.title)
        assertTrue(insight.detail, "Download (+8.0 GiB)" in insight.detail && "lasts about 30 days" in insight.detail)
        assertEquals(Severity.WARNING, insight.severity)
        // Too recent to compare, or nothing grew: nothing to say.
        assertNull(ScanMemory.growthInsight(history.takeLast(1), now.copy(time = t0 + 6 * day)))
        assertNull(ScanMemory.growthInsight(history, now.copy(scanned = 100 * gib, free = 40 * gib)))

        val dir = java.nio.file.Files.createTempDirectory("memory").toFile()
        try {
            val memory = ScanMemory(dir)
            (history + now).forEach(memory::record)
            assertEquals(history + now, memory.history())
        } finally {
            dir.deleteRecursively()
        }
    }
}
