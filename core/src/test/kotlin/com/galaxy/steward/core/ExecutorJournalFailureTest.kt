package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataExecutor
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.core.appdata.AppTarget
import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.ExecutorOptions
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalBroken
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalOutput
import com.galaxy.steward.core.exec.JournalSink
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.JournalWriter
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.DuplicateCopy
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.OrganizeMove
import com.galaxy.steward.core.plan.PackDirOp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * A journal that can't be written (the phone is full) stops the run, and whatever the last step had changed is put back:
 * a change nobody could undo, or an item in the quarantine with no line, would be lost. These paths could not be tested
 * before the journal could be swapped for one that fails on purpose.
 */
class ExecutorJournalFailureTest {
    /** A real journal that fails, as a full disk would, on the first entry [failOn] picks; [written] is what got in. */
    private class FailingJournal(file: File, private val failOn: (JournalEntry) -> Boolean) : JournalOutput {
        private val real = JournalWriter(file)
        val written = ArrayList<JournalEntry>()
        private var failed = false

        override fun meta(key: String, value: String) = real.meta(key, value)

        override fun entry(entry: JournalEntry) {
            if (!failed && failOn(entry)) {
                failed = true
                throw IOException("No space left on device")
            }
            real.entry(entry)
            written += entry
        }

        override fun close() = real.close()
    }

    /** Files in the quarantine (the steward's folder also holds a .nomedia marker, which isn't one). */
    private fun quarantineFiles(fs: TestFs) =
        fs.root.walkTopDown().filter { it.isFile && it.path.contains("/${SafetyPolicy.STEWARD_DIR}/${SafetyPolicy.QUARANTINE_DIR}/") }.toList()

