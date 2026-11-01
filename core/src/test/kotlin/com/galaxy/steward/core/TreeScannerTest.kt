package com.galaxy.steward.core

import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.scan.TreeScanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The storage walk lists folders on several threads; the tree must not depend on that. */
class TreeScannerTest {
    private fun layout(fs: TestFs) {
        for (top in listOf("Download", "Documents", "Pictures", "DCIM", "Music", "Custom")) {
            for (a in 1..6) for (b in 1..4) {
                fs.random("$top/folder $a/sub $b/file-$a-$b.bin", 100 + a * b, a * 31 + b)
                if (b % 2 == 0) fs.text("$top/folder $a/sub $b/deeper/notes $a$b.txt", "n$a$b")
            }
        }
        fs.text("Download/tool/.git/HEAD", "ref: refs/heads/main") // a project
        fs.text("Download/tool/main.py", "print(1)")
        repeat(3) { fs.text("Download/qq/smali_classes2/com/x/C$it.smali", ".class C$it") } // a decompiled app
        repeat(60) { fs.text("Documents/dump/pkg/m$it.py", "x = $it") } // mostly code
        fs.text("Documents/keys/id_ed25519", "secret")
        fs.text("Download/Projects/app/readme.txt", "readme")
        fs.text("Pinned/keep.txt", "mine")
        fs.text(".StorageSteward/Quarantine/run/old.txt", "never scanned")
        Files.createSymbolicLink(File(fs.path("Download/link")).toPath(), File(fs.path("Documents")).toPath())
        fs.ageDirectories()
    }

    /** Every folder with everything the planners read from it, in walk order. */
    private fun dump(tree: StorageTree): List<String> {
        val out = ArrayList<String>()
        tree.root.walkDirs { d ->
            out += listOf(
                d.relPath, d.zone, d.managed, d.flags, d.subtreeFlags, d.totalBytes, d.totalFiles, d.codeFiles, d.mtime,
                d.files.joinToString(",") { "${it.name}:${it.size}:${it.mtime}" }, d.dirs.joinToString(",") { it.name },
            ).joinToString("|")
        }
        return out
    }

    @Test
    fun parallelWalkBuildsTheSameTreeAsOneFolderAtATime() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val settings = testSettings.copy(protectedFolders = listOf("Pinned"))
            val sequential = dump(TreeScanner(fs.rootPath, settings, parallelism = 1).scan())
            assertTrue(sequential.size > 200)
            assertTrue(sequential.none { it.startsWith(".StorageSteward/Quarantine") })
            repeat(5) {
                var lastFiles = -1L
                val tree = TreeScanner(fs.rootPath, settings, parallelism = 6).scan { _, files, _, _ -> lastFiles = files }
                assertEquals(sequential, dump(tree))
                assertEquals(tree.root.totalFiles.toLong(), lastFiles) // the final progress call has every file
            }
        }
    }
}
