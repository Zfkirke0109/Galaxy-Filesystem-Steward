package com.galaxy.steward.core

import com.galaxy.steward.core.goal.Upkeep
import com.galaxy.steward.core.plan.JunkCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weekly upkeep runs with nobody watching: only clutter that loses nothing, and that is ticked by default. */
class UpkeepTest {
    @Test
    fun onlyWhatLosesNothingGoesUnattended() = runTest {
        TestFs().use { fs ->
            fs.text("Download/movie.mp4.crdownload", "partial")
            fs.text("log/wifi/old.log", "log")
            fs.dir("Documents/Empty/a/b")
            fs.text("Download/empty.txt", "")
            fs.text("Download/notes.txt", "mine")
            // Old runs of a tool: offered for review, never taken unattended.
            for (day in listOf("2026-01-01_10-00", "2026-01-02_10-00", "2026-01-03_10-00")) fs.text("Documents/Runs/$day/out.txt", day)
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings, TestEnv, null).scan()
            val offered = report.junk.map { it.category }.toSet()
            assertTrue(offered.toString(), JunkCategory.ZERO_BYTE_FILES in offered && JunkCategory.OLD_RUNS in offered)

            val picked = Upkeep.select(report, null, fs.rootPath, System.currentTimeMillis())
            assertEquals(
                setOf(JunkCategory.STALE_DOWNLOADS, JunkCategory.OLD_LOGS, JunkCategory.EMPTY_FOLDERS),
                picked.map { it.category }.toSet(),
            )
            assertTrue(picked.none { it.path == fs.path("Download/notes.txt") || it.path == fs.path("Download/empty.txt") })
        }
    }
}