    @Test
    fun aMoveWhoseLineCantBeWrittenGoesBack() = runTest {
        TestFs().use { fs ->
            val f = fs.text("Download/boarding pass.pdf", "BP")
            fs.dir("Documents/Personal/Travel")
            fs.ageDirectories()
            val dst = fs.path("Documents/Personal/Travel/boarding pass.pdf")
            val move = OrganizeMove("m", f.path, dst, false, 2, 1, "t", "Documents/Personal/Travel", true, listOf(MoveFileOp(f.path, dst, 2, f.lastModified())))
            var journal: FailingJournal? = null
            val executor = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir), ExecutorOptions()) { file ->
                FailingJournal(file) { it.action == JournalAction.MOVED }.also { journal = it }
            }
            val error = runCatching { executor.execute("Organize", "organize", listOf(move)) }.exceptionOrNull()
            assertTrue("$error", error is JournalBroken)
            assertTrue(fs.exists("Download/boarding pass.pdf"))
            assertFalse(fs.exists("Documents/Personal/Travel/boarding pass.pdf"))
            // Undo of what did get in finds nothing to do and touches nothing.
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(journal!!.written)
            assertEquals(0, undo.restored)
            assertTrue(fs.exists("Download/boarding pass.pdf"))
        }
    }

    /** The line written ahead fails: nothing has moved yet, and nothing does. */
    @Test
    fun nothingMovesWhenTheLineAheadCantBeWritten() = runTest {
        TestFs().use { fs ->
            val f = fs.text("Download/app-old.apk", "old")
            fs.ageDirectories()
            val item = JunkItem("j", JunkCategory.OLD_INSTALLERS, f.path, false, f.length(), f.lastModified(), "t")
            val executor = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir), ExecutorOptions()) { file ->
                FailingJournal(file) { it.action.startsWith(JournalAction.PENDING) }
            }
            val error = runCatching { executor.execute("Cleanup", "junk", listOf(item)) }.exceptionOrNull()
            assertTrue("$error", error is JournalBroken)
            assertTrue(f.exists())
            assertTrue(quarantineFiles(fs).isEmpty())
        }
    }

    @Test
    fun aDuplicateQuarantinedWithoutItsLineComesBack() = runTest {
        TestFs().use { fs ->
            val bytes = Random(3).nextBytes(5_000)
            fs.file("Documents/a.jpg", bytes)
            fs.file("Download/a.jpg", bytes)
            fs.ageDirectories()
            val sha = Hashing.sha256(fs.path("Documents/a.jpg"))
            val group = DuplicateGroup(
                "d", sha, bytes.size.toLong(),
                listOf(
                    DuplicateCopy(fs.path("Documents/a.jpg"), bytes.size.toLong(), File(fs.path("Documents/a.jpg")).lastModified(), Zone.USER_MANAGED),
                    DuplicateCopy(fs.path("Download/a.jpg"), bytes.size.toLong(), File(fs.path("Download/a.jpg")).lastModified(), Zone.USER_MANAGED),
                ),
                0, "t",
            )
            val executor = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir), ExecutorOptions(quarantineDuplicates = true)) { file ->
                FailingJournal(file) { it.action == JournalAction.DEDUPED_QUARANTINED }
            }
            val error = runCatching { executor.execute("Duplicates", "dedupe", listOf(group)) }.exceptionOrNull()
            assertTrue("$error", error is JournalBroken)
            assertTrue(fs.exists("Download/a.jpg"))
            assertTrue(quarantineFiles(fs).isEmpty())
        }
    }

    private fun coldFolder(fs: TestFs): OptimizeItem {
        (0 until 4).forEach { fs.random("Documents/Old project/f$it.txt", 2_000 + it, 70 + it) }
        fs.ageDirectories()
        val dir = fs.path("Documents/Old project")
        val newest = File(dir).listFiles()!!.maxOf { it.lastModified() }
        return OptimizeItem("p", OptimizeKind.PACK_COLD_FOLDER, dir, "Old project", "", 4, true, listOf(PackDirOp(dir, "$dir.zip", newest)))
    }

    @Test
    fun aZipWhoseLineCantBeWrittenIsRemovedAndTheFolderStays() = runTest {
        TestFs().use { fs ->
            val pack = coldFolder(fs)
            val executor = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir), ExecutorOptions()) { file ->
                FailingJournal(file) { it.action == JournalAction.PACKED }
            }
            val error = runCatching { executor.execute("Pack", "optimize", listOf(pack)) }.exceptionOrNull()
            assertTrue("$error", error is JournalBroken)
            assertFalse(fs.exists("Documents/Old project.zip"))
            assertEquals(4, File(fs.path("Documents/Old project")).listFiles()!!.size)
            assertTrue(fs.root.walkTopDown().none { it.name.endsWith(ActionExecutor.PARTIAL_ZIP) })
        }
    }

    /** The zip is journaled, the folder's move into the quarantine is not: the folder comes back, and Undo removes the zip. */
    @Test
    fun aPackStoppedBeforeItsFolderMovedLeavesTheFolderAndAnUndoableZip() = runTest {
        TestFs().use { fs ->
            val pack = coldFolder(fs)
            var journal: FailingJournal? = null
            val executor = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir), ExecutorOptions()) { file ->
                FailingJournal(file) { it.action == JournalAction.QUARANTINED }.also { journal = it }
            }
            val error = runCatching { executor.execute("Pack", "optimize", listOf(pack)) }.exceptionOrNull()
            assertTrue("$error", error is JournalBroken)
            assertEquals(4, File(fs.path("Documents/Old project")).listFiles()!!.size)
            assertTrue(fs.exists("Documents/Old project.zip"))
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(journal!!.written)
            assertEquals(1, undo.restored)
            assertFalse(fs.exists("Documents/Old project.zip"))
            assertEquals(4, File(fs.path("Documents/Old project")).listFiles()!!.size)
        }
    }

    /** Something new appears at the old place right after the move: the moved item is journaled anyway, and Undo brings it back beside. */
    @Test
    fun aMoveIsJournaledEvenWhenItsPlaceIsTakenAtOnce() = runTest {
        TestFs().use { fs ->
            val f = fs.text("Download/notes.txt", "mine")
            fs.ageDirectories()
            val item = JunkItem("j", JunkCategory.OLD_INSTALLERS, f.path, false, f.length(), f.lastModified(), "t")
            val journals = JournalStore(fs.stateDir)
            val executor = ActionExecutor(fs.rootPath, journals, ExecutorOptions()) { file ->
                // An app writes a new notes.txt the moment the old one has gone.
                FailingJournal(file) { e ->
                    if (e.action == JournalAction.QUARANTINED) File(fs.path("Download/notes.txt")).writeText("new")
                    false
                }
            }
            val run = executor.execute("Cleanup", "junk", listOf(item))
            assertEquals(1, run.quarantined)
            assertEquals(1, run.reasons[ActionExecutor.REAPPEARED])
            RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals("new", File(fs.path("Download/notes.txt")).readText())
            assertEquals("mine", File(fs.path("Download/notes (restored).txt")).readText())
        }
    }

    /** The helper's app-folder runs: the line after the move fails, so the item goes back before the run stops. */
    @Test
    fun anAppFolderMoveWhoseLineCantBeWrittenGoesBack() = runTest {
        TestFs().use { fs ->
            val obb = fs.random("Android/obb/com.game/main.1.com.game.obb", 4000, 31)
            val sink = object : JournalSink {
                override fun meta(key: String, value: String) = Unit
                override fun entry(entry: JournalEntry) {
                    if (entry.action == JournalAction.QUARANTINED) throw IOException("No space left on device")
                }
            }
            val target = AppTarget(obb.path, false, obb.length(), obb.lastModified())
            val item = AppJunkItem("app:obb", AppJunkKind.OBSOLETE_OBB, "com.game", AppArea.OBB, listOf(target), obb.length(), 1, Long.MAX_VALUE, "t")
            val error = runCatching { AppDataExecutor(fs.rootPath, sink, "run-1", null, emptySet()).execute("App folders", listOf(item)) }.exceptionOrNull()
            assertTrue("$error", error is JournalBroken)
            assertTrue(obb.exists())
            assertTrue(quarantineFiles(fs).isEmpty())
        }
    }
}
