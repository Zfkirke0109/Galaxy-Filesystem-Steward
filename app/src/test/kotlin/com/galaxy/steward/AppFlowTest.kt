package com.galaxy.steward

import android.app.AppOpsManager
import android.content.Context
import android.os.Environment
import android.os.Looper
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.appdata.AppJunkKind
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowStatFs
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

        // Apps tab: without Shizuku the app folders scan covers Android/media in-process.
        compose.onNodeWithText("Apps").performClick()
        shot("14-apps")
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
        compose.onNodeWithContentDescription("Back").performClick()

        // Termux is not installed here: the screen explains how to connect it.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Termux home and packages"))
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Connect Termux").assertExists()
        shot("17-termux-setup")
        scenario.close()
    }
}
