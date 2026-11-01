package com.galaxy.steward.core

import com.galaxy.steward.core.dedupe.FolderSketch
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.termux.TermuxScript
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Near-copies of folders, found from sketches of their file lists. */
class NearCopyTest {
    @Test
    fun theAppHashesLikeTheTermuxScript() {
        val bash = try {
            ProcessBuilder("bash", "--version").start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
        requireTool("bash is needed to compare with the Termux script", bash)
        val program = TermuxScript.text.substringAfter("SKETCH_AWK='").substringBefore("'\n\n")
        val lines = listOf("sources/com/a/B.java\t1234", "Übung/файл.txt\t5", "x\t0", "deep/" + "n".repeat(300) + "\t99999999999")
        val input = File.createTempFile("sketch", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        try {
            val p = ProcessBuilder("bash", "-c", "LC_ALL=C awk \"\$1\" \"\$2\"", "sketch", program, input.path).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString().trim().lines().map { it.toLong() }
            assertEquals(0, p.waitFor())
            assertEquals(lines.map(FolderSketch::hash), out)
        } finally {
            input.delete()
        }
    }

    @Test
    fun similarityIsEstimatedFromTheSmallestHashes() {
        fun sketch(range: IntRange) = FolderSketch.BottomK().apply { range.forEach { add(FolderSketch.hash("f$it\t1")) } }.sorted()
        val a = sketch(0 until 1000)
        assertEquals(1.0, FolderSketch.similarity(a, a), 0.0)
        assertEquals(0.0, FolderSketch.similarity(a, sketch(5000 until 6000)), 0.0)
        // 800 of 1200 distinct files in common: Jaccard 2/3.
        assertEquals(2.0 / 3, FolderSketch.similarity(a, sketch(200 until 1200)), 0.15)
        assertEquals(FolderSketch.K, a.size)
        assertTrue(a.toList() == a.sorted())
    }

    @Test
    fun anOlderNearCopyIsOfferedAndSourceTreesAreOnlyNamed() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            // An export made twice: the second has two more files and one changed.
            for (i in 0 until 40) {
                fs.random("Documents/Exports/Report-v1/page$i.txt", 3000 + i, i, mtime = now - 90 * DAY_MS)
                if (i != 7) fs.random("Documents/Exports/Report-v2/page$i.txt", 3000 + i, i, mtime = now - 20 * DAY_MS)
            }
            fs.random("Documents/Exports/Report-v2/page7.txt", 5000, 99, mtime = now - 20 * DAY_MS)
            fs.random("Documents/Exports/Report-v2/extra1.txt", 100, 101, mtime = now - 20 * DAY_MS)
            // Two decompiles of one app: named, never offered.
            for (i in 0 until 30) {
                fs.text("Download/Projects/mx_jadx/sources/p/C$i.java", "class C$i { int x = $i; }".padEnd(400, ' '), mtime = now - 40 * DAY_MS)
                fs.text("Download/Projects/mx_jadx_bad/sources/p/C$i.java", "class C$i { int x = $i; }".padEnd(400, ' '), mtime = now - 30 * DAY_MS)
            }
            fs.ageDirectories()
            val settings = testSettings.copy(minDuplicateBytes = 1L shl 40)
            val report = Steward(fs.rootPath, settings, TestEnv, null).scan()
            // The sketches only look at bigger folders on a phone; these are small, so compare them directly.
            val sketches = FolderSketch.sketches(report.tree.root, minFiles = 20, minBytes = 1)
            val pairs = FolderSketch.nearCopies(sketches)
            val names = pairs.map { setOf(it.a.substringAfterLast('/'), it.b.substringAfterLast('/')) }
            assertTrue(names.toString(), setOf("Report-v1", "Report-v2") in names)
            assertTrue(names.toString(), setOf("mx_jadx", "mx_jadx_bad") in names)
            assertTrue(pairs.first { "Report-v1" in it.a || "Report-v1" in it.b }.percent in 80..100)

            val lowered = Steward(fs.rootPath, settings.copy(nearCopyMinBytes = 1), TestEnv, null).scan()
            val near = lowered.junk.filter { it.category == JunkCategory.NEAR_COPIES }
            assertEquals(listOf(fs.path("Documents/Exports/Report-v1")), near.map { it.path })
            assertTrue(near.single().note, near.single().note.endsWith("the same files as Documents/Exports/Report-v2, which is newer and stays"))
            assertTrue(lowered.insights.any { it.title == "Near-copies: mx_jadx and mx_jadx_bad" })
        }
    }
    /**
     * Nested pairs: Proj/vids is a newer copy of DCIM/Videos-export, and Proj an older copy of Proj-v2, which has no
     * videos. Offering both older folders would put every copy of the videos in the quarantine.
     */
    @Test
    fun nestedPairsNeverQuarantineEveryCopy() = runTest {
        TestFs().use { fs ->
            val now = System.currentTimeMillis()
            for (i in 0 until 20) {
                fs.random("DCIM/Videos-export/v$i.mp4", 1_000, 100 + i, mtime = now - 100 * DAY_MS)
                fs.random("Download/Proj/vids/v$i.mp4", 1_000, 200 + i, mtime = now - 50 * DAY_MS)
            }
            for (i in 0 until 40) {
                fs.random("Download/Proj/notes/n$i.txt", 1_000, 300 + i, mtime = now - 50 * DAY_MS)
                fs.random("Download/Proj-v2/notes/n$i.txt", 1_000, 400 + i, mtime = now - 10 * DAY_MS)
            }
            fs.ageDirectories()
            val report = Steward(fs.rootPath, testSettings.copy(nearCopyMinBytes = 1), TestEnv, null).scan()
            val near = report.junk.filter { it.category == JunkCategory.NEAR_COPIES }.map { it.path }
            assertTrue(near.toString(), near.isNotEmpty())
            assertTrue(near.toString(), !(fs.path("DCIM/Videos-export") in near && fs.path("Download/Proj") in near))
            // Every video still has a copy outside whatever is offered.
            for (i in 0 until 20) {
                val copies = listOf(fs.path("DCIM/Videos-export/v$i.mp4"), fs.path("Download/Proj/vids/v$i.mp4"))
                assertTrue("v$i", copies.any { c -> near.none { c.startsWith("$it/") } })
            }
        }
    }
}
