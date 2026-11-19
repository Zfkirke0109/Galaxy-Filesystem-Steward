package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataBrowser
import com.galaxy.steward.core.appdata.AppDataHelper
import com.galaxy.steward.core.appdata.AppDataWire
import com.galaxy.steward.core.appdata.AppFolderEntry
import com.galaxy.steward.core.appdata.AppFolderListing
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppJunkKind
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
import java.nio.file.Files

/** The app folder browser: listing Android/data like a file manager, then removing what you pick. */
class AppFolderBrowserTest {
    private val now = System.currentTimeMillis()
    private val own = "com.galaxy.steward"
    private val game = "Android/data/com.example.game"

    private fun layout(fs: TestFs) {
        fs.random("$game/files/assets/level1.pak", 6000, 1)
        fs.random("$game/files/assets/level2.pak", 4000, 2)
        fs.random("$game/files/saves/slot1.sav", 300, 3)
        fs.text("$game/files/api.key", "secret") // credential-like: shown, never removable
        fs.random("$game/cache/shader.bin", 900, 4)
        fs.random("$game/notes.txt", 50, 5)
        fs.random("Android/data/com.amazon.mp3/files/track.bin", 7000, 6) // strict no-touch
        fs.random("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/v1.mp4", 5000, 7)
    }

    /** Lists through the same line protocol the Shizuku helper uses. */
    private fun list(fs: TestFs, rel: String): AppFolderListing {
        val lines = ArrayList<String>()
        AppDataHelper.Helper.list(AppDataWire.encodeListRequest(AppDataWire.ListRequest(fs.rootPath, own, fs.path(rel)))) { lines += it }
        return AppDataHelper.Client.readList(lines.asSequence())
    }

    private fun AppFolderListing.entry(name: String): AppFolderEntry = entries.single { it.name == name }

    private fun picked(listing: AppFolderListing, names: List<String>, delete: Boolean): List<AppJunkItem> =
        listing.itemsFor(names.map { listing.entry(it) }, quarantine = !delete)

    private suspend fun apply(fs: TestFs, journals: JournalStore, items: List<AppJunkItem>): ExecutionSummary {
        val runId = journals.newId()
        val request = AppDataWire.encodeApplyRequest(AppDataWire.ApplyRequest(fs.rootPath, runId, "Picked", own, null, items))
        val lines = ArrayList<String>()
        AppDataHelper.Helper.apply(request) { lines += it }
        return JournalWriter(journals.fileFor(runId)).use { AppDataHelper.Client.readApply(lines.asSequence(), it) { _, _, _ -> } }
    }

    @Test
    fun listsFoldersWithTheirTotalSizeLargestFirst() {
        TestFs().use { fs ->
            layout(fs)
            Files.createSymbolicLink(File(fs.path("$game/files/shared")).toPath(), fs.root.toPath()) // never followed
            val root = list(fs, game)
            assertEquals(AppArea.DATA, root.area)
            assertEquals("com.example.game", root.packageName)
            assertFalse(root.protected)
            assertEquals(listOf("files", "cache", "notes.txt"), root.entries.map { it.name })
            assertEquals(6000L + 4000 + 300 + 6, root.entry("files").bytes) // the link adds nothing
            assertEquals(4, root.entry("files").files)
            assertTrue(root.entry("files").isDirectory)

            val files = list(fs, "$game/files")
            assertEquals(listOf("assets", "saves", "api.key", "shared"), files.entries.map { it.name })
            assertEquals("key or credential", files.entry("api.key").locked)
            assertEquals("link", files.entry("shared").locked)
            assertNull(files.entry("assets").locked)
            // The key and the link can never be picked.
            assertEquals(listOf("assets", "saves"), files.itemsFor(files.entries, quarantine = true).map { it.note })

            val amazon = list(fs, "Android/data/com.amazon.mp3")
            assertTrue(amazon.protected)
            assertTrue(amazon.entries.all { it.locked == "protected app" })
        }
    }

    @Test
    fun refusesAnythingOutsideAnAppFolder() {
        TestFs().use { fs ->
            layout(fs)
            fs.text("Download/private.txt", "x")
            Files.createSymbolicLink(File(fs.path("Android/data/com.link.app")).toPath(), File(fs.path("Download")).toPath())
            for (rel in listOf("Download", "Android/data", "Android/data/com.example.game/../com.amazon.mp3", "Android/data/com.link.app")) {
                val error = runCatching { list(fs, rel) }.exceptionOrNull()
                assertTrue("$rel should be refused", error is AppDataHelper.HelperException)
            }
            val browser = AppDataBrowser(fs.rootPath, own, maxEntries = 1)
            val capped = browser.list(fs.path(game))
            assertEquals(1, capped.entries.size)
            assertEquals(2, capped.hidden)
        }
    }

