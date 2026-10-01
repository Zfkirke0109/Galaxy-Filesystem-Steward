package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppAreaUsage
import com.galaxy.steward.core.device.DiskStats
import com.galaxy.steward.core.device.PhoneSpace
import com.galaxy.steward.core.device.SharedCount
import com.galaxy.steward.core.report.StorageReportText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 10-01 phone: Android said "Other 90 GiB" while the scan of shared storage saw 11.7 GiB. */
class PhoneSpaceTest {
    private val disk = DiskStats(256 * GIB, 26 * GIB, linkedMapOf("Other" to 90 * GIB, "App data" to 60 * GIB, "Apps" to 30 * GIB))
    private val usage = listOf(
        AppAreaUsage("com.example.game", AppArea.DATA, 40 * GIB, 900, installed = true, protected = false),
        AppAreaUsage("com.example.game", AppArea.OBB, 12 * GIB, 2, installed = true, protected = false),
        AppAreaUsage("com.layla", AppArea.DATA, 15 * GIB, 30, installed = true, protected = false),
        AppAreaUsage("com.wowee.client", AppArea.DATA, 2 * GIB, 5000, installed = false, protected = false),
    )

    @Test
    fun otherIsSplitIntoAppFoldersAndYourFilesWithTheAppsNamed() {
        val phone = PhoneSpace(
            disk = disk,
            shared = SharedCount(total = 82 * GIB, images = GIB, video = GIB, audio = 0, apps = 60 * GIB),
            appFolders = PhoneSpace.appFolders(usage, mapOf("com.example.game" to "Game", "com.layla" to "Layla")),
            scanned = 11 * GIB,
        )
        assertEquals(listOf("com.example.game", "com.layla", "com.wowee.client"), phone.appFolders.map { it.packageName })
        assertEquals(69 * GIB, phone.appFolderBytes)
        val lines = phone.lines()
        // Other now: 82 - 2 = 80 GiB; of it the scanned app folders 69 GiB, the rest 11 GiB; Android's 90 GiB is older.
        val other = lines.single { it.startsWith("\"Other\"") }
        assertTrue(other, other.contains("80.0 GiB now") && other.contains("app folders (Android/data, obb and media) 69.0 GiB") && other.contains("everything else 11.0 GiB"))
        assertTrue(other, other.contains("Android's 90.0 GiB is from its last count"))
        val largest = lines.single { it.startsWith("Largest app folders") }
        assertTrue(largest, largest.startsWith("Largest app folders: Game (com.example.game) 52.0 GiB (data 40.0 GiB, obb 12.0 GiB), Layla (com.layla) 15.0 GiB"))
        assertTrue(largest, largest.contains("com.wowee.client [removed] 2.0 GiB"))

        val text = StorageReportText.render(null, null, listOf("header"), phone = phone)
        assertTrue(text, text.contains("WHOLE PHONE") && text.contains("    Game (com.example.game)  52.0 GiB  (data 40.0 GiB, obb 12.0 GiB)"))
    }

    @Test
    fun withoutTheAppFolderScanAndroidsOwnCountIsUsedAndTheScanSuggested() {
        val phone = PhoneSpace(disk = disk, shared = SharedCount(total = 92 * GIB, images = GIB, video = GIB, audio = 0, apps = 70 * GIB))
        val lines = phone.lines()
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("app folders 70.0 GiB, everything else 20.0 GiB") })
        // 90 GiB then, 90 GiB now: no word about an old count.
        assertTrue(lines.none { it.contains("last count") })
        assertEquals("Scan the app folders through Shizuku on the Apps tab to see which apps these are.", lines.last())
    }
}
