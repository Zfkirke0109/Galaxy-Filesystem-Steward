package com.galaxy.steward.core.scan

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Walks shared storage without following symlinks and builds a compact in-memory [StorageTree].
 * Ownership zones and safety flags are assigned during the walk so later planners never have to guess.
 */
class TreeScanner(
    private val rootPath: String,
    private val settings: StewardSettings,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    /**
     * The last scan's tree, to reuse what can't have changed: a folder inside source code or a decompiled app whose date
     * is the same as then has the same entries, so it is filled in from [previous] with one stat per subfolder instead of
     * one per entry. (A file rewritten in place keeps its old size and date here until something beside it changes;
     * the steward never changes code trees, and checks every file again before touching it.)
     */
    private val previous: StorageTree? = null,
) {
    private val reused = AtomicInteger()

    /** Folders filled in from [previous] by the last [scan]. */
    val reusedFolders: Int get() = reused.get()

    fun interface Listener {
        fun onProgress(dirs: Int, files: Long, bytes: Long, current: String)
    }

    private val protectedRel: Set<String> = settings.protectedFolders
        .map { it.trim().trim('/') }
        .filter { it.isNotEmpty() }
        .toSet() + SafetyPolicy.LOGCAT_DIR

    /**
     * Folders are listed on [parallelism] threads at once. On Android every listing and stat goes through the
     * storage FUSE daemon, which answers requests in parallel but is slow one at a time: a single-threaded walk
     * mapped 251,000 files in 184 s on a Galaxy S23 Ultra. Each folder is filled in by exactly one worker, and every
     * folder's entries are sorted, so the tree is the same whatever the order folders were visited in.
     */
    suspend fun scan(listener: Listener? = null): StorageTree = coroutineScope {
        val rootDir = Paths.get(rootPath)
        val root = DirNode(rootPath.trimEnd('/'), null).apply { zone = Zone.OTHER_SHARED }
        val progress = Progress(listener)
        val queue = Channel<Triple<DirNode, Path, DirNode?>>(Channel.UNLIMITED)
        // Folders queued or being listed; the walk is over when it drops to zero.
        val pending = AtomicInteger(1)
        reused.set(0)
        queue.send(Triple(root, rootDir, previous?.root?.takeIf { it.path == root.path }))
        val workers = (1..parallelism.coerceAtLeast(1)).map {
            launch(Dispatchers.IO) {
                for ((node, dirPath, before) in queue) {
                    ensureActive()
                    visit(node, dirPath, before, progress) { child, childPath, childBefore ->
                        pending.incrementAndGet()
                        queue.trySend(Triple(child, childPath, childBefore))
                    }
                    if (pending.decrementAndGet() == 0) queue.close()
                }
            }
        }
        workers.joinAll()
        progress.finish()
        aggregate(root)
        if (markCodeDominated(root)) aggregateFlags(root)
        StorageTree(root)
    }

    /** Counts shared by the workers; the listener is called by one worker at a time. */
    private class Progress(private val listener: Listener?) {
        private val dirs = AtomicInteger()
        private val files = AtomicLong()
        private val bytes = AtomicLong()
        private val sinceReport = AtomicInteger()

        fun dir() = dirs.incrementAndGet()

        fun file(size: Long) {
            files.incrementAndGet()
            bytes.addAndGet(size)
        }

        fun entry(current: DirNode) {
            if (listener == null || sinceReport.incrementAndGet() % 512 != 0) return
            synchronized(this) { listener.onProgress(dirs.get(), files.get(), bytes.get(), current.relPath) }
        }

        fun finish() {
            synchronized(this) { listener?.onProgress(dirs.get(), files.get(), bytes.get(), "") }
        }
    }

    /**
     * Lists one folder: flags and zone for [node] itself, then a child node for every subfolder. [before] is the same
     * folder in the previous tree, if there was one.
     */
    private fun visit(node: DirNode, dirPath: Path, before: DirNode?, progress: Progress, enqueue: (DirNode, Path, DirNode?) -> Unit) {
        progress.dir()
        if (before != null && node.mtime > 0 && before.mtime == node.mtime && before.insideFlagged(NodeFlags.CODE_TREE) &&
            !before.hasFlag(NodeFlags.UNREADABLE) && reuse(node, dirPath, before, progress, enqueue)
        ) {
            reused.incrementAndGet()
            return
        }
        val earlier = before?.dirs?.associateBy { it.name }
        val entries = listEntries(dirPath)
        if (entries == null) {
            node.flags = node.flags or NodeFlags.UNREADABLE
            return
        }
        val names = entries.map { it.first }
        if (!node.isRoot && SafetyPolicy.isProjectRoot(names)) {
            node.flags = node.flags or NodeFlags.PROJECT_ROOT
            if (node.zone.removable) node.zone = Zone.PATH_SENSITIVE
        }
        if (!node.isRoot && SafetyPolicy.isDecompiledAppRoot(names)) markCodeTree(node)
        val subfolders = ArrayList<Triple<DirNode, Path, DirNode?>>()
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
                    if (child.zone != Zone.STEWARD) subfolders += Triple(child, dirPath.resolve(name), earlier?.get(name))
                }
                attrs.isRegularFile -> {
                    val file = FileNode(name, attrs.size(), attrs.lastModifiedTime().toMillis(), node)
                    node.files.add(file)
                    if (SafetyPolicy.isCredentialName(name)) node.flags = node.flags or NodeFlags.HAS_CREDENTIAL
                    progress.file(file.size)
                }
                else -> node.flags = node.flags or NodeFlags.HAS_SPECIAL
            }
            progress.entry(node)
        }
        // Deterministic order makes plans and tests stable.
        node.dirs.sortBy { it.name.lowercase() }
        node.files.sortBy { it.name.lowercase() }
        // Children are handed out only once this folder is complete: its zone and flags are final by then.
        subfolders.forEach { (child, path, earlierChild) -> enqueue(child, path, earlierChild) }
    }

    /**
     * Fills [node] in from [before] (same path, same date): its files, its flags from the listing, and its subfolders,
     * each with a fresh date so the walk can decide again below. False (and [node] untouched) if a subfolder is gone
     * or isn't one any more; the folder is then listed as usual.
     */
    private fun reuse(node: DirNode, dirPath: Path, before: DirNode, progress: Progress, enqueue: (DirNode, Path, DirNode?) -> Unit): Boolean {
        val children = ArrayList<Triple<DirNode, Path, DirNode?>>(before.dirs.size)
        for (d in before.dirs) {
            val child = DirNode(d.name, node)
            val path = dirPath.resolve(d.name)
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return false
            } catch (_: SecurityException) {
                return false
            }
            if (!attrs.isDirectory) return false
            child.mtime = attrs.lastModifiedTime().toMillis()
            children += Triple(child, path, d)
        }
        // What the listing would say: links, special files and unsafe names aren't in the tree, so they come from then.
        node.flags = node.flags or (before.flags and LISTED_FLAGS)
        val names = before.files.map { it.name } + before.dirs.map { it.name }
        if (SafetyPolicy.isProjectRoot(names)) {
            node.flags = node.flags or NodeFlags.PROJECT_ROOT
            if (node.zone.removable) node.zone = Zone.PATH_SENSITIVE
        }
        if (SafetyPolicy.isDecompiledAppRoot(names)) markCodeTree(node)
        for (f in before.files) {
            node.files.add(FileNode(f.name, f.size, f.mtime, node))
            if (SafetyPolicy.isCredentialName(f.name)) node.flags = node.flags or NodeFlags.HAS_CREDENTIAL
            progress.file(f.size)
        }
        for ((child, path, d) in children) {
            assignZone(child)
            node.dirs.add(child)
            if (child.zone != Zone.STEWARD) enqueue(child, path, d)
        }
        progress.entry(node)
        return true
    }

    /** Source code and decompiled apps behave like projects: kept as they are, never restructured. */
    private fun markCodeTree(node: DirNode) {
        node.flags = node.flags or NodeFlags.CODE_TREE
        if (node.zone.removable) node.zone = Zone.PATH_SENSITIVE
    }

    /**
     * Folders whose content is mostly code are marked after the walk, once their composition is known. Only folders
     * below the top level qualify, so one big source dump never freezes all of Download. Returns true if any changed.
     */
    private fun markCodeDominated(root: DirNode): Boolean {
        var changed = false
        val stack = ArrayDeque<DirNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.depth >= 2 && node.zone.removable && SafetyPolicy.isCodeDominated(node.codeFiles, node.totalFiles)) {
                markCodeTree(node)
                node.walkDirs { if (it.zone.removable) it.zone = Zone.PATH_SENSITIVE }
                changed = true
                continue
            }
            node.dirs.forEach(stack::addLast)
        }
        return changed
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
        if (child.depth >= 2 && SafetyPolicy.isDevContainerName(child.name)) markCodeTree(child)
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
            var code = 0
            for (f in node.files) {
                total += f.size
                if (f.kind == FileKind.CODE) code++
            }
            var count = node.files.size
            var sub = node.flags
            for (d in node.dirs) {
                total += d.totalBytes
                count += d.totalFiles
                code += d.codeFiles
                sub = sub or d.subtreeFlags
            }
            node.totalBytes = total
            node.totalFiles = count
            node.codeFiles = code
            node.subtreeFlags = sub
        }
    }

    /** Recomputes subtree flags only (after flags were added once sizes were known). */
    private fun aggregateFlags(root: DirNode) {
        val order = ArrayList<DirNode>()
        root.walkDirs { order.add(it) }
        for (i in order.indices.reversed()) {
            val node = order[i]
            var sub = node.flags
            for (d in node.dirs) sub = sub or d.subtreeFlags
            node.subtreeFlags = sub
        }
    }

    companion object {
        /** Enough to keep the storage daemon busy without starving the rest of the phone. */
        const val DEFAULT_PARALLELISM = 6

        /** Flags a folder gets from entries the tree doesn't keep. */
        private const val LISTED_FLAGS = NodeFlags.HAS_SYMLINK or NodeFlags.HAS_SPECIAL or NodeFlags.HAS_UNSAFE_NAME

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