    @Test
    fun pickedItemsGoToTheQuarantineAndComeBack() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val files = list(fs, "$game/files")
            val summary = apply(fs, journals, picked(files, listOf("assets", "saves"), delete = false))

            assertEquals(0, summary.failed)
            assertEquals(2, summary.quarantined)
            assertFalse(fs.exists("$game/files/assets"))
            assertFalse(fs.exists("$game/files/saves"))
            assertTrue(fs.exists("$game/files/api.key"))
            val entries = journals.entries(journals.list().single().id)
            assertEquals(2, entries.count { it.action == JournalAction.QUARANTINED })

            val lines = ArrayList<String>()
            AppDataHelper.Helper.rollback(fs.rootPath, AppDataWire.encodeEntries(entries)) { lines += it }
            val undo = AppDataHelper.Client.readRollback(lines.asSequence()) { _, _, _ -> }
            assertEquals(2, undo.restored)
            assertEquals(6000L, File(fs.path("$game/files/assets/level1.pak")).length())
            assertTrue(fs.exists("$game/files/saves/slot1.sav"))
        }
    }

    @Test
    fun deletingForGoodKeepsKeysRecentFilesAndTheAppFolder() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val root = list(fs, game)
            fs.random("$game/files/saves/live.sav", 10, 8, mtime = now + 60_000) // written after the listing
            val summary = apply(fs, journals, picked(root, listOf("files", "cache", "notes.txt"), delete = true))

            assertFalse(fs.exists("$game/files/assets"))
            assertFalse(fs.exists("$game/files/saves/slot1.sav"))
            assertTrue(fs.exists("$game/files/saves/live.sav"))
            assertTrue(fs.exists("$game/files/api.key"))
            assertFalse(fs.exists("$game/cache"))
            assertFalse(fs.exists("$game/notes.txt"))
            assertTrue(fs.exists(game)) // the app's own folder stays
            assertEquals(6000L + 4000 + 300 + 900 + 50, summary.bytesFreed)
            assertTrue(summary.messages.any { it.startsWith("2 recent, linked or key-like item(s) kept") })
            assertTrue(journals.entries(journals.list().single().id).all { it.action == JournalAction.PURGED })
        }
    }

    @Test
    fun runTimeChecksRefuseTheAppFolderOtherAppsAndChangedFiles() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val root = list(fs, game)
            val notes = picked(root, listOf("notes.txt"), delete = true).single()
            File(fs.path("$game/notes.txt")).appendText("more") // changed after the listing
            val appFolder = notes.copy(id = "pick:app", targets = listOf(AppTarget(fs.path(game), true, 0, 0)))
            val otherApp = notes.copy(id = "pick:other", targets = listOf(AppTarget(fs.path("Android/data/com.amazon.mp3/files"), true, 0, 0)))
            val amazon = list(fs, "Android/data/com.amazon.mp3")
            val protectedPick = AppJunkItem(
                "pick:amazon", AppJunkKind.PICKED_DELETE, "com.amazon.mp3", AppArea.DATA,
                listOf(AppTarget(amazon.entries.single().path, true, 7000, 0)), 7000, 1, now, "",
            )

            val summary = apply(fs, journals, listOf(notes, appFolder, otherApp, protectedPick))
            assertTrue(fs.exists("$game/notes.txt"))
            assertTrue(fs.exists("Android/data/com.amazon.mp3/files/track.bin"))
            assertEquals(0L, summary.bytesFreed)
            assertTrue(summary.messages.any { it.startsWith("Changed since the scan") })
            assertTrue(summary.messages.any { it.startsWith("An app's own folder stays") })
            assertTrue(summary.messages.any { it.startsWith("Protected (belongs to another app)") })
            assertTrue(summary.messages.any { it.startsWith("Protected (protected app)") })
        }
    }

    @Test
    fun mediaFoldersListInTheAppToo() {
        TestFs().use { fs ->
            layout(fs)
            val listing = AppDataBrowser(fs.rootPath, own).list(fs.path("Android/media/com.whatsapp/WhatsApp/Media"))
            assertEquals(AppArea.MEDIA, listing.area)
            assertEquals(listOf("WhatsApp Video"), listing.entries.map { it.name })
            assertEquals(5000L, listing.entries.single().bytes)
        }
    }
}
