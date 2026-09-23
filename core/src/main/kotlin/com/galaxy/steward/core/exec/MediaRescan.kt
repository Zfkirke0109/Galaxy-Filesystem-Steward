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
        val kept = ArrayList<String>()
        for (c in candidates.sortedBy { it.length }) {
            if (kept.none { c.startsWith("$it/") }) kept += c
        }
        return kept.sorted()
    }
}
