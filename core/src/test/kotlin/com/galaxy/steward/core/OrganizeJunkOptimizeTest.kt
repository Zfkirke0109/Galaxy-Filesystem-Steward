package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.optimize.VolumeSpace
import com.galaxy.steward.core.organize.KeywordRule
import com.galaxy.steward.core.organize.OrganizePlanner
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.plan.Severity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrganizeJunkOptimizeTest {
    private suspend fun scan(fs: TestFs, settings: StewardSettings = testSettings, space: VolumeSpace? = null): ScanReport =
        Steward(fs.rootPath, settings, TestEnv, File(fs.stateDir, "hash-cache.tsv")).scan(space)

    private fun ScanReport.moveFor(fs: TestFs, rel: String) = organize.firstOrNull { it.source == fs.path(rel) }

    @Test
    fun downloadInboxIsFiledSemantically() = runTest {
        TestFs().use { fs ->
            fs.text("Download/Bank Statement March.pdf", "a")
            fs.text("Download/manual.pdf", "b")
            fs.random("Download/app-release.apk", 2000, 1)
            fs.random("Download/Screenshot_20240101.png", 2000, 2)
            fs.random("Download/backup-2024.zip", 2000, 3)
            fs.random("Download/firmware_boot.img", 2000, 4)
            fs.text("Download/id_ed25519", "secret")
            fs.text("Download/still-downloading.crdownload", "x")
            val report = scan(fs, testSettings.copy(minDuplicateBytes = MIB))

            fun dest(rel: String) = report.moveFor(fs, rel)?.destination?.removePrefix(fs.rootPath + "/")
            assertEquals("Documents/Personal/Finance/Bank Statement March.pdf", dest("Download/Bank Statement March.pdf"))
            assertEquals("Documents/Reference/manual.pdf", dest("Download/manual.pdf"))
            assertEquals("Documents/Software/APKs/app-release.apk", dest("Download/app-release.apk"))
            assertEquals("Pictures/Screenshots/Screenshot_20240101.png", dest("Download/Screenshot_20240101.png"))
            assertEquals("Documents/Backups/backup-2024.zip", dest("Download/backup-2024.zip"))
            assertEquals("Documents/Android-Device/Test-Phone/Firmware/firmware_boot.img", dest("Download/firmware_boot.img"))
            assertNull("credentials never move", dest("Download/id_ed25519"))
            assertNull("in-progress downloads never move", dest("Download/still-downloading.crdownload"))
        }
    }

    @Test
    fun foldersMoveAsUnitsAndProjectsStayPut() = runTest {
        TestFs().use { fs ->
            fs.random("Download/Holiday/a.jpg", 5000, 1)
            fs.random("Download/Holiday/b.jpg", 5000, 2)
            fs.text("Download/Holiday/list.txt", "packing list")
            fs.text("Download/my-tool/.git/HEAD", "ref: refs/heads/main")
            fs.text("Download/my-tool/main.py", "print(1)")
            fs.text("Download/Documents/letter.docx", "dear reader")
            fs.text("Download/APKs/old.apk", "pk-bytes")
            fs.ageDirectories()
            val report = scan(fs)

            val holiday = report.moveFor(fs, "Download/Holiday")!!
            assertEquals(fs.path("Pictures/Imported/Holiday"), holiday.destination)
            assertTrue(holiday.isDirectory)
            assertNull(report.moveFor(fs, "Download/my-tool"))
            assertTrue(report.insights.any { it.path == fs.path("Download/my-tool") })
            // Generic wrapper dissolves; its children are filed individually.
            assertEquals(fs.path("Documents/Reference/letter.docx"), report.moveFor(fs, "Download/Documents/letter.docx")?.destination)
            assertEquals(fs.path("Documents/Software/APKs"), report.moveFor(fs, "Download/APKs")?.destination)
        }
    }

    @Test
    fun userFoldersInsideDocumentsStayWhileWrappersDissolve() = runTest {
        TestFs().use { fs ->
            fs.text("Documents/Misc/keep-me.txt", "mine")
            fs.text("Documents/Documents/stray.pdf", "stray")
            fs.dir("Download/Empty")
            fs.ageDirectories()
            val report = scan(fs)
            assertNull(report.moveFor(fs, "Documents/Misc/keep-me.txt"))
            assertNull(report.moveFor(fs, "Documents/Misc"))
            assertEquals(fs.path("Documents/Reference/stray.pdf"), report.moveFor(fs, "Documents/Documents/stray.pdf")?.destination)
            assertNull("empty folders are clutter, not filing", report.moveFor(fs, "Download/Empty"))
        }
    }

    @Test
    fun organizeApplyAndRollbackRoundTrip() = runTest {
        TestFs().use { fs ->
            fs.text("Download/receipt-coffee.pdf", "one")
            fs.random("Download/Album/song1.mp3", 3000, 1)
            fs.random("Download/Album/song2.mp3", 3000, 2)
            fs.text("Documents/Personal/Finance/receipt-coffee.pdf", "different")
            fs.ageDirectories()
            val report = scan(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals).execute("Organize", "organize", report.organize)

            assertTrue(fs.exists("Music/Imported/Album/song1.mp3"))
            assertFalse(fs.exists("Download/Album"))
            // Name collision with different content gets a hash suffix, never an overwrite.
            val finance = File(fs.root, "Documents/Personal/Finance").list()!!.sorted()
            assertEquals(2, finance.size)
            assertEquals("different", File(fs.root, "Documents/Personal/Finance/receipt-coffee.pdf").readText())

            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertEquals("one", File(fs.path("Download/receipt-coffee.pdf")).readText())
            assertTrue(fs.exists("Download/Album/song2.mp3"))
            assertFalse("folders created by the run are removed again", fs.exists("Music/Imported"))
        }
    }

    @Test
    fun identicalFileAlreadyAtDestinationIsDedupedDuringMove() = runTest {
        TestFs().use { fs ->
            fs.text("Download/manual.pdf", "same")
            fs.text("Documents/Reference/manual.pdf", "same")
            val tree = com.galaxy.steward.core.scan.TreeScanner(fs.rootPath, testSettings).scan()
            val moves = OrganizePlanner(testSettings, TestEnv).plan(tree).moves
            val summary = ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Organize", "organize", moves)
            assertEquals(1, summary.deduped)
            assertFalse(fs.exists("Download/manual.pdf"))
        }
    }

    @Test
    fun recentFilesAreLeftAlone() = runTest {
        TestFs().use { fs ->
            fs.text("Download/just-saved.pdf", "x", mtime = System.currentTimeMillis())
            assertNull(scan(fs).moveFor(fs, "Download/just-saved.pdf"))
        }
    }

    @Test
    fun customRulesWinOverBuiltIns() = runTest {
        TestFs().use { fs ->
            fs.text("Download/Invoice ACME 42.pdf", "x")
            val rule = KeywordRule("Work", listOf("acme"), "Documents/Work/ACME", setOf("pdf"))
            val report = scan(fs, testSettings.copy(customRules = listOf(rule)))
            assertEquals(fs.path("Documents/Work/ACME/Invoice ACME 42.pdf"), report.moveFor(fs, "Download/Invoice ACME 42.pdf")?.destination)
        }
    }

    @Test
    fun junkCategoriesAreDetected() = runTest {
        TestFs().use { fs ->
            fs.text("Download/big.iso.crdownload", "partial")
            fs.text("log/dumpstate_old.txt", "log")
            fs.text("Documents/empty-marker.txt", "")
            fs.text("DCIM/.thumbnails/1.jpg", "thumb")
            fs.text("Pictures/.trashed-1700000000-old.jpg", "trash")
            fs.dir("Download/Old/Empty/Deeper")
            fs.dir("Movies")
            fs.ageDirectories()
            val junk = scan(fs).junk
            fun cat(c: JunkCategory) = junk.filter { it.category == c }.map { it.path.removePrefix(fs.rootPath + "/") }

            assertEquals(listOf("Download/big.iso.crdownload"), cat(JunkCategory.STALE_DOWNLOADS))
            assertEquals(listOf("log/dumpstate_old.txt"), cat(JunkCategory.OLD_LOGS))
            assertEquals(listOf("Documents/empty-marker.txt"), cat(JunkCategory.ZERO_BYTE_FILES))
            assertEquals(listOf("DCIM/.thumbnails"), cat(JunkCategory.THUMBNAIL_CACHES))
            assertEquals(listOf("Pictures/.trashed-1700000000-old.jpg"), cat(JunkCategory.TRASHED_MEDIA))
            // The top-most empty folder is reported once; standard Android folders never are.
            assertEquals(listOf("Download/Old"), cat(JunkCategory.EMPTY_FOLDERS))
            val empty = junk.first { it.category == JunkCategory.EMPTY_FOLDERS }
            assertEquals(3, empty.emptyDirs.size)
        }
    }

    @Test
    fun junkIsQuarantinedAndEmptyTreesRemoved() = runTest {
        TestFs().use { fs ->
            fs.text("Download/big.iso.crdownload", "partial")
            fs.dir("Download/Old/Empty/Deeper")
            fs.ageDirectories()
            val report = scan(fs)
            val journals = JournalStore(File(fs.stateDir, "journals"))
            val summary = ActionExecutor(fs.rootPath, journals).execute("Junk", "junk", report.junk)
            assertEquals(1, summary.quarantined)
            assertEquals(3, summary.removedDirs)
            assertFalse(fs.exists("Download/Old"))
            assertTrue(fs.exists("Download"))
            RollbackEngine(fs.rootPath, journals).rollback(summary.runId)
            assertTrue(fs.exists("Download/Old/Empty/Deeper"))
            assertTrue(fs.exists("Download/big.iso.crdownload"))
        }
    }

    @Test
    fun redundantNestedFolderIsFlattened() = runTest {
        TestFs().use { fs ->
            fs.text("Download/Project-X-main/Project-X-main/readme.txt", "x")
            fs.text("Download/Project-X-main/Project-X-main/docs/guide.txt", "y")
            fs.text("Documents/Taxes/2024/return.pdf", "z")
            fs.ageDirectories()
            val report = scan(fs)
            val flatten = report.optimize.filter { it.kind == OptimizeKind.FLATTEN_WRAPPER }
            assertEquals(listOf(fs.path("Download/Project-X-main")), flatten.map { it.path })

            ActionExecutor(fs.rootPath, JournalStore(fs.stateDir)).execute("Optimize", "optimize", flatten)
            assertTrue(fs.exists("Download/Project-X-main/readme.txt"))
            assertTrue(fs.exists("Download/Project-X-main/docs/guide.txt"))
            assertFalse(fs.exists("Download/Project-X-main/Project-X-main"))
        }
    }

    @Test
    fun packagePathsInsideDecompiledTreesAreNeverFlattened() = runTest {
        TestFs().use { fs ->
            // Seen on a real phone: apktool output with a Java package that repeats its own name.
            val pkg = "Download/Projects/QQ/extracted/qq_src/smali_classes17/com/tencent/qqexpand/userConfig"
            fs.text("$pkg/userConfig/UserConfig\$Options.smali", ".class public Lcom/tencent/qqexpand/userConfig/userConfig/UserConfig;")
            fs.text("Download/Projects/QQ/extracted/qq_src/apktool.yml", "version: 2.9.3")
            // Same shape without apktool.yml or smali files: a plain package folder in a source checkout.
            fs.text("Download/lib-src/src/main/java/org/demo/model/model/Model.java", "class Model {}")
            // Resources below a code-tree folder are left alone too.
            fs.text("Download/tool/node_modules/pkg/pkg/readme.txt", "x")
            fs.ageDirectories()
            val report = scan(fs)
            assertTrue(report.optimize.none { it.kind == OptimizeKind.FLATTEN_WRAPPER })
        }
    }

    @Test
    fun decompiledAppsAndSourceTreesAreKeptAsTheyAre() = runTest {
        TestFs().use { fs ->
            val settings = testSettings.copy(flatDirThreshold = 10)
            // Seen on a real phone: month folders were created inside these trees and duplicates were removed from them.
            val smali = "Download/QQ-Localization/extracted/qq_src/smali_classes19/com/tencent/mapsdk/internal"
            repeat(12) { fs.text("$smali/C$it.smali", ".class public Lcom/tencent/mapsdk/internal/C$it;") }
            val jadxA = "Download/Forensics/analysis/shizuku-v13.8.0.apk/jadx"
            val jadxB = "Download/Forensics/analysis/installed_shizuku_base.apk/jadx"
            for (base in listOf(jadxA, jadxB)) {
                repeat(12) { fs.text("$base/sources/rikka/shizuku/S$it.java", "class S$it { /* identical in both analyses */ }") }
                fs.text("$base/resources/AndroidManifest.xml", "<manifest/>")
            }
            // A plain source dump without any markers: mostly code files.
            repeat(60) { fs.text("Download/lib-dump/pkg/M$it.py", "print($it)") }
            fs.text("Download/lib-dump/notes.pdf", "notes")
            // A folder named like a workspace is treated as development work.
            fs.text("Download/Projects/tool/readme.txt", "readme")
            fs.ageDirectories()
            val report = scan(fs, settings)

            val touched = report.allItems().flatMap { item -> item.operations }.map { op ->
                when (op) {
                    is com.galaxy.steward.core.plan.MoveFileOp -> op.src
                    is com.galaxy.steward.core.plan.MoveDirOp -> op.src
                    is com.galaxy.steward.core.plan.DeleteDuplicateOp -> op.path
                    is com.galaxy.steward.core.plan.QuarantineOp -> op.path
                    is com.galaxy.steward.core.plan.RemoveEmptyDirOp -> op.path
                }
            }
            for (protected in listOf("Download/QQ-Localization", "Download/Forensics", "Download/lib-dump", "Download/Projects")) {
                assertTrue("$protected must not be changed", touched.none { it.startsWith(fs.path(protected)) })
            }
            // The identical jadx sources are still recognised, but no copy is offered for removal.
            assertTrue(report.duplicates.isEmpty() || report.duplicates.all { it.removals.isEmpty() })
            assertTrue(report.insights.any { it.title.startsWith("Left in place") && it.detail.contains("source code") })
        }
    }

    @Test
    fun onlyPhotoAndVideoDumpsAreBucketedByDate() = runTest {
        TestFs().use { fs ->
            val settings = testSettings.copy(flatDirThreshold = 10)
            repeat(12) { fs.random("Documents/Archives/ViPER4Android-Presets/Full/Kernel/Preset $it.irs", 64, 100 + it) }
            repeat(12) { fs.random("Music/Imported/Track $it.mp3", 64, 200 + it) }
            repeat(12) { fs.random("Download/Twitter dump/VID_$it.mp4", 64, 300 + it) }
            fs.ageDirectories()
            val buckets = scan(fs, settings).optimize.filter { it.kind == OptimizeKind.BUCKET_FLAT_DIR }.map { it.path }
            assertEquals(listOf(fs.path("Download/Twitter dump")), buckets)
        }
    }

    @Test
    fun oversizedFlatFolderIsBucketedByYear() = runTest {
        TestFs().use { fs ->
            val settings = testSettings.copy(flatDirThreshold = 10)
            repeat(12) { fs.text("Pictures/Imported/img$it.jpg", "x$it") }
            fs.ageDirectories()
            val item = scan(fs, settings).optimize.single { it.kind == OptimizeKind.BUCKET_FLAT_DIR }
            assertEquals(12, item.fileCount)
            assertTrue(item.defaultSelected)
        }
    }

    @Test
    fun lowFreeSpaceRaisesWarning() = runTest {
        TestFs().use { fs ->
            val report = scan(fs, space = VolumeSpace(freeBytes = 5 * GIB, totalBytes = 256 * GIB))
            assertTrue(report.insights.any { it.severity == Severity.WARNING })
        }
    }
}
