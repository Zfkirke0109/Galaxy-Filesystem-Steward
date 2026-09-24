package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.report.StorageReportText
import com.galaxy.steward.core.termux.TermuxLargeFile
import com.galaxy.steward.core.termux.TermuxReport
import com.galaxy.steward.core.termux.TermuxRootfs
import com.galaxy.steward.core.termux.TermuxUsage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Zips that were already unpacked, and other layout clean-ups that make shared storage easier to read. */
class LayoutCleanupTest {
    private suspend fun scan(fs: TestFs): ScanReport =
        Steward(fs.rootPath, testSettings.copy(minDuplicateBytes = 1L shl 40), TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan()

    private val old = System.currentTimeMillis() - 60 * DAY_MS

    /** Writes a zip of [entries] (name to content) at [rel]. */
    private fun zip(fs: TestFs, rel: String, entries: Map<String, ByteArray>) {
        val f = File(fs.root, rel)
        f.parentFile.mkdirs()
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        f.setLastModified(old)
    }

    private fun bytes(seed: Int, size: Int = 3000) = Random(seed).nextBytes(size)

    @Test
    fun zipsAlreadyUnpackedAreQuarantinedOnlyWhileTheCopyMatches() = runTest {
        TestFs().use { fs ->
            // A GitHub download: one top folder inside, unpacked into a folder of the same name without it.
            zip(fs, "Download/tool-main.zip", mapOf("tool-main/README.md" to bytes(1), "tool-main/docs/guide.txt" to bytes(2)))
            fs.file("Download/tool-main/README.md", bytes(1))
            fs.file("Download/tool-main/docs/guide.txt", bytes(2))
            fs.text("Download/tool-main/notes-i-added.txt", "mine") // extra files in the folder don't matter
            // Photos unpacked next to the zip, into a folder named after it.
            zip(fs, "Documents/Trip.zip", mapOf("a.jpg" to bytes(3), "b.jpg" to bytes(4), "__MACOSX/._a.jpg" to bytes(9, 10)))
            fs.file("Documents/Trip/a.jpg", bytes(3))
            fs.file("Documents/Trip/b.jpg", bytes(4))
            // One file never unpacked: the zip is still the only copy of it.
            zip(fs, "Download/partial.zip", mapOf("x.txt" to bytes(5), "y.txt" to bytes(6)))
            fs.file("Download/partial/x.txt", bytes(5))
            // Unpacked, then edited in place (same size): planning can't tell, the CRC check before the move can.
            zip(fs, "Download/edited.zip", mapOf("save.dat" to bytes(7)))
            fs.file("Download/edited/save.dat", bytes(8))
            fs.ageDirectories()

            val report = scan(fs)
            val zips = report.junk.filter { it.category == JunkCategory.EXTRACTED_ARCHIVES }
            assertEquals(
                setOf("Download/tool-main.zip", "Documents/Trip.zip", "Download/edited.zip"),
                zips.map { it.path.removePrefix(fs.rootPath + "/") }.toSet(),
            )
            assertTrue(zips.all { it.defaultSelected })
            // A zip claimed as clutter is not also filed into Documents/Archives.
            assertTrue(report.organize.none { it.source.endsWith(".zip") && zips.any { z -> z.path == it.source } })

            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals).execute("Clutter", "junk", zips)
            assertEquals(2, summary.quarantined)
            assertEquals(1, summary.skipped)
            assertFalse(fs.exists("Download/tool-main.zip"))
            assertFalse(fs.exists("Documents/Trip.zip"))
            assertTrue(fs.exists("Download/edited.zip")) // the only copy of the original save
            assertTrue(fs.exists("Download/partial.zip"))
            assertTrue(fs.exists("Download/tool-main/docs/guide.txt")) // the unpacked folder stays
            assertTrue(summary.messages.any { it.startsWith("The unpacked copy no longer matches the zip") })

            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertTrue(fs.exists("Download/tool-main.zip"))
            assertTrue(fs.exists("Documents/Trip.zip"))
        }
    }

