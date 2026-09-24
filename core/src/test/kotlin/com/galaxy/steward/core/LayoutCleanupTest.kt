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
            assertTrue(text, lines.any { it.startsWith("  big/  5.9 KiB, 3 files, newest ") })
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
    fun dateFoldersInsideSourceTreesGoBackIntoTheirPackages() = runTest {
        TestFs().use { fs ->
            // What 1.2.0 did to decompiled apps on the phone: sorted by month, then sorted again.
            val jadx = "Download/Projects/Forensics/app.apk/jadx/sources/defpackage"
            fs.text("$jadx/Kept.java", "class Kept {}")
            fs.text("$jadx/2026-08/2026-08/A.java", "class A {}")
            fs.text("$jadx/2026-08/2026-08/B.java", "class B {}")
            fs.text("$jadx/2026-08/2026-08/Kept.java", "class Kept { /* other */ }") // name taken: stays where it is
            val smali = "Download/Projects/qq/smali_classes2/com/tencent/nativeinterface"
            fs.text("$smali/2026-08/C.smali", ".class LC;")
            fs.text("$smali/2026-08/D.smali", ".class LD;")
            // A real year folder of photos is not source code.
            fs.random("Pictures/Imported/2024/IMG_1.jpg", 900, 1)
            fs.ageDirectories()

            val report = scan(fs)
            val repairs = report.optimize.filter { it.kind == OptimizeKind.REPAIR_DATE_FOLDERS }
            assertEquals(setOf(fs.path("$jadx/2026-08"), fs.path("$smali/2026-08")), repairs.map { it.path }.toSet())
            assertTrue(repairs.all { it.defaultSelected })
            assertEquals(2, repairs.single { it.path.startsWith(fs.path(jadx)) }.fileCount)

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Optimize", "optimize", repairs)
            assertEquals(0, summary.failed + summary.skipped)
            assertTrue(fs.exists("$jadx/A.java"))
            assertTrue(fs.exists("$jadx/B.java"))
            assertEquals("class Kept {}", File(fs.root, "$jadx/Kept.java").readText())
            assertTrue(fs.exists("$jadx/2026-08/2026-08/Kept.java"))
            assertTrue(fs.exists("$smali/C.smali"))
            assertFalse(fs.exists("$smali/2026-08")) // emptied and removed
            assertTrue(fs.exists("Pictures/Imported/2024/IMG_1.jpg"))
        }
    }

    @Test
    fun pinnedFoldersAndKeysAreNeverPlannedSoRunsDoNotSkipThemForever() = runTest {
        TestFs().use { fs ->
            // Seen on a phone: two Autopilot runs in a row skipped the same 1,685 moves, and the log didn't say why.
            val pinned = "Download/Projects/Pinned/app/jadx/sources/defpackage"
            fs.text("$pinned/2026-08/A.java", "class A {}")
            fs.text("$pinned/2026-08/B.java", "class B {}")
            val open = "Download/Projects/Open/app/jadx/sources/defpackage"
            fs.text("$open/a.java", "class a {}")
            fs.text("$open/2026-08/A.java", "class A {}") // a.java is taken on shared storage, which ignores case
            fs.text("$open/2026-08/C.java", "class C {}")
            fs.text("$open/2026-08/ClientSecret.java", "class K {}") // a credential-like name never moves
            fs.ageDirectories()

            val settings = testSettings.copy(minDuplicateBytes = 1L shl 40, protectedFolders = listOf("Download/Projects/Pinned"))
            val report = Steward(fs.rootPath, settings, TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan()
            val repairs = report.optimize.filter { it.kind == OptimizeKind.REPAIR_DATE_FOLDERS }
            assertEquals(listOf(fs.path("$open/2026-08")), repairs.map { it.path })
            val moves = repairs.single().operations.filterIsInstance<com.galaxy.steward.core.plan.MoveFileOp>()
            assertEquals(listOf(fs.path("$open/2026-08/C.java")), moves.map { it.src })

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Optimize", "optimize", repairs)
            assertEquals(0, summary.skipped + summary.failed)
            assertTrue(fs.exists("$open/C.java"))
            assertTrue(fs.exists("$pinned/2026-08/A.java"))
        }
    }

    @Test
    fun skippedStepsAreCountedByReason() = runTest {
        TestFs().use { fs ->
            fs.text("Download/Projects/x/jadx/sources/p/2026-08/A.java", "class A {}")
            fs.text("Download/Projects/x/jadx/sources/p/2026-08/B.java", "class B {}")
            fs.ageDirectories()
            val repairs = scan(fs).optimize.filter { it.kind == OptimizeKind.REPAIR_DATE_FOLDERS }
            File(fs.root, "Download/Projects/x/jadx/sources/p/2026-08/A.java").delete()
            File(fs.root, "Download/Projects/x/jadx/sources/p/2026-08/B.java").delete()
            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Optimize", "optimize", repairs)
            assertEquals(2, summary.skipped)
            assertEquals(mapOf("Source is gone" to 2), summary.reasons)
            assertTrue(RunLog.applied("Optimize", "optimize", summary, 10).endsWith("skipped 2, failed 0; why: source is gone 2"))
        }
    }

    /** Writes a tar (gzip-compressed when [gzip]) the way GNU tar does, with a long name for one entry. */
    private fun tar(fs: TestFs, rel: String, entries: Map<String, ByteArray>, gzip: Boolean) {
        val f = File(fs.root, rel)
        f.parentFile.mkdirs()
        val raw = java.io.ByteArrayOutputStream()
        fun header(name: String, size: Int, type: Char) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0, 0, minOf(100, name.length))
            "0000644".toByteArray().copyInto(h, 100)
            String.format("%011o", size).toByteArray().copyInto(h, 124)
            String.format("%011o", old / 1000).toByteArray().copyInto(h, 136)
            h[156] = type.code.toByte()
            "ustar  ".toByteArray().copyInto(h, 257)
            "        ".toByteArray().copyInto(h, 148)
            val sum = h.sumOf { it.toInt() and 0xff }
            String.format("%06o", sum).toByteArray().copyInto(h, 148)
            h[154] = 0
            raw.write(h)
        }
        fun pad(n: Int) = raw.write(ByteArray((512 - n % 512) % 512))
        for ((name, bytes) in entries) {
            if (name.length > 100) {
                header("././@LongLink", name.length + 1, 'L')
                raw.write(name.toByteArray() + 0)
                pad(name.length + 1)
            }
            header(name, bytes.size, '0')
            raw.write(bytes)
            pad(bytes.size)
        }
        raw.write(ByteArray(1024))
        if (gzip) java.util.zip.GZIPOutputStream(f.outputStream()).use { it.write(raw.toByteArray()) } else f.writeBytes(raw.toByteArray())
        f.setLastModified(old)
    }

    @Test
    fun tarBackupsOfFoldersThatAreStillThereAreQuarantinedOnlyWhileTheyMatch() = runTest {
        TestFs().use { fs ->
            val long = "deep/" + "x".repeat(120) + ".txt"
            val files = mapOf("notes.txt" to bytes(1), "sub/data.bin" to bytes(2, 9000), long to bytes(3))
            tar(fs, "Documents/Backups/Project.tar.gz", files.mapKeys { "Project/${it.key}" }, gzip = true)
            tar(fs, "Documents/Backups/Other.tar", files, gzip = false)
            files.forEach { (name, data) -> fs.file("Documents/Backups/Project/$name", data) }
            files.forEach { (name, data) -> fs.file("Documents/Backups/Other/$name", data) }
            fs.ageDirectories()

            val found = scan(fs).junk.filter { it.category == JunkCategory.EXTRACTED_ARCHIVES }
            assertEquals(setOf(fs.path("Documents/Backups/Project.tar.gz"), fs.path("Documents/Backups/Other.tar")), found.map { it.path }.toSet())

            // The unpacked copy changed after the scan: that archive stays.
            fs.file("Documents/Backups/Other/sub/data.bin", bytes(9, 9000))
            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Clutter", "junk", found)
            assertEquals(1, summary.quarantined)
            assertFalse(fs.exists("Documents/Backups/Project.tar.gz"))
            assertTrue(fs.exists("Documents/Backups/Other.tar"))
            assertTrue(fs.exists("Documents/Backups/Project/$long"))
        }
    }

    @Test
    fun heapDumpsAndOldRunsOfAToolAreClutter() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            fs.random("Documents/Reports/leakcanary-com.example/2026-09-01_heap.hprof", 4000, 1, mtime = now - 10 * DAY_MS)
            fs.random("Documents/Reports/leakcanary-com.example/today.hprof", 4000, 2, mtime = now - 3_600_000)
            // Three runs of one tool from three weeks ago, and one from yesterday.
            listOf("20260901-101500-111", "20260902-101500-222", "20260903-101500-333").forEachIndexed { i, run ->
                fs.random("Documents/Ultimate-Cleanup/runs/$run/report.log", 2000 + i, 10 + i, mtime = now - (30 - i) * DAY_MS)
            }
            fs.random("Documents/Ultimate-Cleanup/runs/20260923-080000-444/report.log", 2000, 20, mtime = now - DAY_MS)
            // Dated folders of photos are not runs.
            listOf("2024-01-01 10.00", "2024-02-01 10.00", "2024-03-01 10.00").forEachIndexed { i, d ->
                fs.random("Pictures/Trips/$d/IMG_$i.jpg", 900, 30 + i)
            }
            fs.ageDirectories()
            val junk = scan(fs).junk
            assertEquals(listOf("2026-09-01_heap.hprof"), junk.filter { it.category == JunkCategory.HEAP_DUMPS }.map { it.title })
            val runs = junk.filter { it.category == JunkCategory.OLD_RUNS }
            assertEquals(listOf("20260901-101500-111", "20260902-101500-222", "20260903-101500-333"), runs.map { it.title }.sorted())
            assertTrue(runs.all { !it.defaultSelected && it.note.contains("20260923-080000-444 is newer") })
        }
    }

    @Test
    fun installersOfInstalledAppsAnywhereAndOlderInstallersAreOffered() = runTest {
        TestFs().use { fs ->
            fs.random("MT2/apks/Layla.apk", 5000, 1)
            fs.random("Documents/Software/APKs/tool-1.0.apk", 3000, 2)
            fs.random("Documents/Software/APKs/tool-1.2.apk", 3000, 3)
            fs.random("Download/tool-1.1.apk", 3000, 4)
            fs.ageDirectories()
            val env = object : DeviceEnvironment {
                override val deviceLabel = "Test-Phone"
                override fun installedVersionCode(packageName: String): Long? = if (packageName == "ai.layla") 40 else null
                override fun apkInfo(path: String): ApkInfo? = when (path.substringAfterLast('/')) {
                    "Layla.apk" -> ApkInfo("ai.layla", 40, "4.0")
                    "tool-1.0.apk" -> ApkInfo("com.example.tool", 10, "1.0")
                    "tool-1.1.apk" -> ApkInfo("com.example.tool", 11, "1.1")
                    "tool-1.2.apk" -> ApkInfo("com.example.tool", 12, "1.2")
                    else -> null
                }
            }
            val junk = Steward(fs.rootPath, testSettings.copy(minDuplicateBytes = 1L shl 40), env, null).scan().junk
            assertEquals(listOf(fs.path("MT2/apks/Layla.apk")), junk.filter { it.category == JunkCategory.INSTALLED_APKS }.map { it.path })
            val older = junk.filter { it.category == JunkCategory.OLD_INSTALLERS }
            assertEquals(setOf("tool-1.0.apk", "tool-1.1.apk"), older.map { it.title }.toSet())
            assertTrue(older.all { it.note.endsWith("1.2 is in APKs") && !it.defaultSelected })
        }
    }

    @Test
    fun coldTextFoldersArePackedLosslesslyAndUnpackedByUndo() = runTest {
        TestFs().use { fs ->
            val longAgo = System.currentTimeMillis() - 200 * DAY_MS
            // Old logcat exports: text, untouched for months.
            repeat(30) { i -> fs.text("Documents/Reports/LogcatX/2025/logcat-$i.txt", "I/Tag: line $i\n".repeat(40_000 + i), mtime = longAgo) }
            fs.dir("Documents/Reports/LogcatX/empty-dir", mtime = longAgo)
            // Photos are never packed, however old; a folder in use isn't either.
            repeat(30) { i -> fs.random("Documents/Trips/2019/IMG_$i.jpg", 400_000, i, mtime = longAgo) }
            repeat(30) { i -> fs.text("Documents/Reports/Current/log-$i.txt", "x".repeat(400_000), mtime = System.currentTimeMillis() - 3 * DAY_MS) }
            fs.ageDirectories()
            val packs = scan(fs).optimize.filter { it.kind == OptimizeKind.PACK_COLD_FOLDER }
            assertEquals(listOf(fs.path("Documents/Reports/LogcatX")), packs.map { it.path })
            val item = packs.single()
            assertFalse(item.defaultSelected)
            assertTrue(item.reclaimBytes > 8 * MIB)

            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals).execute("Pack", "optimize", packs)
            assertEquals(1, summary.packed)
            assertEquals(0, summary.skipped + summary.failed)
            assertFalse(fs.exists("Documents/Reports/LogcatX"))
            val zip = File(fs.root, "Documents/Reports/LogcatX.zip")
            assertTrue(zip.length() < 2 * MIB)
            java.util.zip.ZipFile(zip).use { z ->
                assertEquals("I/Tag: line 7\n".repeat(40_007), z.getInputStream(z.getEntry("LogcatX/2025/logcat-7.txt")).readBytes().decodeToString())
                assertTrue(z.getEntry("LogcatX/empty-dir/") != null)
                assertEquals(longAgo / 2000, z.getEntry("LogcatX/2025/logcat-7.txt").time / 2000)
            }

            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertFalse(zip.exists())
            assertEquals("I/Tag: line 7\n".repeat(40_007), File(fs.root, "Documents/Reports/LogcatX/2025/logcat-7.txt").readText())
            assertTrue(File(fs.root, "Documents/Reports/LogcatX/empty-dir").isDirectory)
        }
    }

    @Test
    fun whatPackingSavesIsMeasuredNotGuessedFromNames() = runTest {
        TestFs().use { fs ->
            val longAgo = System.currentTimeMillis() - 200 * DAY_MS
            // ".log" files that are encrypted (random bytes): the name says text, the bytes say nothing to gain.
            repeat(30) { i -> fs.random("Documents/Vault/logs/session-$i.log", 600_000, i, mtime = longAgo) }
            // ".dat" files that are plain text inside: the name says little, the bytes say a lot.
            repeat(30) { i -> fs.text("Documents/GameSaves/slot-$i.dat", "score=$i;level=3;\n".repeat(40_000), mtime = longAgo) }
            fs.ageDirectories()
            val packs = scan(fs).optimize.filter { it.kind == OptimizeKind.PACK_COLD_FOLDER }
            assertEquals(listOf(fs.path("Documents/GameSaves")), packs.map { it.path })
            val item = packs.single()
            assertTrue(item.detail, "measured" in item.detail)
            // Measured: nearly all of it; a guess from ".dat" would have said 30%.
            val total = File(fs.root, "Documents/GameSaves").listFiles()!!.sumOf { it.length() }
            assertTrue("${item.savesBytes} of $total", item.savesBytes > total * 9 / 10)
        }
    }

    @Test
    fun aFolderChangedAfterTheScanIsNotPacked() = runTest {
        TestFs().use { fs ->
            val longAgo = System.currentTimeMillis() - 200 * DAY_MS
            repeat(30) { i -> fs.text("Documents/Exports/old-$i.csv", "a,b,c\n".repeat(100_000), mtime = longAgo) }
            fs.ageDirectories()
            val packs = scan(fs).optimize.filter { it.kind == OptimizeKind.PACK_COLD_FOLDER }
            fs.text("Documents/Exports/old-3.csv", "changed")
            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Pack", "optimize", packs)
            assertEquals(mapOf("Changed since the scan" to 1), summary.reasons)
            assertTrue(fs.exists("Documents/Exports/old-3.csv"))
            assertFalse(fs.exists("Documents/Exports.zip"))
        }
    }

    @Test
    fun dateFoldersOfLibrariesAreOnlySuggested() = runTest {
        TestFs().use { fs ->
            val october2020 = java.time.LocalDate.of(2020, 10, 12).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            repeat(25) { fs.random("Documents/Archives/ViPER4Android-Presets/Full/Kernel/2020-10/preset$it.vdc", 300, it, mtime = october2020) }
            // Someone's own month folder, with files from other months: not the old sorting.
            repeat(25) { fs.text("Documents/Invoices/2024-03/invoice$it.pdf", "i$it", mtime = october2020 + it * 40 * DAY_MS) }
            fs.ageDirectories()
            val repairs = scan(fs).optimize.filter { it.kind == OptimizeKind.REPAIR_DATE_FOLDERS }
            assertEquals(listOf(fs.path("Documents/Archives/ViPER4Android-Presets/Full/Kernel/2020-10")), repairs.map { it.path })
            assertFalse(repairs.single().defaultSelected)
            assertEquals(25, repairs.single().fileCount)
        }
    }

    @Test
    fun aBigFreshLogFolderIsExplained() = runTest {
        TestFs().use { fs ->
            val big = File(fs.root, "log/dumpstate.zip")
            big.parentFile.mkdirs()
            java.io.RandomAccessFile(big, "rw").use { it.setLength(600L * 1024 * 1024) } // sparse: size without the bytes
            fs.ageDirectories()
            val insights = scan(fs).insights
            assertTrue(insights.any { it.title == "log holds 600 MiB, none of it 3 days old" })
        }
    }

    @Test
    fun aDateFolderIsNeverSortedIntoItself() = runTest {
        TestFs().use { fs ->
            val august = java.time.LocalDate.of(2026, 8, 6).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            repeat(6) { fs.random("Pictures/Imported/2026-08/IMG_$it.jpg", 500, it, mtime = august) }
            repeat(6) { fs.random("Pictures/Imported/Mixed/IMG_$it.jpg", 500, it + 10, mtime = august - it * 200 * DAY_MS) }
            repeat(8) { fs.text("Download/Projects/app/src/main/java/com/x/C$it.java", "class C$it {}") }
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings.copy(flatDirThreshold = 4), TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan()
            val buckets = report.optimize.filter { it.kind == OptimizeKind.BUCKET_FLAT_DIR }.map { it.path }
            assertEquals(listOf(fs.path("Pictures/Imported/Mixed")), buckets)
            // Source folders are flat by nature: no "files in one folder" advice about them.
            assertTrue(report.insights.none { it.title.endsWith("files in one folder") && it.path!!.contains("/src/") })
        }
    }

    @Test
    fun recycleBinsAndDaysOldSystemLogsAreClutter() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            fs.random("MT2/.recycle/6KQS080B8AXJ/_StorageSteward/run.tsv", 3000, 1) // deleted in MT Manager
            fs.text("MT2/.recycle/9ZZ/signing.jks", "key") // a deleted key still never goes anywhere
            fs.random("MT2/apks/tool.apk", 2000, 2) // MT Manager's own working folder stays
            fs.random("log/dumpstate_2026-09-18.zip", 4000, 3, mtime = now - 5 * DAY_MS)
            fs.random("log/ewlogd/current.log", 500, 4, mtime = now - 3_600_000) // still being written
            fs.ageDirectories()

            val junk = scan(fs).junk
            val bins = junk.filter { it.category == JunkCategory.RECYCLE_BINS }
            assertEquals(listOf(fs.path("MT2/.recycle/6KQS080B8AXJ")), bins.map { it.path })
            assertEquals("_StorageSteward, deleted in MT Manager", bins.single().note)
            assertEquals(listOf(fs.path("log/dumpstate_2026-09-18.zip")), junk.filter { it.category == JunkCategory.OLD_LOGS }.map { it.path })
        }
    }

    @Test
    fun foldersInsideTheWrongHomeAreFiledWhereTheyBelong() = runTest {
        TestFs().use { fs ->
            // As they were on the phone.
            repeat(3) { fs.random("Documents/Archives/ViPER4Android-Presets-v2.2.0-Full/Full/preset$it.vdc", 300, it) }
            fs.random("Documents/Audio-DSP/leakcanary-com.example.debug/heap1.hprof", 900, 5)
            fs.random("Documents/Reports/Diagnostics/leakcanary-com.example.debug/heap2.hprof", 800, 6)
            fs.random("Documents/Software/APKs/apk/tool.apk", 700, 7)
            fs.random("Documents/Software/APKs/app.apk", 600, 8)
            fs.random("Documents/Audio-DSP/Test-Corpora/sweep.wav", 500, 9) // belongs where it is
            fs.ageDirectories()

            val report = scan(fs)
            fun move(rel: String) = report.organize.singleOrNull { it.source == fs.path(rel) }
            val apk = move("Documents/Software/APKs/apk")!!
            assertEquals(fs.path("Documents/Software/APKs"), apk.destination)
            assertTrue(apk.defaultSelected)
            val viper = move("Documents/Archives/ViPER4Android-Presets-v2.2.0-Full")!!
            assertEquals(fs.path("Documents/Audio-DSP/ViPER4Android-Presets-v2.2.0-Full"), viper.destination)
            assertFalse(viper.defaultSelected)
            assertEquals(fs.path("Documents/Reports/Diagnostics/leakcanary-com.example.debug"), move("Documents/Audio-DSP/leakcanary-com.example.debug")!!.destination)
            assertEquals(null, move("Documents/Audio-DSP/Test-Corpora"))
            assertEquals(null, move("Documents/Reports/Diagnostics/leakcanary-com.example.debug"))
            // The preset folder's redundant "Full" inside "…-Full" is flattened.
            assertTrue(report.optimize.any { it.kind == OptimizeKind.FLATTEN_WRAPPER && it.path == fs.path("Documents/Archives/ViPER4Android-Presets-v2.2.0-Full") })

            val summary = ActionExecutor(fs.rootPath, JournalStore(File(fs.stateDir, "journals"))).execute("Organize", "organize", report.organize)
            assertEquals(0, summary.failed + summary.skipped)
            assertTrue(fs.exists("Documents/Software/APKs/tool.apk"))
            assertTrue(fs.exists("Documents/Reports/Diagnostics/leakcanary-com.example.debug/heap1.hprof"))
            assertTrue(fs.exists("Documents/Reports/Diagnostics/leakcanary-com.example.debug/heap2.hprof"))
            assertTrue(fs.exists("Documents/Audio-DSP/ViPER4Android-Presets-v2.2.0-Full/Full/preset0.vdc"))
        }
    }

    @Test
    fun keysAndSourceTreesInSharedStorageGetOnePieceOfAdviceEach() = runTest {
        TestFs().use { fs ->
            fs.text("Documents/Software/APKs/rootlesszachdsp.jks", "k")
            fs.text("Download/angle-release-credentials.txt", "k")
            fs.text("Download/Projects/app/src/main/res/raw/cacert.pem", "part of the code")
            repeat(20_000) { fs.text("Download/Projects/app/src/main/java/C$it.java", "class C$it {}") }
            fs.random("DCIM/Camera/a.jpg", 900, 1)
            fs.ageDirectories()

            val insights = scan(fs).insights
            val keys = insights.single { it.title.endsWith("in shared storage") }
            assertEquals("2 key or credential files sit in shared storage", keys.title)
            assertTrue(keys.detail, keys.detail.startsWith("Any app with All files access can read them: angle-release-credentials.txt, rootlesszachdsp.jks."))
            assertTrue(insights.none { it.title.startsWith("Left in place: ") && it.title.endsWith(".jks") })
            val code = insights.single { it.title.endsWith("of your files are source code") }
            assertEquals("99% of your files are source code", code.title)
            assertEquals(fs.path("Download/Projects"), code.path)
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
