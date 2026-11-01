package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataExecutor
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.core.appdata.AppTarget
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalSink
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.exec.TreeDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Undo of an app-folder run puts things back into `Android/data` and `obb` with the shell's rights, from a quarantine in
 * shared storage that any app with All-files access can write to. It puts back only what the run moved there.
 */
class AppFolderUndoTest {
    private class Recorder : JournalSink {
        val entries = ArrayList<JournalEntry>()
        override fun meta(key: String, value: String) = Unit
        override fun entry(entry: JournalEntry) {
            entries += entry
        }
    }

    private fun picked(fs: TestFs, rel: String, directory: Boolean): AppJunkItem {
        val f = File(fs.path(rel))
        val size = if (directory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
        val area = if (rel.startsWith("Android/obb/")) AppArea.OBB else AppArea.DATA
        val target = AppTarget(f.path, directory, size, f.lastModified())
        return AppJunkItem("picked:$rel", AppJunkKind.PICKED, "com.game", area, listOf(target), size, 1, Long.MAX_VALUE, "test")
    }

    private suspend fun quarantine(fs: TestFs, vararg items: AppJunkItem): Recorder {
        val journal = Recorder()
        val run = AppDataExecutor(fs.rootPath, journal, "run-1", null, emptySet()).execute("App folders", items.toList())
        assertEquals(run.messages.joinToString("\n"), 0, run.failed + run.skipped)
        return journal
    }

    private fun inQuarantine(journal: Recorder, rel: String): File =
        File(journal.entries.single { it.action == JournalAction.QUARANTINED && it.a.endsWith(rel) }.b)

    @Test
    fun aFolderSomethingWasAddedToInTheQuarantineStaysThere() = runTest {
        TestFs().use { fs ->
            fs.random("Android/data/com.game/files/levels/1.dat", 3000, 1)
            fs.random("Android/data/com.game/files/levels/2.dat", 3000, 2)
            val journal = quarantine(fs, picked(fs, "Android/data/com.game/files/levels", directory = true))
            val line = journal.entries.single { it.action == JournalAction.QUARANTINED }
            assertTrue(line.sha256, line.sha256!!.startsWith(TreeDigest.PREFIX))

            // Another app adds a file to the quarantined copy.
            val q = inQuarantine(journal, "files/levels")
            File(q, "3.dat").writeText("planted")
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(journal.entries)

            assertEquals(undo.messages.joinToString("\n"), 1, undo.skipped)
            assertTrue(undo.messages.single().contains(RollbackEngine.CHANGED_IN_QUARANTINE))
            assertFalse(File(fs.path("Android/data/com.game/files/levels")).exists())
            assertTrue(File(q, "1.dat").exists() && File(q, "3.dat").exists())
            // It is still in the quarantine, so the run stays undoable.
            assertEquals(1, undo.remaining)
        }
    }

    @Test
    fun aFolderChangedInsideInTheQuarantineStaysThere() = runTest {
        TestFs().use { fs ->
            fs.random("Android/data/com.game/files/levels/1.dat", 3000, 1)
            fs.random("Android/data/com.game/files/levels/deep/2.dat", 3000, 2)
            val journal = quarantine(fs, picked(fs, "Android/data/com.game/files/levels", directory = true))
            File(inQuarantine(journal, "files/levels"), "deep/2.dat").appendText("x")
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(journal.entries)
            assertEquals(1, undo.skipped)
            assertFalse(File(fs.path("Android/data/com.game/files/levels")).exists())
        }
    }

    @Test
    fun anUntouchedFolderGoesBackExactly() = runTest {
        TestFs().use { fs ->
            val one = fs.random("Android/data/com.game/files/levels/1.dat", 3000, 1).readBytes()
            val two = fs.random("Android/data/com.game/files/levels/deep/2.dat", 3000, 2).readBytes()
            val journal = quarantine(fs, picked(fs, "Android/data/com.game/files/levels", directory = true))
            assertFalse(File(fs.path("Android/data/com.game/files/levels")).exists())
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(journal.entries)
            assertEquals(undo.messages.joinToString("\n"), 0, undo.skipped + undo.failed)
            assertTrue(File(fs.path("Android/data/com.game/files/levels/1.dat")).readBytes().contentEquals(one))
            assertTrue(File(fs.path("Android/data/com.game/files/levels/deep/2.dat")).readBytes().contentEquals(two))
            assertEquals(0, undo.remaining)
        }
    }

    @Test
    fun aFileReplacedInTheQuarantineStaysThereAndAnUntouchedOneGoesBack() = runTest {
        TestFs().use { fs ->
            fs.random("Android/obb/com.game/main.1.com.game.obb", 4000, 3)
            fs.random("Android/obb/com.game/main.2.com.game.obb", 4000, 4)
            val journal = quarantine(
                fs,
                picked(fs, "Android/obb/com.game/main.1.com.game.obb", directory = false),
                picked(fs, "Android/obb/com.game/main.2.com.game.obb", directory = false),
            )
            inQuarantine(journal, "main.1.com.game.obb").writeText("something else")
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(journal.entries)
            assertEquals(1, undo.skipped)
            assertEquals(1, undo.restored)
            assertFalse(File(fs.path("Android/obb/com.game/main.1.com.game.obb")).exists())
            assertTrue(File(fs.path("Android/obb/com.game/main.2.com.game.obb")).exists())
        }
    }

    /** Journals written before 1.2.14 have no digest for a folder: it goes back as it did then. */
    @Test
    fun aFolderFromAnOlderJournalStillGoesBack() = runTest {
        TestFs().use { fs ->
            fs.random("Android/data/com.game/files/levels/1.dat", 3000, 1)
            val journal = quarantine(fs, picked(fs, "Android/data/com.game/files/levels", directory = true))
            val older = journal.entries.map { if (it.action == JournalAction.QUARANTINED) it.copy(sha256 = null) else it }
            val undo = RollbackEngine(fs.rootPath, null).rollbackEntries(older)
            assertEquals(0, undo.skipped)
            assertTrue(File(fs.path("Android/data/com.game/files/levels/1.dat")).exists())
        }
    }

    /** No two different trees read the same, whatever their names hold. */
    @Test
    fun theDigestTellsTreesApart() {
        TestFs().use { fs ->
            fs.text("a/x", "1")
            fs.text("b/x\n f 1 0", "1")
            fs.text("c/x", "12")
            val a = TreeDigest.of(File(fs.path("a")).toPath())
            assertEquals(a, TreeDigest.of(File(fs.path("a")).toPath()))
            assertTrue(a != TreeDigest.of(File(fs.path("b")).toPath()))
            assertTrue(a != TreeDigest.of(File(fs.path("c")).toPath()))
            assertEquals(null, TreeDigest.of(File(fs.path("a/x")).toPath()))
        }
    }
}
