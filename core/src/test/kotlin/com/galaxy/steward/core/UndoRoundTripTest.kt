package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.PlanItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Undo puts every file back where it was, with its content and date, and every folder it removed: checked over the
 * whole tree, not a few paths. Before, merges, flattening and folder duplicates were applied in tests but never undone.
 */
class UndoRoundTripTest {
    /** Relative path to size, SHA-256 and modification time of every file, and every folder, outside the steward's own. */
    private fun snapshot(fs: TestFs): Pair<Map<String, Triple<Long, String, Long>>, Set<String>> {
        val files = HashMap<String, Triple<Long, String, Long>>()
        val dirs = HashSet<String>()
        fs.root.walkTopDown().onEnter { it.name != SafetyPolicy.STEWARD_DIR }.forEach { f ->
            val rel = f.relativeTo(fs.root).path
            if (rel.isEmpty()) return@forEach
            if (f.isDirectory) dirs += rel else files[rel] = Triple(f.length(), Hashing.sha256(f.path), f.lastModified())
        }
        return files to dirs
    }

    private suspend fun roundTrip(fs: TestFs, items: List<PlanItem>, settings: StewardSettings = testSettings) {
        val before = snapshot(fs)
        val journals = JournalStore(File(fs.stateDir, "journals"))
        val run = ActionExecutor(fs.rootPath, journals).execute("Run", "test", items)
        assertEquals(run.messages.joinToString("\n"), 0, run.failed)
        val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
        assertEquals(undo.messages.joinToString("\n"), 0, undo.failed + undo.skipped)
        assertEquals(0, undo.remaining)
        val after = snapshot(fs)
        assertEquals(before.first, after.first)
        assertEquals(before.second, after.second)
    }

    private suspend fun scan(fs: TestFs, settings: StewardSettings = testSettings) = Steward(fs.rootPath, settings, TestEnv, null).scan()

    @Test
    fun aFolderMergeIsUndoneExactly() = runTest {
        TestFs().use { fs ->
            for (i in 0 until 6) {
                val bytes = Random(100 + i).nextBytes(1_100_000)
                fs.file("Pictures/Trips/p$i.jpg", bytes)
                fs.file("Download/Trips/p$i.jpg", bytes)
            }
            fs.random("Download/Trips/unique.jpg", 1_000, 999)
            fs.ageDirectories()
            val merge = scan(fs, testSettings.copy(minDuplicateFolderBytes = 1)).folderMerges.first { !it.sameName }
            roundTrip(fs, listOf(merge))
        }
    }

    @Test
    fun aSameNameMergeIsUndoneExactly() = runTest {
        TestFs().use { fs ->
            fs.random("Documents/LogcatX/2026-07-01.txt", 3000, 1)
            fs.random("Documents/LogcatX/shared.txt", 2000, 2)
            fs.random("Documents/LogcatX/clash.txt", 1000, 3)
            fs.random("Documents/Reports/Diagnostics/LogcatX/2026-08-01.txt", 4000, 4)
            fs.random("Documents/Reports/Diagnostics/LogcatX/shared.txt", 2000, 2)
            fs.random("Documents/Reports/Diagnostics/LogcatX/clash.txt", 1500, 5)
            fs.ageDirectories()
            val merge = scan(fs).folderMerges.single { it.sameName }
            roundTrip(fs, listOf(merge))
        }
    }

    @Test
    fun flatteningIsUndoneExactly() = runTest {
        TestFs().use { fs ->
            fs.text("Download/Project-X-main/Project-X-main/readme.txt", "x")
            fs.text("Download/Project-X-main/Project-X-main/docs/guide.txt", "y")
            fs.ageDirectories()
            val flatten = scan(fs).optimize.filter { it.kind == OptimizeKind.FLATTEN_WRAPPER }
            assertEquals(1, flatten.size)
            roundTrip(fs, flatten)
        }
    }

    /** A duplicate folder is deleted for good file by file; undo copies each back from the kept folder. */
    @Test
    fun aDuplicateFolderIsUndoneExactly() = runTest {
        TestFs().use { fs ->
            for (base in listOf("Documents/Trip", "Download/Trip backup")) {
                fs.random("$base/day1/a.jpg", 40_000, 10)
                fs.random("$base/day1/b.jpg", 40_000, 11)
                fs.random("$base/day2/c.jpg", 40_000, 12)
                fs.text("$base/notes.txt", "hello")
            }
            fs.ageDirectories()
            val group = scan(fs).folderDuplicates.single()
            roundTrip(fs, listOf(group))
        }
    }
}
