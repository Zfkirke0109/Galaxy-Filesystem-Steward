package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.DuplicateCopy
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.OrganizeMove
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * One run removes a duplicate for the copy it keeps, then is asked to quarantine that very copy: a near-copy folder
 * holding it, or an old installer that is it. Autopilot and the goal run take duplicates before cleanup, and with
 * "empty the quarantine now" the photo would be gone for good.
 */
class KeptCopyTest {
    @Test
    fun aCopyKeptForADuplicateStaysOutOfTheQuarantine() = runTest {
        TestFs().use { fs ->
            val photo = Random(11).nextBytes(40_000)
            val apk = Random(12).nextBytes(30_000)
            fs.file("Download/Export_2024/IMG_0001.jpg", photo)
            fs.file("Download/Export_2025/IMG_0001.jpg", photo)
            fs.file("Documents/APKs/app.apk", apk)
            fs.file("Download/app.apk", apk)
            fs.ageDirectories()

            val photoGroup = group("dup:photo", fs, photo.size, "Download/Export_2024/IMG_0001.jpg", "Download/Export_2025/IMG_0001.jpg")
            val apkGroup = group("dup:apk", fs, apk.size, "Documents/APKs/app.apk", "Download/app.apk")
            val nearCopy = junk(JunkCategory.NEAR_COPIES, fs, "Download/Export_2024", photo.size, isDirectory = true)
            val oldInstaller = junk(JunkCategory.OLD_INSTALLERS, fs, "Documents/APKs/app.apk", apk.size, isDirectory = false)

            // Autopilot's order: duplicates first, then cleanup.
            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals")))
                .execute("Autopilot", "autopilot", listOf(photoGroup, apkGroup, nearCopy, oldInstaller))

            assertTrue(fs.exists("Download/Export_2024/IMG_0001.jpg"))
            assertTrue(fs.exists("Documents/APKs/app.apk"))
            assertEquals(2, summary.deduped)
            assertEquals(0, summary.quarantined)
            assertEquals(2, summary.skipped)
            assertEquals(2, summary.reasons[ActionExecutor.KEPT_COPY])
        }
    }

    @Test
    fun aKeptCopyIsFollowedWhenTheSameRunMovesIt() = runTest {
        TestFs().use { fs ->
            val photo = Random(13).nextBytes(20_000)
            fs.file("Download/x/IMG_0002.jpg", photo)
            fs.file("Download/y/IMG_0002.jpg", photo)
            fs.ageDirectories()
            val dedupe = group("dup:photo", fs, photo.size, "Download/x/IMG_0002.jpg", "Download/y/IMG_0002.jpg")
            val kept = File(fs.path("Download/x/IMG_0002.jpg"))
            val move = OrganizeMove(
                "organize:photo", kept.path, fs.path("Pictures/Old/IMG_0002.jpg"), false, photo.size.toLong(), 1, "test", "Pictures/Old", true,
                listOf(MoveFileOp(kept.path, fs.path("Pictures/Old/IMG_0002.jpg"), kept.length(), kept.lastModified())),
            )
            val folder = junk(JunkCategory.NEAR_COPIES, fs, "Pictures/Old", photo.size, isDirectory = true).copy(mtime = -1)
            fs.dir("Pictures/Old")

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Autopilot", "autopilot", listOf(dedupe, move, folder))

            assertTrue(fs.exists("Pictures/Old/IMG_0002.jpg"))
            assertEquals(1, summary.reasons[ActionExecutor.KEPT_COPY])
        }
    }

    private fun group(id: String, fs: TestFs, size: Int, keeper: String, removal: String): DuplicateGroup {
        val copies = listOf(keeper, removal).map { DuplicateCopy(fs.path(it), size.toLong(), File(fs.path(it)).lastModified(), Zone.USER_MANAGED) }
        return DuplicateGroup(id, Hashing.sha256(fs.path(keeper)), size.toLong(), copies, keeperIndex = 0, keepReason = "test")
    }

