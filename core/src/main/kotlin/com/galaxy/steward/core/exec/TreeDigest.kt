package com.galaxy.steward.core.exec

import com.galaxy.steward.core.hash.Hashing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * What a folder holds, as one SHA-256: the relative path and type of everything below it, and each file's size and
 * modification time. Links are listed, never followed. Each path is written with its length first, so no name (one
 * with a tab or a newline in it, say) can make two different trees read the same.
 *
 * Used for folders an app-folder run puts in the quarantine: the quarantine is in shared storage, where any app with
 * All-files access can add to it, and Undo puts the folder back into `Android/data` or `obb` with the shell's rights.
 * Comparing the digest first means Undo puts back only what the run moved. It can't tell a file rewritten with its
 * size kept and its date set back; that would take reading every file twice.
 */
object TreeDigest {
    /** Marks a digest in a journal line's hash field, where a file's own lines keep a file's SHA-256. */
    const val PREFIX = "tree:"

    /** The digest of what [root] holds, or null when [root] isn't a real folder or something in it can't be read. */
    fun of(root: Path): String? = try {
        val top = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!top.isDirectory) {
            null
        } else {
            val lines = ArrayList<Pair<String, String>>()
            val stack = ArrayDeque<Pair<Path, String>>()
            stack.addLast(root to "")
            while (stack.isNotEmpty()) {
                val (dir, prefix) = stack.removeLast()
                Files.newDirectoryStream(dir).use { entries ->
                    for (entry in entries) {
                        val rel = prefix + entry.fileName.toString()
                        val a = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        val line = when {
                            a.isSymbolicLink -> "l"
                            a.isDirectory -> "d".also { stack.addLast(entry to "$rel/") }
                            a.isRegularFile -> "f ${a.size()} ${a.lastModifiedTime().toMillis()}"
                            else -> "o"
                        }
                        lines += rel to "${rel.length}:$rel $line\n"
                    }
                }
            }
            lines.sortBy { it.first }
            val md = MessageDigest.getInstance("SHA-256")
            for ((_, line) in lines) md.update(line.toByteArray(Charsets.UTF_8))
            PREFIX + Hashing.hex(md.digest())
        }
    } catch (_: IOException) {
        null
    } catch (_: java.nio.file.DirectoryIteratorException) {
        null
    }
}
