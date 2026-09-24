package com.galaxy.steward

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.diagnostics.StewardLog
import com.galaxy.steward.ui.MainActivity
import com.galaxy.steward.ui.Outcome
import com.galaxy.steward.ui.StewardViewModel
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.ShadowStorageStatsManager
import org.robolectric.shadows.ShadowUsageStatsManager
import java.io.File
import kotlin.random.Random

/**
 * End-to-end check of the Android layer with the real engine: a fixture "phone" is scanned through the
 * ViewModel, every screen is rendered to build/outputs/roborazzi, Autopilot is applied and verified on disk,
 * and the run is undone again.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var root: File
    private val old = System.currentTimeMillis() - 90 * DAY_MS
    private val photo = Random(1).nextBytes(700_000)

    /** Drives Robolectric's paused main looper while background work (scan, apply, undo) finishes. */
    private fun awaitState(what: String, vm: StewardViewModel, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 120_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what; state=${vm.state.value.copy(report = null)}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun file(rel: String, bytes: ByteArray) = File(root, rel).apply {
        parentFile!!.mkdirs()
        writeBytes(bytes)
        setLastModified(old)
    }

    private fun text(rel: String, s: String) = file(rel, s.toByteArray())

    /** What the app wrote under its log tag, so a logcat export would show it. */
    private fun stewardLog(): List<String> = ShadowLog.getLogsForTag(StewardLog.TAG).map { it.msg }

    @Before
    fun fixture() {
        root = Environment.getExternalStorageDirectory()
        root.deleteRecursively()
        root.mkdirs()
        file("DCIM/Camera/IMG_20240612_101500.jpg", photo)
        file("Download/IMG_20240612_101500 (1).jpg", photo)
        file("Pictures/WhatsApp/IMG-20240612-WA0003.jpg", photo)
        text("Download/Bank Statement 2024-05.pdf", "statement")
        text("Download/Boarding Pass LIS.pdf", "boarding")
        file("Download/Screenshot_20240501-101010.png", Random(2).nextBytes(90_000))
        file("Download/Pokemon Emerald.gba", Random(3).nextBytes(400_000))
        file("Download/llama-3-8b.Q4_K_M.gguf", Random(4).nextBytes(500_000))
        file("Download/backup-2024-06.zip", Random(5).nextBytes(300_000))
        file("Download/bugreport-SM-S918B-2024-06-01.zip", Random(6).nextBytes(200_000))
        text("Download/notes.txt", "shopping list")
        for (i in 1..3) file("Download/Holiday Lisbon/DSC_00$i.jpg", Random(10 + i).nextBytes(350_000))
        text("Download/my-tool/.git/HEAD", "ref: refs/heads/main")
        text("Download/my-tool/main.py", "print('hi')")
        text("Download/big-movie.mkv.crdownload", "partial")
        text("log/dumpstate_2024-05-01.txt", "old log")
        File(root, "Download/Old stuff/Empty/Deeper").mkdirs()
        text("Download/Project-X-main/Project-X-main/readme.md", "# X")
        text("Download/Project-X-main/Project-X-main/docs/guide.md", "guide")
        text("Documents/Documents/stray.pdf", "stray")
        for (base in listOf("Documents/Trip", "Download/Trip copy")) {
            for (i in 1..3) file("$base/day$i.jpg", Random(20 + i).nextBytes(400_000))
        }
        file("Android/data/com.example/files/blob.bin", photo)
        // App folders reachable without Shizuku (Android/media): an old log and a thumbnail cache.
        text("Android/media/com.example.logger/logs/sync-2024-05-01.log", "old log line")
        file("Android/media/com.whatsapp/WhatsApp/Media/.Thumbs/t1.jpg", Random(30).nextBytes(20_000))
        file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", Random(31).nextBytes(30_000))
        text("Documents/keys/release.jks", "keystore")
        root.walkBottomUp().filter { it.isDirectory }.forEach { it.setLastModified(old) }

        val blocks = (256L * 1024 * 1024 * 1024 / 4096).toInt()
        ShadowStatFs.registerStats(root, blocks, blocks / 7, blocks / 7)
        // Environment.isExternalStorageManager() checks the primary external dir, which Robolectric leaves unset.
        ShadowEnvironment.addExternalDir("emulated-0")
    }

    /**
     * Three apps as Android's statistics report them: sizes from StorageStatsManager, last use from UsageStatsManager,
     * and usage access granted so both can be read.
     */
    private fun installApps() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()
        val storage = Shadow.extract<ShadowStorageStatsManager>(context.getSystemService(StorageStatsManager::class.java))
        val usage = Shadow.extract<ShadowUsageStatsManager>(context.getSystemService(UsageStatsManager::class.java))
        // Package, label, app, data (without cache), cache, and how long ago it was last used.
        listOf(
            listOf("com.example.game", "Example Game", 300L * 1024 * 1024, 2_600L * 1024 * 1024, 40L * 1024 * 1024, 200 * DAY_MS),
            listOf("org.thoughtcrime.securesms", "Signal", 120L * 1024 * 1024, 900L * 1024 * 1024, 30L * 1024 * 1024, DAY_MS / 2),
            listOf("com.example.notes", "Notes", 20L * 1024 * 1024, 5L * 1024 * 1024, 1L * 1024 * 1024, 3 * DAY_MS),
        ).forEach { row ->
            val pkg = row[0] as String
            shadowOf(context.packageManager).installPackage(
                PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = ApplicationInfo().apply {
                        packageName = pkg
                        nonLocalizedLabel = row[1] as String
                    }
                },
            )
            val stats = StorageStats::class.java.getDeclaredConstructor().newInstance()
            fun field(name: String, value: Long) = StorageStats::class.java.getDeclaredField(name).apply { isAccessible = true }.setLong(stats, value)
            field("codeBytes", row[2] as Long)
            field("dataBytes", row[3] as Long + row[4] as Long) // Android counts the cache as part of the data
            field("cacheBytes", row[4] as Long)
            storage.addStorageStats(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle(), stats)
            usage.addUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                ShadowUsageStatsManager.UsageStatsBuilder.newBuilder().setPackageName(pkg).setFirstTimeStamp(now - 300 * DAY_MS)
                    .setLastTimeStamp(now).setLastTimeUsed(now - row[5] as Long).build(),
            )
        }
        shadowOf(context.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
    }

    /** "All files access" is an app-op on Android 11+; flip it the way the Settings toggle would. */
    private fun setAllFilesAccess(granted: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(
            "android:manage_external_storage", // AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE (hidden)
            context.applicationInfo.uid,
            context.packageName,
            if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED,
        )
    }

    @Test
    fun scanReviewApplyAndUndo() {
        installApps()
        setAllFilesAccess(false)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Grant all files access").assertExists()
        shot("00-permission")

        setAllFilesAccess(true)
        lateinit var vm: StewardViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[StewardViewModel::class.java] }
        vm.setDynamicColor(false)
        vm.refreshAccess()
        compose.onNodeWithText("Start smart scan").assertExists()
        shot("01-home-before-scan")

        compose.onNodeWithText("Start smart scan").performClick()
        awaitState("scan", vm) { vm.state.value.report != null }
        shot("02-home-after-scan")
        // The first scan asks once for the notification permission (so progress shows in the shade) and is logged.
        scenario.onActivity {
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(it).lastRequestedPermission?.requestedPermissions?.toList())
        }
        assertFalse(vm.shouldAskForNotifications())
        assertTrue(stewardLog().toString(), stewardLog().any { it.startsWith("scan done in ") && it.contains("; phases mapping ") })

        val report = vm.state.value.report!!
        assertEquals(1, report.duplicates.size)
        assertEquals(1, report.folderDuplicates.size)
        assertTrue(report.organize.any { it.source.endsWith("Download/Bank Statement 2024-05.pdf") })

        compose.onNodeWithText("Duplicates").performClick()
        compose.onNodeWithText("IMG_20240612_101500.jpg").performClick()
        shot("03-duplicates")
        compose.onNodeWithText("Folders (1)").performClick()
        shot("04-duplicate-folders")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Clutter").performClick()
        compose.onNodeWithText("Abandoned downloads").performClick()
        shot("05-clutter")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Organize").performClick()
        shot("06-organize")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Optimize").performClick()
        shot("07-optimize")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Review & apply"))
        compose.onNodeWithText("Review & apply").performClick()
        shot("08-autopilot-confirm")
        compose.onNodeWithText("Apply").performClick()
        awaitState("autopilot", vm) { vm.state.value.applying == null && vm.state.value.outcome != null }
        shot("09-autopilot-result")

        val outcome = vm.state.value.outcome
        assertTrue("expected an applied outcome, got $outcome", outcome is Outcome.Applied)
        assertTrue(stewardLog().toString(), stewardLog().any { it.startsWith("run \"Autopilot\" (autopilot) done in ") })
        // Verified duplicate removed, camera original kept.
        assertFalse(File(root, "Download/IMG_20240612_101500 (1).jpg").exists())
        assertTrue(File(root, "DCIM/Camera/IMG_20240612_101500.jpg").exists())
        // Filed semantically.
        assertTrue(File(root, "Documents/Personal/Finance/Bank Statement 2024-05.pdf").exists())
        assertTrue(File(root, "Documents/Personal/Travel/Boarding Pass LIS.pdf").exists())
        assertTrue(File(root, "Documents/Gaming-Emulation/ROMs/Pokemon Emerald.gba").exists())
        assertTrue(File(root, "Pictures/Imported/Holiday Lisbon/DSC_001.jpg").exists())
        // Duplicate folder removed, wrapper flattened, clutter quarantined, empty tree gone.
        assertFalse(File(root, "Download/Trip copy").exists())
        assertTrue(File(root, "Download/Project-X-main/readme.md").exists() || File(root, "Documents").walk().any { it.name == "readme.md" })
        assertFalse(File(root, "Download/big-movie.mkv.crdownload").exists())
        assertFalse(File(root, "Download/Old stuff").exists())
        // Never touched: project, app-owned data, credentials.
        assertTrue(File(root, "Download/my-tool/main.py").exists())
        assertTrue(File(root, "Android/data/com.example/files/blob.bin").exists())
        assertTrue(File(root, "Documents/keys/release.jks").exists())

        compose.onNodeWithText("Undo").performClick()
        awaitState("undo", vm) { vm.state.value.outcome is Outcome.RolledBack }
        shot("10-undo-result")
        assertTrue(File(root, "Download/IMG_20240612_101500 (1).jpg").readBytes().contentEquals(photo))
        assertTrue(File(root, "Download/Bank Statement 2024-05.pdf").exists())
        assertTrue(File(root, "Download/Trip copy/day1.jpg").exists())
        assertTrue(File(root, "Download/big-movie.mkv.crdownload").exists())
        assertTrue(File(root, "Download/Old stuff/Empty/Deeper").isDirectory)
        assertFalse(File(root, "Documents/Personal").exists())
        compose.onNodeWithText("Done").performClick()

        compose.onAllNodesWithText("History")[0].performClick()
        shot("11-history")
        compose.onNodeWithText("Map").performClick()
        compose.onNodeWithText("Download").performClick()
        shot("12-storage-map")
        compose.onNodeWithText("Settings").performClick()
        shot("13-settings")

        // Settings > Diagnostics: without Shizuku the export holds the app's own log, saved under Documents.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Export logcat"))
        compose.onNodeWithText("Export").performClick()
        awaitState("logcat export", vm) { vm.logcat.state.value.let { !it.running && (it.saved != null || it.error != null) } }
        val saved = vm.logcat.state.value.saved ?: error("logcat export failed: ${vm.logcat.state.value.error}")
        assertEquals(File(root, SafetyPolicy.LOGCAT_DIR).path, saved.file.parent)
        assertTrue(saved.file.name.matches(Regex("""logcat-\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d\.txt""")))
        assertFalse(saved.wholeDevice)
        val log = saved.file.readText()
        assertTrue(log.startsWith("Galaxy Steward "))
        assertTrue(log.contains("Contents: Galaxy Steward's own log lines only"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Share"))
        shot("13b-logcat-export")
        assertTrue(stewardLog().toString(), stewardLog().any { it.startsWith("undo \"Autopilot\" done in ") })
        assertTrue(stewardLog().toString(), stewardLog().any { it.startsWith("logcat saved in ") && it.endsWith("own lines only") })
        // Share hands the file to other apps through the FileProvider, which serves exactly this folder.
        compose.onNodeWithText("Share").performClick()
        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("${context.packageName}.files", uri.authority)
        context.contentResolver.openInputStream(uri)!!.use { assertEquals(log, it.readBytes().decodeToString()) }

        // The storage report maps the last scan's folders and says how each is treated; it is saved beside the logcat.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Storage report"))
        compose.onNodeWithText("Save").performClick()
        awaitState("storage report", vm) { vm.storageReport.state.value.let { !it.running && (it.saved != null || it.error != null) } }
        val storageReport = vm.storageReport.state.value.saved ?: error("storage report failed: ${vm.storageReport.state.value.error}")
        assertEquals(File(root, SafetyPolicy.LOGCAT_DIR).path, storageReport.parent)
        assertTrue(storageReport.name.matches(Regex("""storage-\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d\.txt""")))
        val reportText = storageReport.readText()
        assertTrue(reportText, reportText.lines().first().endsWith(" storage report"))
        assertTrue(reportText, reportText.lines().any { it.startsWith("Documents/  ") })
        assertTrue(reportText, reportText.contains("WHAT THE LAST SCAN SUGGESTS"))

        // Apps tab: app sizes are read as soon as it opens.
        compose.onNodeWithText("Apps").performClick()
        // The tab reads app sizes when it resumes; Robolectric leaves the navigation entry short of RESUMED, so start
        // the read the way that resume does.
        vm.apps.loadStats()
        awaitState("app sizes", vm) { vm.apps.state.value.everLoaded }
        shot("14-apps")
        assertEquals(setOf("com.example.game", "org.thoughtcrime.securesms", "com.example.notes"), vm.apps.state.value.apps.map { it.packageName }.toSet())
        assertEquals(setOf("com.example.game", "org.thoughtcrime.securesms"), vm.apps.state.value.cacheSelected) // caches of 25 MiB and more

        // App storage: sizes with last use, sorted by the app unused longest.
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("App data 2.5 GiB · cache 40.0 MiB · app 300 MiB · used 6 months ago").assertExists()
        compose.onNodeWithText("Unused longest").performClick()
        shot("14a-app-storage")
        // Clear all data: nothing is picked for you, messengers are never offered, and without Shizuku it points to
        // Android's own Clear storage button.
        compose.onNodeWithText("Clear all data").performClick()
        compose.onNodeWithText("Messages").assertExists()
        compose.onNodeWithText("Without Shizuku, open an app's App info > Storage and tap Clear storage there.", substring = true).assertExists()
        assertTrue(vm.apps.state.value.dataSelected.isEmpty())
        compose.onNodeWithText("Clear data").assertIsNotEnabled()
        shot("14b-app-storage-clear-data")
        compose.onNodeWithContentDescription("Back").performClick()

        // Without Shizuku the app folders scan covers Android/media in-process.
        compose.onNodeWithText("Scan").performClick()
        awaitState("app folder scan", vm) { vm.apps.state.value.folders != null && !vm.apps.state.value.foldersScanning }
        val folders = vm.apps.state.value.folders!!
        assertTrue(folders.items.any { it.kind == AppJunkKind.LOGS && it.packageName == "com.example.logger" })
        assertTrue(folders.items.any { it.kind == AppJunkKind.THUMBNAILS && it.packageName == "com.whatsapp" })
        compose.onNodeWithText("Logs and crash reports").performClick()
        shot("15-app-folders")
        compose.onNodeWithText("Clean").performClick()
        compose.onNodeWithText("Clean app folders?").assertExists()
        compose.onNode(hasText("Clean") and hasAnyAncestor(isDialog())).performClick()
        awaitState("app folder clean", vm) { vm.state.value.applying == null && vm.state.value.outcome is Outcome.Applied }
        shot("16-app-folders-result")
        assertFalse(File(root, "Android/media/com.example.logger/logs/sync-2024-05-01.log").exists())
        assertTrue(File(root, "Android/media/com.example.logger/logs").isDirectory) // the folder itself stays
        assertTrue(File(root, "Android/media/com.whatsapp/WhatsApp/Media/.Thumbs/t1.jpg").exists()) // not selected by default
        assertTrue(File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg").exists())
        compose.onNodeWithText("Done").performClick()

        // Browse app folders like a file manager (Android/media needs no Shizuku), pick a folder and remove it.
        compose.onNodeWithText("Browse").performClick()
        compose.onNodeWithText("Android/media").performClick()
        shot("16a-app-folder-apps")
        compose.onNodeWithText("com.whatsapp").performClick()
        val media = File(root, "Android/media/com.whatsapp/WhatsApp/Media").path
        awaitState("app folder listing", vm) { vm.apps.browser.value.listing?.path?.endsWith("/com.whatsapp") == true }
        compose.onNodeWithText("WhatsApp").performClick()
        awaitState("app folder listing", vm) { vm.apps.browser.value.listing?.path?.endsWith("/WhatsApp") == true }
        compose.onNodeWithText("Media").performClick()
        awaitState("app folder listing", vm) { vm.apps.browser.value.listing?.path == media }
        assertEquals(listOf("WhatsApp Images", ".Thumbs"), vm.apps.browser.value.listing!!.entries.map { it.name }) // largest first
        compose.onAllNodes(isToggleable())[0].performClick() // WhatsApp Images
        shot("16b-app-folder-browser")
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove 1 item?").assertExists()
        shot("16c-app-folder-remove")
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        awaitState("removing a picked folder", vm) { vm.state.value.applying == null && vm.state.value.outcome is Outcome.Applied }
        assertFalse(File(media, "WhatsApp Images").exists())
        assertTrue(File(media, ".Thumbs/t1.jpg").exists())
        assertTrue(File(root, ".StorageSteward/Quarantine").walk().any { it.name == "IMG-1.jpg" })
        awaitState("the folder listed again", vm) { vm.apps.browser.value.listing?.entries?.map { it.name } == listOf(".Thumbs") }
        // It went to the quarantine, so the result offers Undo, which puts it back.
        compose.onNodeWithText("Undo").performClick()
        awaitState("undoing the removal", vm) { vm.state.value.outcome is Outcome.RolledBack }
        assertTrue(File(media, "WhatsApp Images/IMG-1.jpg").exists())
        compose.onNodeWithText("Done").performClick()
        repeat(4) { compose.onNodeWithContentDescription("Back").performClick() } // Media, WhatsApp, app, list of apps
        compose.onNodeWithText("App folders").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()

        // Termux is not installed here: the screen explains how to connect it.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Termux home and packages"))
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Connect Termux").assertExists()
        shot("17-termux-setup")
        scenario.close()
    }
}
