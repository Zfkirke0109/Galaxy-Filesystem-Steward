package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.ExecutorOptions
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.ScanReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class DedupeTest {
    private suspend fun scan(fs: TestFs, settings: StewardSettings = testSettings): ScanReport =
        Steward(fs.rootPath, settings, TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan()

    @Test
    fun keepsMediaLibraryCopyAndRemovesDownloadCopy() = runTest {
        TestFs().use { fs ->
            val photo = Random(1).nextBytes(300_000)
            fs.file("DCIM/Camera/IMG_0001.jpg", photo)
            fs.file("Download/IMG_0001 (1).jpg", photo)
            fs.file("Documents/Photos/IMG_0001.jpg", photo)
            val report = scan(fs)

            val group = report.duplicates.single()
            assertEquals(fs.path("DCIM/Camera/IMG_0001.jpg"), group.keeper.path)
            assertTrue(group.keepReason.contains("media library"))
            assertEquals(setOf(fs.path("Download/IMG_0001 (1).jpg"), fs.path("Documents/Photos/IMG_0001.jpg")), group.removals.map { it.path }.toSet())
            assertTrue(group.defaultSelected)
            assertEquals(2L * photo.size, group.reclaimBytes)
        }
    }

    @Test
    fun cameraOriginalBeatsShallowerPicturesCopy() = runTest {
        TestFs().use { fs ->
            val bytes = Random(7).nextBytes(20_000)
            fs.file("DCIM/Camera/IMG_2000.jpg", bytes)
            fs.file("Pictures/IMG_2000.jpg", bytes)
            val group = scan(fs).duplicates.single()
            assertEquals(fs.path("DCIM/Camera/IMG_2000.jpg"), group.keeper.path)
            assertEquals(listOf(fs.path("Pictures/IMG_2000.jpg")), group.removals.map { it.path })
        }
    }

    @Test
    fun mediaCopiesAreNeverRemovedInFavourOfNonMedia() = runTest {
        TestFs().use { fs ->
            val bytes = Random(2).nextBytes(10_000)
            fs.file("Pictures/Saved/a.png", bytes)
            fs.file("Documents/a.png", bytes)
            val group = scan(fs).duplicates.single()
            // If the user makes the Documents copy the keeper, the media copy must stay.
            val flipped = group.withKeeper(group.copies.indexOfFirst { it.zone == Zone.USER_MANAGED })
            assertTrue(flipped.removals.isEmpty())
            assertEquals(1, flipped.retained.size)
        }
    }

    @Test
    fun appOwnedCredentialAndProjectFilesAreNotRemovable() = runTest {
        TestFs().use { fs ->
            val bytes = Random(3).nextBytes(50_000)
            fs.file("Android/data/com.example/files/blob.bin", bytes)
            fs.file("Download/blob.bin", bytes)
            fs.file("Documents/keys/release.keystore", bytes)
            fs.file("Download/other.keystore", bytes)
            fs.text("Documents/MyApp/settings.gradle.kts", "rootProject.name = \"x\"")
            fs.file("Documents/MyApp/assets/blob.bin", bytes)
            val report = scan(fs)

            val group = report.duplicates.single()
            // Android/ copies and credential-named files never participate; the project copy is kept, not removed.
            assertEquals(fs.path("Documents/MyApp/assets/blob.bin"), group.keeper.path)
            assertEquals(Zone.PATH_SENSITIVE, group.keeper.zone)
            assertEquals(listOf(fs.path("Download/blob.bin")), group.removals.map { it.path })
        }
    }

    @Test
    fun applyDeletesVerifiedCopyAndRollbackRestoresIt() = runTest {
        TestFs().use { fs ->
            val bytes = Random(4).nextBytes(200_000)
            fs.file("Documents/Archive/data.zip", bytes)
            fs.file("Download/data.zip", bytes)
            val report = scan(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals).execute("Dedupe", "dedupe", report.duplicates)

            assertEquals(1, summary.deduped)
            assertEquals(bytes.size.toLong(), summary.bytesFreed)
            assertFalse(fs.exists("Download/data.zip"))
            assertTrue(fs.exists("Documents/Archive/data.zip"))

            val rollback = RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertEquals(1, rollback.restored)
            assertTrue(File(fs.path("Download/data.zip")).readBytes().contentEquals(bytes))
            assertNotNull(journals.info(summary.runId)?.rolledBackAt)
        }
    }

    @Test
    fun contentChangedAfterScanIsNotDeleted() = runTest {
        TestFs().use { fs ->
            val bytes = Random(5).nextBytes(100_000)
            fs.file("Documents/a.bin", bytes)
            fs.file("Download/a.bin", bytes)
            val report = scan(fs)
            // Same size, different content, same mtime: only a fresh hash can catch this.
            val changed = bytes.copyOf().also { it[500] = (it[500] + 1).toByte() }
            val target = File(report.duplicates.single().removals.single().path)
            val mtime = target.lastModified()
            target.writeBytes(changed)
            target.setLastModified(mtime)

            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Dedupe", "dedupe", report.duplicates)
            assertEquals(0, summary.deduped)
            assertEquals(1, summary.skipped)
            assertTrue(fs.exists("Download/a.bin") && fs.exists("Documents/a.bin"))
        }
    }

    @Test
    fun quarantineModeKeepsBytesRecoverable() = runTest {
        TestFs().use { fs ->
            val bytes = Random(6).nextBytes(64_000)
            fs.file("Documents/x.bin", bytes)
            fs.file("Download/x.bin", bytes)
            val report = scan(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals, ExecutorOptions(quarantineDuplicates = true))
                .execute("Dedupe", "dedupe", report.duplicates)
            assertEquals(bytes.size.toLong(), summary.bytesQuarantined)
            assertFalse(fs.exists("Download/x.bin"))
            assertTrue(File(fs.root, ".StorageSteward/Quarantine/${summary.runId}/Download/x.bin").exists())
            // The quarantine itself is never scanned or deduped.
            assertTrue(scan(fs).duplicates.isEmpty())
            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertTrue(fs.exists("Download/x.bin"))
        }
    }

    @Test
    fun exactDuplicateFoldersAreFoundOnceAtTheTop() = runTest {
        TestFs().use { fs ->
            for (base in listOf("Documents/Trip", "Download/Trip backup")) {
                fs.random("$base/day1/a.jpg", 40_000, 10)
                fs.random("$base/day1/b.jpg", 40_000, 11)
                fs.random("$base/day2/c.jpg", 40_000, 12)
                fs.text("$base/notes.txt", "hello")
            }
            fs.ageDirectories()
            val report = scan(fs)
            // The files inside the duplicate folder are not listed again as file duplicates.
            assertTrue(report.duplicates.isEmpty())
            val group = report.folderDuplicates.single()
            assertEquals(fs.path("Documents/Trip"), group.keeper.path)
            assertEquals(listOf(fs.path("Download/Trip backup")), group.removals.map { it.path })
            assertEquals(4, group.fileCount)

            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Folders", "dedupe", listOf(group))
            assertEquals(4, summary.deduped)
            assertFalse(fs.exists("Download/Trip backup"))
            assertTrue(fs.exists("Documents/Trip/day2/c.jpg"))
        }
    }

    @Test
    fun overlappingFoldersProduceMergePlan() = runTest {
        TestFs().use { fs ->
            val settings = testSettings.copy(minDuplicateFolderBytes = 1)
            for (i in 0 until 6) {
                val bytes = Random(100 + i).nextBytes(1_100_000)
                fs.file("Pictures/Trips/p$i.jpg", bytes)
                fs.file("Download/Trips/p$i.jpg", bytes)
            }
            fs.random("Download/Trips/unique.jpg", 1_000, 999)
            fs.ageDirectories()
            val report = scan(fs, settings)
            val merge = report.folderMerges.firstOrNull()
            assertNotNull("expected a merge plan", merge)
            merge!!
            assertEquals(fs.path("Pictures/Trips"), merge.target)
            assertEquals(6, merge.commonFiles)
            assertEquals(1, merge.uniqueFiles)

            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Merge", "merge", listOf(merge))
            assertEquals(6, summary.deduped)
            assertEquals(1, summary.moved)
            assertTrue(fs.exists("Pictures/Trips/unique.jpg"))
            assertFalse(fs.exists("Download/Trips"))
        }
    }
}
