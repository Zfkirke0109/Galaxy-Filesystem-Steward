package com.galaxy.steward.core.exec

import com.galaxy.steward.core.SafetyPolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Turns the paths a run changed into the few folders the media index has to re-read.
 *
 * Android's scanner walks a folder recursively and drops index rows for files that are gone, so one call per
 * affected folder replaces one call per file. On a real phone a 1,900-file run produced 3,748 single-file scans that
 * kept the media provider busy for 15 minutes; the same run needs a few dozen folder scans.
 */
object MediaRescan {
    fun targets(
        rootPath: String,
        changedPaths: Collection<String>,
        isDirectory: (String) -> Boolean = { Files.isDirectory(Paths.get(it), LinkOption.NOFOLLOW_LINKS) },
    ): List<String> {
        val root = rootPath.trimEnd('/')
        val steward = "$root/${SafetyPolicy.STEWARD_DIR}"
        val candidates = HashSet<String>()
        for (raw in changedPaths) {
            val path = raw.trimEnd('/')
            if (!path.startsWith("$root/")) continue
            if (path == steward || path.startsWith("$steward/")) continue
            val folder = if (isDirectory(path)) path else path.substringBeforeLast('/')
            // Never rescan the whole volume because one file at the top level changed.
            candidates += if (folder == root) path else folder
        }
        // A folder is scanned recursively, so drop every candidate that has another candidate above it. Walking up
        // each path keeps this linear in the number of paths (a run can change tens of thousands of them).
        return candidates.filter { c ->
            var parent = c.substringBeforeLast('/', "")
            while (parent.length > root.length) {
                if (parent in candidates) return@filter false
                parent = parent.substringBeforeLast('/', "")
            }
            true
        }.sorted()
    }
}
