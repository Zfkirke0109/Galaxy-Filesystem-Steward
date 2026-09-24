package com.galaxy.steward.core

import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.scan.TreeScanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A rescan fills in unchanged code folders from the last tree instead of listing them again. */
class RescanTest {
    private fun layout(fs: TestFs) {
        val jadx = "Download/Projects/app/jadx"
        fs.text("$jadx/resources/AndroidManifest.xml", "<manifest/>")
        repeat(12) { p -> repeat(5) { c -> fs.text("$jadx/sources/p$p/C$c.java", "class C$c {}") } }
        fs.text("$jadx/sources/p0/release.keystore", "key")
        repeat(3) { fs.text("Documents/Notes/n$it.txt", "note $it") }
        fs.ageDirectories()
    }

    private fun summary(tree: StorageTree): List<String> {
        val out = ArrayList<String>()
        tree.root.walkDirs { d ->
            out += "${d.relPath} ${d.zone} ${d.flags} ${d.subtreeFlags} ${d.totalFiles} ${d.totalBytes}"
            d.files.forEach { out += "  ${it.name} ${it.size}" }
        }
        return out
    }

    @Test
    fun unchangedCodeFoldersAreReusedAndChangedOnesListedAgain() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val first = TreeScanner(fs.rootPath, testSettings).scan()
            val scanner = TreeScanner(fs.rootPath, testSettings, previous = first)
            val second = scanner.scan()
            // Every folder of the decompiled app, the same tree as a full listing gives.
            assertTrue(scanner.reusedFolders >= 14)
            assertEquals(summary(first), summary(second))
            val jadx = second.find("Download/Projects/app/jadx")!!
            assertTrue(jadx.hasFlag(NodeFlags.CODE_TREE) && jadx.zone == Zone.PATH_SENSITIVE)
            assertTrue(second.find("Download/Projects/app/jadx/sources/p0")!!.hasFlag(NodeFlags.HAS_CREDENTIAL))

            // A new file changes its folder's date: that folder is listed again. Your own folders always are.
            val p3 = File(fs.root, "Download/Projects/app/jadx/sources/p3")
            File(p3, "New.java").writeText("class New {}")
            fs.text("Documents/Notes/n9.txt", "new note")
            val third = TreeScanner(fs.rootPath, testSettings, previous = second).scan()
            assertNotNull(third.find("Download/Projects/app/jadx/sources/p3")!!.files.firstOrNull { it.name == "New.java" })
            assertEquals(4, third.find("Documents/Notes")!!.files.size)
            assertEquals(first.root.totalFiles + 2, third.root.totalFiles)

            // A folder that is gone is noticed from its parent's date.
            File(fs.root, "Download/Projects/app/jadx/sources/p5").deleteRecursively()
            val fourth = TreeScanner(fs.rootPath, testSettings, previous = third).scan()
            assertEquals(null, fourth.find("Download/Projects/app/jadx/sources/p5"))
        }
    }
}
