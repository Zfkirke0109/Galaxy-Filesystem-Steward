package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.plan.MoveDirOp
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.OrganizeMove
import com.galaxy.steward.core.report.StorageReportText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * What a run and the report after it say matches what changed on disk. The 10-30 Autopilot run read "moved 4,
 * deduplicated 6" for a scan of 5 moves and 1 duplicate folder of 5 files: one move had found its file already filed.
 */
class RunAccountingTest {
    @Test
    fun aMoveThatFindsItsFileAlreadyFiledIsCountedAsSuch() = runTest {
        TestFs().use { fs ->
            val pdf = Random(21).nextBytes(5_000)
            fs.file("Download/manual.pdf", pdf)
            fs.file("Documents/PDFs/manual.pdf", pdf)
            fs.random("Download/Project-X/a.txt", 100, 22)
            fs.random("Download/Project-X/b.txt", 200, 23)
            fs.ageDirectories()
            val src = File(fs.path("Download/manual.pdf"))
            val file = move("organize:pdf", MoveFileOp(src.path, fs.path("Documents/PDFs/manual.pdf"), src.length(), src.lastModified()))
            val folder = move("organize:x", MoveDirOp(fs.path("Download/Project-X"), fs.path("Documents/Projects/Project-X"), true, bytes = 300))

            val s = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Organize", "organize", listOf(file, folder))

            assertFalse(fs.exists("Download/manual.pdf"))
            assertTrue(fs.exists("Documents/PDFs/manual.pdf") && fs.exists("Documents/Projects/Project-X/b.txt"))
            assertEquals(1, s.moved)
            assertEquals(300L, s.bytesMoved) // the folder went as one unit and still counts what it holds
            assertEquals(1, s.deduped)
            assertEquals(1, s.alreadyFiled)
            assertEquals(pdf.size.toLong(), s.bytesFreed)
            assertTrue(RunLog.applied("Organize", "organize", s, 0), RunLog.applied("Organize", "organize", s, 0).contains("deduplicated 1 (1 already at their destination)"))
        }
    }

    @Test
    fun theReportAfterARunSaysWhatTheScanFoundAndWhatIsLeft() = runTest {
        TestFs().use { fs ->
            val photo = Random(24).nextBytes(30_000)
            fs.file("DCIM/Camera/IMG_1.jpg", photo)
            fs.file("Download/IMG_1.jpg", photo)
            fs.ageDirectories()
            val scan = Steward(fs.rootPath, testSettings, TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan()
            assertEquals(1, scan.duplicates.size)

            val after = scan.without(scan.duplicates.map { it.id }.toSet())
            val text = StorageReportText.render(after, null, listOf("header"))
            assertTrue(text, text.contains(RunLog.scan(scan)) && RunLog.scan(scan).contains("duplicate files 1"))
            assertTrue(text, text.contains("Left after 1 applied since: ") && text.contains("since: ${RunLog.findings(after)}"))
            assertTrue(RunLog.findings(after).contains("duplicate files 0"))
        }
    }

    private fun move(id: String, op: com.galaxy.steward.core.plan.Operation) =
        OrganizeMove(id, "src", "dst", op is MoveDirOp, 0, 1, "test", "dst", true, listOf(op))
}
