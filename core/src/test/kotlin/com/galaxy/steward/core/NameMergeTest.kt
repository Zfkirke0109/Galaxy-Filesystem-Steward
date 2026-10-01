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
            assertEquals(fs.path("Documents/LogcatX"), m.target)
            assertEquals(fs.path("Documents/Reports/Diagnostics/LogcatX"), m.source)
            assertEquals(1, m.commonFiles)
            assertEquals(3, m.uniqueFiles)
            assertFalse(m.defaultSelected)

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Merge", "dedupe", listOf(m))
            assertEquals(0, summary.failed)
            assertFalse(fs.exists("Documents/Reports/Diagnostics/LogcatX"))
            assertTrue(fs.exists("Documents/LogcatX/2026-08-01.txt") && fs.exists("Documents/LogcatX/old/2026-06-01.txt"))
            // The two different clash.txt files both stay.
            assertEquals(2, File(fs.root, "Documents/LogcatX").listFiles()!!.count { it.name.startsWith("clash") })
            assertTrue(fs.exists("Documents/Code/Tool/LogcatX/x.txt"))
        }
    }
}