    /**
     * Two runs from one scan, as from the Duplicates and then the Clutter screen: the duplicate groups used to keep the
     * copies in the older folder (older files), the near-copy item then quarantined that very folder ("Thesis-2 is newer
     * and stays"), and the chapters were left only in the quarantine, gone a week later.
     */
    @Test
    fun aSecondRunFromTheSameScanKeepsTheKeptCopies() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            val chapters = (0 until 24).map { Random(300 + it).nextBytes(2_000 + it) }
            chapters.forEachIndexed { i, bytes ->
                fs.file("Download/Thesis/ch$i.pdf", bytes, mtime = now - 120 * DAY_MS)
                fs.file("Download/Thesis-2/ch$i.pdf", bytes, mtime = now - 30 * DAY_MS)
            }
            fs.random("Download/Thesis-2/ch24.pdf", 2_500, 999, mtime = now - 30 * DAY_MS)
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings.copy(nearCopyMinBytes = 1), TestEnv, null).scan()
            val near = report.junk.filter { it.category == JunkCategory.NEAR_COPIES }
            assertEquals(listOf(fs.path("Download/Thesis")), near.map { it.path })
            assertEquals(24, report.duplicates.size)
            report.duplicates.forEach { g -> assertTrue(g.keepReason, g.keeper.path.startsWith(fs.path("Download/Thesis-2/"))) }

            val journals = JournalStore(File(fs.stateDir, "journals"))
            ActionExecutor(fs.rootPath, journals).execute("Duplicates", "dedupe", report.duplicates)
            ActionExecutor(fs.rootPath, journals).execute("Clutter", "junk", near)
            chapters.forEachIndexed { i, bytes ->
                assertTrue("ch$i", File(fs.path("Download/Thesis-2/ch$i.pdf")).readBytes().contentEquals(bytes))
            }
        }
    }

    /**
     * The same, with a copy you chose to keep in the older folder: the near-copy offer for that folder goes, as it does
     * for the copies the scan keeps itself. Before, it stayed, and the second run left that chapter only in the quarantine.
     */
    @Test
    fun aCopyYouChoseToKeepTakesItsFolderOutOfTheClutterList() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            val chapters = (0 until 24).map { Random(300 + it).nextBytes(2_000 + it) }
            chapters.forEachIndexed { i, bytes ->
                fs.file("Download/Thesis/ch$i.pdf", bytes, mtime = now - 120 * DAY_MS)
                fs.file("Download/Thesis-2/ch$i.pdf", bytes, mtime = now - 30 * DAY_MS)
            }
            fs.random("Download/Thesis-2/ch24.pdf", 2_500, 999, mtime = now - 30 * DAY_MS)
            fs.ageDirectories()
            val scanned = Steward(fs.rootPath, testSettings.copy(nearCopyMinBytes = 1), TestEnv, null).scan()
            val group = scanned.duplicates.single { g -> g.copies.any { it.path == fs.path("Download/Thesis/ch0.pdf") } }
            val mine = group.withKeeper(group.copies.indexOfFirst { it.path == fs.path("Download/Thesis/ch0.pdf") })
            assertEquals("Chosen by you", mine.keepReason)

            val report = scanned.leavingInPlace(mine.keeper.path)
            assertTrue(report.junk.none { it.category == JunkCategory.NEAR_COPIES })
            assertTrue(report.insights.any { it.title == "Left in place: Thesis" })
            assertEquals(scanned, scanned.leavingInPlace(fs.path("Download/Thesis-2/ch0.pdf")))

            val journals = JournalStore(File(fs.stateDir, "journals"))
            ActionExecutor(fs.rootPath, journals).execute("Duplicates", "dedupe", report.duplicates.map { if (it.id == mine.id) mine else it })
            // Everything left on the clutter list, ticked by you (near copies aren't ticked by default).
            ActionExecutor(fs.rootPath, journals).execute("Clutter", "junk", report.junk)
            assertTrue(File(fs.path("Download/Thesis/ch0.pdf")).readBytes().contentEquals(chapters[0]))
        }
    }

    /** Clutter that holds the copy a duplicate group keeps, with no other copy to keep instead, isn't offered. */
    @Test
    fun clutterHoldingTheOnlyKeptCopyIsLeftInPlace() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            // Two old run folders of a tool, each with the same report: both are offered as old runs.
            val bytes = Random(5).nextBytes(3_000)
            for (day in listOf("2026-01-01_10-00", "2026-01-02_10-00", "2026-10-30_10-00")) {
                val age = if (day.startsWith("2026-10")) 100 else 200
                (0 until 3).forEach { fs.random("Documents/ToolRuns/$day/part$it.txt", 500 + it, day.hashCode() + it, mtime = now - age * DAY_MS) }
            }
            fs.file("Documents/ToolRuns/2026-01-01_10-00/report.pdf", bytes, mtime = now - 200 * DAY_MS)
            fs.file("Documents/ToolRuns/2026-01-02_10-00/report.pdf", bytes, mtime = now - 199 * DAY_MS)
            fs.ageDirectories()
            // A run folder counts from its newest file or its own time, whichever is newer.
            mapOf("2026-01-01_10-00" to 200, "2026-01-02_10-00" to 199, "2026-10-30_10-00" to 100).forEach { (day, age) ->
                File(fs.root, "Documents/ToolRuns/$day").setLastModified(now - age * DAY_MS)
            }
            val report = Steward(fs.rootPath, testSettings, TestEnv, null).scan()
            assertTrue(report.insights.any { it.title == "Left in place: 2026-01-01_10-00" })
            val group = report.duplicates.single { it.keeper.name == "report.pdf" }
            val quarantined = report.junk.filter { it.category != JunkCategory.EMPTY_FOLDERS }.map { it.path }
            assertTrue(quarantined.toString(), quarantined.none { group.keeper.path == it || group.keeper.path.startsWith("$it/") })
        }
    }

    private fun junk(category: JunkCategory, fs: TestFs, rel: String, size: Int, isDirectory: Boolean) =
        JunkItem("junk:$category:$rel", category, fs.path(rel), isDirectory, size.toLong(), File(fs.path(rel)).lastModified(), "test")
}
