package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.plan.JunkCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Samsung My Files keeps what you delete in `Android/.Trash/com.sec.android.app.myfiles/<volume>` (242 MiB on the 11-19
 * phone). Everything under Android/ is app-owned, so no clean-up looked there. What is inside such a bin is offered like
 * any other recycle bin's items, and nothing else under Android/ is.
 */
class SystemTrashTest {
    private val bin = "Android/.Trash/com.sec.android.app.myfiles/2909f2b1-f2f2-4eb9-9c40-69e6a6577202"

    @Test
    fun whatYouDeletedInMyFilesIsOfferedAndComesBackWithUndo() = runTest {
        TestFs().use { fs ->
            val photo = fs.random("$bin/IMG_0001.jpg", 40_000, 1)
            val photoBytes = photo.readBytes()
            fs.random("$bin/Old trip/day1.mp4", 60_000, 2)
            fs.random("Android/data/com.example/files/blob.bin", 5_000, 3)
            fs.random("Android/media/com.whatsapp/WhatsApp/Media/a.jpg", 5_000, 4)
            fs.ageDirectories()

            val report = Steward(fs.rootPath, testSettings, TestEnv, null).scan()
            val bins = report.junk.filter { it.category == JunkCategory.RECYCLE_BINS }
            assertEquals(setOf(fs.path("$bin/IMG_0001.jpg"), fs.path("$bin/Old trip")), bins.map { it.path }.toSet())
            assertTrue(bins.all { it.note.contains("My Files") && it.defaultSelected })
            // Nothing else under Android/ is offered, moved or filed.
            val elsewhere = report.allItems().flatMap { it.operations.toString().split(", ") }
                .filter { it.contains("/Android/") && !it.contains("/Android/.Trash/") }
            assertEquals(emptyList<String>(), elsewhere)

            val journals = JournalStore(File(fs.stateDir, "journals"))
            val run = ActionExecutor(fs.rootPath, journals).execute("Clutter", "junk", bins)
            assertEquals(run.messages.joinToString("\n"), 2, run.quarantined)
            assertFalse(photo.exists())
            assertTrue(File(fs.path(bin)).isDirectory) // the bin itself stays

            val undo = RollbackEngine(fs.rootPath, journals).rollback(run.runId)
            assertEquals(undo.messages.joinToString("\n"), 0, undo.failed + undo.skipped)
            assertTrue(photo.readBytes().contentEquals(photoBytes))
            assertTrue(File(fs.path("$bin/Old trip/day1.mp4")).exists())
        }
    }

    @Test
    fun onlyWhatIsInsideABinMayChange() {
        val guard = PathGuard("/storage/emulated/0")
        fun reason(rel: String) = guard.protectionReason("/storage/emulated/0/$rel")
        assertNotNull(reason("Android/.Trash"))
        assertNotNull(reason("Android/.Trash/com.sec.android.app.myfiles"))
        assertNull(reason("$bin/IMG_0001.jpg"))
        assertNull(reason("android/.trash/com.sec.android.app.myfiles/a.jpg"))
        assertNotNull(reason("Android/data/com.app/files/a.jpg"))
        assertNotNull(reason("Android/obb/com.game/main.obb"))
        assertNotNull(reason("Android/media/com.whatsapp/a.jpg"))
        assertNotNull(reason("Android/.Trash/../data/com.app/a.jpg"))
        assertNotNull(reason("$bin/keys/release.jks"))
    }
}
