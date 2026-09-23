package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataHelper
import com.galaxy.steward.core.appdata.AppDataReport
import com.galaxy.steward.core.appdata.AppDataWire
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.core.appdata.AppScanOptions
import com.galaxy.steward.core.appdata.AppTarget
import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.JournalWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppDataTest {
    private val now = System.currentTimeMillis()
    private val installed = setOf("com.example.app", "com.game", "com.whatsapp", "com.amazon.mp3") +
        (1..10).map { "org.filler.app$it" }

    private fun layout(fs: TestFs) {
        val data = "Android/data/com.example.app"
        fs.random("$data/cache/old.bin", 4000, 1)
        fs.random("$data/cache/in-use.bin", 3000, 2, mtime = now)
        fs.text("$data/files/logs/old.log", "old log")
        fs.text("$data/files/logs/today.log", "fresh log", mtime = now)
        fs.random("$data/files/crash.dmp", 500, 3)
        fs.text("$data/files/tmp/upload.tmp", "partial")
        fs.random("$data/files/offline/movie.mp4", 5000, 4) // content the app downloaded on purpose
        fs.text("$data/files/leveldb/CURRENT", "MANIFEST-000001") // a database: its .log is live data
        fs.random("$data/files/leveldb/000003.log", 800, 5)
        fs.text("$data/files/id_rsa.log", "not really a log") // credential-like names are never touched
        fs.random("Android/obb/com.game/main.10.com.game.obb", 6000, 6)
        fs.random("Android/obb/com.game/main.12.com.game.obb", 6000, 7)
        fs.random("Android/obb/com.game/patch.12.com.game.obb", 2000, 8)
        fs.random("Android/data/com.gone.app/files/save.bin", 1500, 9)
        fs.random("Android/media/com.gone.app/Pictures/x.jpg", 700, 10)
        fs.random("Android/data/com.amazon.mp3/cache/track.bin", 9000, 11) // strict no-touch
        fs.random("Android/media/com.whatsapp/WhatsApp/Media/.Thumbs/t1.jpg", 300, 12)
    }

    private fun scan(fs: TestFs, installed: Set<String> = this.installed): AppDataReport {
        val request = AppDataWire.encodeScanRequest(
            AppDataWire.ScanRequest(fs.rootPath, installed, AppScanOptions(now = now), "com.galaxy.steward", AppArea.entries.toSet()),
        )
        val lines = ArrayList<String>()
        AppDataHelper.Helper.scan(request) { lines += it }
        return AppDataHelper.Client.readScan(lines.asSequence())
    }

    private fun AppDataReport.item(kind: AppJunkKind, pkg: String, area: AppArea = AppArea.DATA) =
        items.single { it.kind == kind && it.packageName == pkg && it.area == area }

    private suspend fun apply(fs: TestFs, journals: JournalStore, items: List<AppJunkItem>, installedNow: Set<String>? = installed): ExecutionSummary {
        val runId = journals.newId()
        val request = AppDataWire.encodeApplyRequest(
            AppDataWire.ApplyRequest(fs.rootPath, runId, "App folders", "com.galaxy.steward", installedNow, items),
        )
        val lines = ArrayList<String>()
        AppDataHelper.Helper.apply(request) { lines += it }
        return JournalWriter(journals.fileFor(runId)).use { AppDataHelper.Client.readApply(lines.asSequence(), it) { _, _, _ -> } }
    }

    @Test
    fun scanFindsRegenerableDataAndLeavesContentAlone() {
        TestFs().use { fs ->
            layout(fs)
            val report = scan(fs)

            val cache = report.item(AppJunkKind.CACHE, "com.example.app")
            assertEquals(1, cache.fileCount) // the file written a moment ago is still in use
            assertEquals(4000L, cache.bytes)

            val logs = report.item(AppJunkKind.LOGS, "com.example.app")
            assertEquals(setOf(fs.path("Android/data/com.example.app/files/logs"), fs.path("Android/data/com.example.app/files/crash.dmp")), logs.targets.map { it.path }.toSet())
            assertEquals(2, logs.fileCount)

            assertEquals(1, report.item(AppJunkKind.TEMP, "com.example.app").fileCount)
            val obb = report.item(AppJunkKind.OBSOLETE_OBB, "com.game", AppArea.OBB)
            assertEquals(listOf(fs.path("Android/obb/com.game/main.10.com.game.obb")), obb.targets.map { it.path })
            assertEquals(2, report.items.count { it.kind == AppJunkKind.LEFTOVERS && it.packageName == "com.gone.app" })
            assertEquals(1, report.item(AppJunkKind.THUMBNAILS, "com.whatsapp", AppArea.MEDIA).fileCount)

            // Protected apps are measured but never offered for cleaning.
            assertTrue(report.items.none { it.packageName == "com.amazon.mp3" })
            assertTrue(report.usage.single { it.packageName == "com.amazon.mp3" }.protected)
            // Nothing points at offline content, database logs or credential-like names.
            val everyTarget = report.items.flatMap { it.targets }.map { it.path }
            assertTrue(everyTarget.none { it.contains("movie.mp4") || it.contains("leveldb") || it.contains("id_rsa") })
        }
    }

    @Test
    fun unreliablePackageListNeverReportsLeftovers() {
        TestFs().use { fs ->
            layout(fs)
            val report = scan(fs, installed = setOf("com.example.app"))
            assertTrue(report.items.none { it.kind == AppJunkKind.LEFTOVERS })
        }
    }

    @Test
    fun cleaningIsJournaledAndOnlyQuarantinedItemsComeBack() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val report = scan(fs)
            val summary = apply(fs, journals, report.items)

            assertEquals(0, summary.failed)
            assertFalse(fs.exists("Android/data/com.example.app/cache/old.bin"))
            assertTrue(fs.exists("Android/data/com.example.app/cache/in-use.bin"))
            assertTrue(fs.exists("Android/data/com.example.app/cache")) // the folder itself stays
            assertFalse(fs.exists("Android/data/com.example.app/files/logs/old.log"))
            assertTrue(fs.exists("Android/data/com.example.app/files/logs/today.log"))
            assertTrue(fs.exists("Android/data/com.example.app/files/offline/movie.mp4"))
            assertTrue(fs.exists("Android/data/com.example.app/files/leveldb/000003.log"))
            assertTrue(fs.exists("Android/data/com.amazon.mp3/cache/track.bin"))
            assertFalse(fs.exists("Android/obb/com.game/main.10.com.game.obb"))
            assertTrue(fs.exists("Android/obb/com.game/main.12.com.game.obb"))
            assertFalse(fs.exists("Android/data/com.gone.app"))
            assertTrue(summary.cleared >= 5)
            assertEquals(3, summary.quarantined)

            val info = journals.list().single()
            assertEquals("appdata", info.kind)
            assertEquals(3, info.restorableCount)
            assertTrue(journals.entries(info.id).any { it.action == JournalAction.PURGED })

            val lines = ArrayList<String>()
            AppDataHelper.Helper.rollback(fs.rootPath, AppDataWire.encodeEntries(journals.entries(info.id))) { lines += it }
            val undo = AppDataHelper.Client.readRollback(lines.asSequence()) { _, _, _ -> }
            assertEquals(3, undo.restored)
            assertTrue(fs.exists("Android/obb/com.game/main.10.com.game.obb"))
            assertTrue(fs.exists("Android/data/com.gone.app/files/save.bin"))
            assertTrue(fs.exists("Android/media/com.gone.app/Pictures/x.jpg"))
            assertFalse(fs.exists("Android/data/com.example.app/cache/old.bin")) // caches are gone for good
        }
    }

    @Test
    fun runTimeChecksRefuseProtectedChangedAndReinstalled() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val report = scan(fs)
            val amazon = fs.path("Android/data/com.amazon.mp3/cache")
            val forged = AppJunkItem(
                "app:CACHE:data:com.amazon.mp3", AppJunkKind.CACHE, "com.amazon.mp3", AppArea.DATA,
                listOf(AppTarget(amazon, true, 9000, now)), 9000, 1, now, "",
            )
            val crash = report.item(AppJunkKind.LOGS, "com.example.app")
            File(fs.path("Android/data/com.example.app/files/crash.dmp")).setLastModified(now - 20 * DAY_MS) // changed after the scan
            val leftovers = report.items.filter { it.kind == AppJunkKind.LEFTOVERS }

            val summary = apply(fs, journals, listOf(forged, crash) + leftovers, installedNow = installed + "com.gone.app")
            assertTrue(fs.exists("Android/data/com.amazon.mp3/cache/track.bin"))
            assertTrue(fs.exists("Android/data/com.example.app/files/crash.dmp"))
            assertTrue(fs.exists("Android/data/com.gone.app/files/save.bin")) // reinstalled since the scan
            assertTrue(summary.messages.any { it.startsWith("Protected (protected app)") })
            assertTrue(summary.messages.any { it.startsWith("Changed since the scan") })
            assertTrue(summary.messages.any { it.startsWith("App is installed again") })
            assertNull(summary.messages.firstOrNull { it.startsWith("I/O error") })
        }
    }
}
