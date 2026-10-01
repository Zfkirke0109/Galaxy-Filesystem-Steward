package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Two folders of one name in different places, like the 9-30 phone's Documents/LogcatX and Reports/Diagnostics/LogcatX. */
class NameMergeTest {
    @Test
    fun foldersOfOneNameBecomeOneKeepingLayoutAndBothDifferentFiles() = runTest {
        TestFs().use { fs ->
            fs.random("Documents/LogcatX/2026-07-01.txt", 3000, 1)
            fs.random("Documents/LogcatX/shared.txt", 2000, 2)
            fs.random("Documents/LogcatX/clash.txt", 1000, 3)
            fs.random("Documents/Reports/Diagnostics/LogcatX/2026-08-01.txt", 4000, 4)
            fs.random("Documents/Reports/Diagnostics/LogcatX/shared.txt", 2000, 2)
            fs.random("Documents/Reports/Diagnostics/LogcatX/clash.txt", 1500, 5)
            fs.random("Documents/Reports/Diagnostics/LogcatX/old/2026-06-01.txt", 500, 6)
            // Common names don't mean the same thing, and projects keep their layout.
            fs.random("Documents/A/images/a.png", 100, 7)
            fs.random("Documents/B/images/b.png", 100, 8)
            fs.text("Documents/Code/Tool/.git/HEAD", "ref: refs/heads/main")
            fs.text("Documents/Code/Tool/LogcatX/x.txt", "code")
            fs.ageDirectories()

            val report = Steward(fs.rootPath, testSettings, TestEnv, null).scan()
            val merges = report.folderMerges.filter { it.sameName }
            assertEquals(1, merges.size)
            val m = merges.single()
            // Into the one already where diagnostics are filed, not the one loose in Documents.
            assertEquals(fs.path("Documents/Reports/Diagnostics/LogcatX"), m.target)
            assertEquals(fs.path("Documents/LogcatX"), m.source)
            assertEquals(1, m.commonFiles)
            assertEquals(2, m.uniqueFiles)
            assertFalse(m.defaultSelected)

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Merge", "dedupe", listOf(m))
            assertEquals(0, summary.failed)
            assertFalse(fs.exists("Documents/LogcatX"))
            val merged = "Documents/Reports/Diagnostics/LogcatX"
            assertTrue(fs.exists("$merged/2026-07-01.txt") && fs.exists("$merged/2026-08-01.txt") && fs.exists("$merged/old/2026-06-01.txt"))
            // The two different clash.txt files both stay.
            assertEquals(2, File(fs.root, merged).listFiles()!!.count { it.name.startsWith("clash") })
            assertTrue(fs.exists("Documents/Code/Tool/LogcatX/x.txt"))
        }
    }

    @Test
    fun copiesBesideTheirOriginalsMergeBackAndSmallCopiesAreDuplicates() = runTest {
        TestFs().use { fs ->
            // "images" is too common a name to merge across places, but a copy next to its original is plainly one.
            fs.random("Documents/Trip/images/a.jpg", 3000, 1)
            fs.random("Documents/Trip/images - Copy/a.jpg", 3000, 1)
            fs.random("Documents/Trip/images - Copy/b.jpg", 3000, 2)
            fs.random("Pictures/Wallpapers/w1.png", 4000, 3)
            fs.random("Pictures/Wallpapers (1)/w1.png", 4000, 3)
            fs.random("Pictures/Wallpapers (1)/w2.png", 4000, 6)
            // Small files aren't compared, except a copy beside its original at the same size.
            fs.random("Documents/Notes/todo.txt", 120, 4)
            fs.random("Documents/Notes/todo (1).txt", 120, 4)
            fs.random("Documents/Notes/other.txt", 120, 5)
            fs.random("Documents/Notes/other (1).txt", 121, 5)
            fs.ageDirectories()

            val report = Steward(fs.rootPath, testSettings.copy(minDuplicateBytes = 1L shl 20), TestEnv, null).scan()
            val merges = report.folderMerges.filter { it.sameName }.associateBy { it.source.removePrefix(fs.rootPath + "/") }
            assertEquals(setOf("Documents/Trip/images - Copy", "Pictures/Wallpapers (1)"), merges.keys)
            assertEquals(fs.path("Documents/Trip/images"), merges.getValue("Documents/Trip/images - Copy").target)
            assertEquals(1, merges.getValue("Documents/Trip/images - Copy").commonFiles)
            assertEquals(fs.path("Pictures/Wallpapers"), merges.getValue("Pictures/Wallpapers (1)").target)

            val small = report.duplicates.single()
            assertEquals(fs.path("Documents/Notes/todo.txt"), small.copies[small.keeperIndex].path)
            assertEquals(setOf(fs.path("Documents/Notes/todo (1).txt")), small.removals.map { it.path }.toSet())
        }
    }

    @Test
    fun filesLooseAtTheTopAreFiledForReview() = runTest {
        TestFs().use { fs ->
            val old = System.currentTimeMillis() - 30 * DAY_MS
            fs.random("manual.pdf", 2000, 1, mtime = old)
            fs.random("sheet.xlsx", 2000, 2, mtime = old)
            // Recent, unknown or hidden ones may be an app's own.
            fs.random("fresh.pdf", 2000, 3, mtime = System.currentTimeMillis())
            fs.random("state.dat", 2000, 4, mtime = old)
            fs.random(".config.json", 2000, 5, mtime = old)
            fs.ageDirectories()
            val moves = Steward(fs.rootPath, testSettings, TestEnv, null).scan().organize.filter { it.source.count { c -> c == '/' } == fs.rootPath.count { c -> c == '/' } + 1 }
            assertEquals(
                mapOf(fs.path("manual.pdf") to fs.path("Documents/Reference/manual.pdf"), fs.path("sheet.xlsx") to fs.path("Documents/Spreadsheets/sheet.xlsx")),
                moves.associate { it.source to it.destination },
            )
            assertTrue(moves.all { !it.defaultSelected && it.reason.startsWith("Loose at the top") })
        }
    }
}
