package com.galaxy.steward.core.scan

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Walks shared storage without following symlinks and builds a compact in-memory [StorageTree].
 * Ownership zones and safety flags are assigned during the walk so later planners never have to guess.
 */
class TreeScanner(
    private val rootPath: String,
    private val settings: StewardSettings,
) {
    fun interface Listener {
        fun onProgress(dirs: Int, files: Long, bytes: Long, current: String)
    }

    private val protectedRel: Set<String> = settings.protectedFolders
        .map { it.trim().trim('/') }
        .filter { it.isNotEmpty() }
        .toSet()

    suspend fun scan(listener: Listener? = null): StorageTree {
        val rootDir = Paths.get(rootPath)
        val root = DirNode(rootPath.trimEnd('/'), null).apply { zone = Zone.OTHER_SHARED }
        val stack = ArrayDeque<Pair<DirNode, Path>>()
        stack.addLast(root to rootDir)
        var dirs = 0
        var files = 0L
        var bytes = 0L
        var sinceReport = 0

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (node, dirPath) = stack.removeLast()
            dirs++
            val entries = listEntries(dirPath)
            if (entries == null) {
                node.flags = node.flags or NodeFlags.UNREADABLE
                continue
            }
            if (!node.isRoot && SafetyPolicy.isProjectRoot(entries.map { it.first })) {
                node.flags = node.flags or NodeFlags.PROJECT_ROOT
                if (node.zone.removable) node.zone = Zone.PATH_SENSITIVE
            }
            for ((name, attrs) in entries) {
                if (SafetyPolicy.isUnsafeName(name)) {
                    node.flags = node.flags or NodeFlags.HAS_UNSAFE_NAME
                    continue
                }
                when {
                    attrs.isSymbolicLink -> node.flags = node.flags or NodeFlags.HAS_SYMLINK
                    attrs.isDirectory -> {
                        val child = DirNode(name, node)
                        assignZone(child)
                        child.mtime = attrs.lastModifiedTime().toMillis()
                        node.dirs.add(child)
                        // The steward's own quarantine and state are never scanned.
                        if (child.zone != Zone.STEWARD) stack.addLast(child to dirPath.resolve(name))
                    }
                    attrs.isRegularFile -> {
                        val file = FileNode(name, attrs.size(), attrs.lastModifiedTime().toMillis(), node)
                        node.files.add(file)
                        if (SafetyPolicy.isCredentialName(name)) node.flags = node.flags or NodeFlags.HAS_CREDENTIAL
                        files++
                        bytes += file.size
                    }
                    else -> node.flags = node.flags or NodeFlags.HAS_SPECIAL
                }
                if (++sinceReport >= 512) {
                    sinceReport = 0
                    listener?.onProgress(dirs, files, bytes, node.relPath)
                }
            }
            // Deterministic order makes plans and tests stable.
            node.dirs.sortBy { it.name.lowercase() }
            node.files.sortBy { it.name.lowercase() }
        }
        listener?.onProgress(dirs, files, bytes, "")
        aggregate(root)
        return StorageTree(root)
    }

    private fun listEntries(dir: Path): List<Pair<String, BasicFileAttributes>>? = try {
        val out = ArrayList<Pair<String, BasicFileAttributes>>()
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                val attrs = try {
                    Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    continue
                }
                out.add(entry.fileName.toString() to attrs)
            }
        }
        out
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun assignZone(child: DirNode) {
        val parent = child.parent!!
        if (parent.isRoot) {
            child.zone = SafetyPolicy.topLevelZone(child.name)
            child.managed = child.name in SafetyPolicy.MANAGED_TOP_DIRS
        } else {
            child.zone = parent.zone
            child.managed = parent.managed ||
                (parent.depth == 1 && parent.zone == Zone.MEDIA_LIBRARY && child.name == SafetyPolicy.MANAGED_MEDIA_SUBDIR)
        }
        if (child.name == ".git") {
            child.flags = child.flags or NodeFlags.GIT_DIR
            if (child.zone.removable) child.zone = Zone.PATH_SENSITIVE
        }
        if (child.zone != Zone.APP_OWNED && child.zone != Zone.STEWARD && child.relPath in protectedRel) {
            child.zone = Zone.USER_PROTECTED
        }
    }

    /** Post-order aggregation of sizes, counts and subtree safety flags. */
    private fun aggregate(root: DirNode) {
        val order = ArrayList<DirNode>()
        root.walkDirs { order.add(it) }
        for (i in order.indices.reversed()) {
            val node = order[i]
            var total = 0L
            for (f in node.files) total += f.size
            var count = node.files.size
            var sub = node.flags
            for (d in node.dirs) {
                total += d.totalBytes
                count += d.totalFiles
                sub = sub or d.subtreeFlags
            }
            node.totalBytes = total
            node.totalFiles = count
            node.subtreeFlags = sub
        }
    }

    companion object {
        /** Builds a tree for tests and tools from an arbitrary directory. */
        suspend fun scanDirectory(root: String, settings: StewardSettings = StewardSettings()): StorageTree =
            TreeScanner(root, settings).scan()
    }
}

/** Convenience for planners: every file in the tree, filtered. */
inline fun StorageTree.files(crossinline predicate: (FileNode) -> Boolean): List<FileNode> {
    val out = ArrayList<FileNode>()
    root.walkFiles { if (predicate(it)) out.add(it) }
    return out
}
