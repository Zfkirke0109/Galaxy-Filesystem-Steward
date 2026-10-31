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

    private fun junk(category: JunkCategory, fs: TestFs, rel: String, size: Int, isDirectory: Boolean) =
        JunkItem("junk:$category:$rel", category, fs.path(rel), isDirectory, size.toLong(), File(fs.path(rel)).lastModified(), "test")
}