    @Test
    fun installersBuriedInADownloadedBuildComeUpToItsTopFolder() = runTest {
        TestFs().use { fs ->
            // A CI artifact as it was on the phone: two installers eight folders down, with Gradle's metadata beside them.
            val artifact = "Documents/Software/APKs/RootlessZachDSP-debug-test-only-08a67edb1acc07c1accaeb26147fe89b40b7f218"
            val apk = "$artifact/app/build/outputs/apk"
            fs.random("$apk/rootlessFdroid/debug/RootlessZachDSP-v2.0.0-alpha01-rootless-fdroid-debug.apk", 4000, 1)
            fs.text("$apk/rootlessFdroid/debug/output-metadata.json", "{}")
            fs.random("$apk/androidTest/rootlessFdroid/debug/RootlessZachDSP-v2.0.0-alpha01-rootless-fdroid-debug-androidTest.apk", 3000, 2)
            fs.text("$apk/androidTest/rootlessFdroid/debug/output-metadata.json", "{ }")
            // A real project with the same layout stays exactly as it is.
            fs.text("Download/MyApp/build.gradle", "plugins {}")
            fs.random("Download/MyApp/app/build/outputs/apk/debug/app-debug.apk", 2000, 3)
            // Two flavors with the same file name keep both, named after where they came from.
            fs.random("Download/ci-artifact/app/build/outputs/apk/free/debug/app-debug.apk", 1000, 4)
            fs.random("Download/ci-artifact/app/build/outputs/apk/paid/debug/app-debug.apk", 1100, 5)
            fs.ageDirectories()

            val report = scan(fs)
            val lifts = report.optimize.filter { it.kind == OptimizeKind.LIFT_BUILD_OUTPUTS }
            assertEquals(setOf(fs.path(artifact), fs.path("Download/ci-artifact")), lifts.map { it.path }.toSet())
            assertTrue(lifts.all { it.defaultSelected })
            assertTrue(report.optimize.none { it.kind == OptimizeKind.COLLAPSE_CHAIN }) // the artifact is not also a "chain"

            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals).execute("Optimize", "optimize", lifts)
            assertEquals(0, summary.failed + summary.skipped)
            assertTrue(fs.exists("$artifact/RootlessZachDSP-v2.0.0-alpha01-rootless-fdroid-debug.apk"))
            assertTrue(fs.exists("$artifact/RootlessZachDSP-v2.0.0-alpha01-rootless-fdroid-debug-androidTest.apk"))
            assertFalse(fs.exists("$artifact/app")) // eight empty folders gone
            assertEquals(listOf("free-debug-app-debug.apk", "paid-debug-app-debug.apk"), File(fs.root, "Download/ci-artifact").list()!!.sorted())
            assertTrue(fs.exists("Documents/Software/APKs")) // the category folder itself stays
            assertTrue(fs.exists("Download/MyApp/app/build/outputs/apk/debug/app-debug.apk"))

            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertTrue(fs.exists("$apk/rootlessFdroid/debug/output-metadata.json"))
            assertTrue(fs.exists("$apk/androidTest/rootlessFdroid/debug/RootlessZachDSP-v2.0.0-alpha01-rootless-fdroid-debug-androidTest.apk"))
        }
    }

    @Test
    fun topLevelFoldersOfInstalledAppsStayAndTheRestGetAHome() = runTest {
        TestFs().use { fs ->
            val phone = object : DeviceEnvironment {
                override val deviceLabel = "Test-Phone"
                override val canQueryPackages = true
                override fun installedVersionCode(packageName: String): Long? = if (packageName == "com.still.here") 1 else null
                override fun installedApps() = mapOf(
                    "org.telegram.messenger" to "Telegram",
                    "com.maxmpz.audioplayer" to "Poweramp",
                    "ru.zdevs.zarchiver" to "ZArchiver",
                    "com.google.android.apps.photos" to "Photos",
                )
            }
            fs.random("Telegram/Telegram Images/a.jpg", 900, 1) // the app's own folder, found by its package name
            fs.text("Poweramp/settings.cfg", "eq") // ... and by its label
            fs.random("Wallpapers/w1.jpg", 800, 2) // a category: merges into Pictures/Wallpapers
            fs.random("MangaScans/ch1/p1.png", 700, 3) // mostly images: Pictures/Imported/MangaScans
            fs.random("MangaScans/ch1/p2.png", 700, 4)
            fs.text("Recent Stuff/notes.pdf", "n", mtime = System.currentTimeMillis()) // written this week: may be in use
            fs.text("log/dumpstate.txt", "samsung") // Samsung's own log folder
            fs.text("Photos/x.txt", "a folder named like a generic word never counts as Google Photos'")
            fs.text(".hidden/state", "s")
            fs.ageDirectories()

            val report = Steward(fs.rootPath, testSettings, phone, File(fs.stateDir, "hash-cache.tsv")).scan()
            val moves = report.organize.associate { it.source.removePrefix(fs.rootPath + "/") to it.destination.removePrefix(fs.rootPath + "/") }
            assertEquals("Pictures/Wallpapers", moves["Wallpapers"])
            assertEquals("Pictures/Imported/MangaScans", moves["MangaScans"])
            for (stays in listOf("Telegram", "Poweramp", "Recent Stuff", "log", ".hidden")) assertFalse(stays, stays in moves)
            assertTrue(report.organize.filter { !it.source.startsWith(fs.path("Download")) }.none { it.defaultSelected })
            val owned = report.insights.single { it.title.endsWith("belong to installed apps") }
            assertEquals("They stay where the apps expect them: Poweramp, Telegram.", owned.detail)
            assertTrue(report.insights.any { it.title == "Left in place: Recent Stuff" })

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals")))
                .execute("Organize", "organize", report.organize.filter { it.source == fs.path("MangaScans") || it.source == fs.path("Wallpapers") })
            assertEquals(0, summary.failed + summary.skipped)
            assertTrue(fs.exists("Pictures/Imported/MangaScans/ch1/p2.png"))
            assertTrue(fs.exists("Pictures/Wallpapers/w1.jpg"))
            assertFalse(fs.exists("Wallpapers"))
            assertTrue(fs.exists("Telegram/Telegram Images/a.jpg"))
        }
    }

    @Test
    fun storageReportMapsFoldersAndSaysWhyTheyStay() = runTest {
        TestFs().use { fs ->
            repeat(3) { fs.random("Download/big/part$it.bin", 2000, it) }
            fs.text("Download/tool/.git/HEAD", "ref: refs/heads/main")
            fs.text("Download/tool/main.py", "print(1)")
            fs.dir("Alarms")
            fs.dir("Podcasts")
            fs.random("DCIM/Camera/a.jpg", 5000, 9)
            fs.ageDirectories()
            val scan = scan(fs)
            val home = "/data/data/com.termux/files/home"
            val termux = TermuxReport(
                home = home, prefix = "/data/data/com.termux/files/usr", prootActive = false,
                usage = listOf(TermuxUsage("/data/data/com.termux/files", 20L * GIB), TermuxUsage("$home/.rustup", 3L * GIB)),
                items = emptyList(),
                rootfs = listOf(TermuxRootfs("/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/debian", 5L * GIB, false)),
                largeFiles = listOf(TermuxLargeFile("$home/models/llama.gguf", 4L * GIB, 0)),
                warnings = emptyList(), unsafe = emptyList(),
            )
            val text = StorageReportText.render(scan, termux, listOf("Galaxy Steward test report"), minBytes = 1000, minFiles = 1)
            val lines = text.lines()
            assertEquals("Galaxy Steward test report", lines.first())
            assertTrue(text, lines.any { it.startsWith("Download/  ") })
            assertTrue(text, lines.any { it.startsWith("  tool/  ") && it.endsWith("[project]") })
            assertTrue(text, lines.any { it.startsWith("  big/  5.9 KiB, 3 files") })
            assertTrue(text, lines.contains("Empty Android folders (Android recreates them): Alarms, Podcasts"))
            assertTrue(text, lines.none { it.startsWith("Alarms/") })
            assertTrue(text, lines.any { it.startsWith("scan done in ") })
            assertTrue(text, lines.contains("    ~/.rustup  3.0 GiB"))
            assertTrue(text, lines.contains("    usr/var/lib/proot-distro/installed-rootfs/debian  5.0 GiB"))
            assertTrue(text, lines.contains("    ~/models/llama.gguf  4.0 GiB"))
            assertFalse("no file names from shared storage", text.contains("part0.bin") || text.contains("a.jpg"))
        }
    }

    @Test
    fun longChainsOfEmptyFoldersAreOnlySuggested() = runTest {
        TestFs().use { fs ->
            fs.text("Download/Unpacked/export/data/v1/Scans/page-1.pdf", "p1")
            fs.text("Download/Unpacked/export/data/v1/Scans/page-2.pdf", "p2")
            // A couple of levels of your own naming is a structure, not a chain. Documents/Personal holds only Travel, but
            // it is a home the organizer files into, so it is never the top of a chain either.
            fs.text("Documents/Personal/Travel/2024/Japan/tickets.pdf", "t")
            fs.ageDirectories()

            val report = scan(fs)
            val chains = report.optimize.filter { it.kind == OptimizeKind.COLLAPSE_CHAIN }
            assertEquals(listOf(fs.path("Download/Unpacked")), chains.map { it.path })
            assertFalse(chains.single().defaultSelected)

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Optimize", "optimize", chains)
            assertEquals(0, summary.failed + summary.skipped)
            assertTrue(fs.exists("Download/Unpacked/Scans/page-2.pdf"))
            assertFalse(fs.exists("Download/Unpacked/export"))
            assertTrue(fs.exists("Documents/Personal/Travel/2024/Japan/tickets.pdf"))
        }
    }
}
