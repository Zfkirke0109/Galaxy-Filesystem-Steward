package com.galaxy.steward.core.model

/**
 * Ownership zone of a path. Mirrors the Termux steward's ownership classes: only user-owned zones are ever
 * mutated, app-owned and steward-owned trees are strictly read-only, and path-sensitive trees (projects,
 * Git repositories, user-pinned folders) may serve as a canonical copy but are never moved or removed.
 */
enum class Zone(val label: String, val description: String) {
    USER_MANAGED("Your files", "Download and Documents - the steward may file, dedupe and tidy these."),
    MEDIA_LIBRARY("Media library", "Camera, Pictures, Movies, Music - kept as the preferred copy of media."),
    OTHER_SHARED("Other app folders", "Folders other apps created in shared storage - changes are review-only by default."),
    PATH_SENSITIVE("Path-sensitive", "Projects and Git repositories - never moved or removed."),
    USER_PROTECTED("Pinned by you", "Folders you protected in settings - never moved or removed."),
    APP_OWNED("App-owned", "Android/data, obb and media - strictly no-touch."),
    STEWARD("Steward", "Quarantine and steward state - excluded from scans.");

    /** A duplicate that lives here may be deleted (subject to the canonical also being durable). */
    val removable: Boolean get() = this == USER_MANAGED || this == MEDIA_LIBRARY || this == OTHER_SHARED

    /** A file here is durable user storage and may serve as the kept canonical copy. */
    val durable: Boolean get() = this != APP_OWNED && this != STEWARD
}

object NodeFlags {
    const val HAS_SYMLINK = 1
    const val HAS_SPECIAL = 1 shl 1
    const val HAS_CREDENTIAL = 1 shl 2
    const val PROJECT_ROOT = 1 shl 3
    const val GIT_DIR = 1 shl 4
    const val UNREADABLE = 1 shl 5
    const val HAS_UNSAFE_NAME = 1 shl 6

    /** Source code or a decompiled app (smali, jadx output, mostly code files): its layout is its meaning. */
    const val CODE_TREE = 1 shl 7

    /** Any of these anywhere in a subtree makes the whole subtree unsafe to move or dedupe as a unit. */
    const val SUBTREE_BLOCKERS =
        HAS_SYMLINK or HAS_SPECIAL or HAS_CREDENTIAL or PROJECT_ROOT or GIT_DIR or UNREADABLE or HAS_UNSAFE_NAME or CODE_TREE
}

class FileNode(
    val name: String,
    val size: Long,
    val mtime: Long,
    val dir: DirNode,
) {
    val path: String get() = dir.path + "/" + name
    val relPath: String get() = if (dir.parent == null) name else dir.relPath + "/" + name
    val kind: FileKind get() = FileKind.of(name)
    val extension: String get() = FileKind.extensionOf(name)
    val hidden: Boolean get() = name.startsWith('.')
    val zone: Zone get() = dir.zone
    val depth: Int get() = dir.depth + 1

    override fun toString(): String = path
}

class DirNode(
    val name: String,
    val parent: DirNode?,
) {
    var zone: Zone = Zone.OTHER_SHARED

    /** True for trees the steward owns the layout of (Download, Documents, media `Imported` folders). */
    var managed: Boolean = false
    var mtime: Long = 0L
    var flags: Int = 0
    var subtreeFlags: Int = 0
    val dirs: MutableList<DirNode> = ArrayList(0)
    val files: MutableList<FileNode> = ArrayList(0)
    var totalBytes: Long = 0L
    var totalFiles: Int = 0

    /** Code files (FileKind.CODE) anywhere below this folder. */
    var codeFiles: Int = 0
    val depth: Int = if (parent == null) 0 else parent.depth + 1

    private var cachedPath: String? = null

    /** Absolute path. The root node's name is the absolute root path itself. */
    val path: String
        get() = cachedPath ?: (if (parent == null) name else parent.path + "/" + name).also { cachedPath = it }

    /** Path relative to the scan root ("" for the root). */
    val relPath: String
        get() = when {
            parent == null -> ""
            parent.parent == null -> name
            else -> parent.relPath + "/" + name
        }

    val hidden: Boolean get() = parent != null && name.startsWith('.')
    val isRoot: Boolean get() = parent == null
    val directBytes: Long get() = files.sumOf { it.size }

    fun hasFlag(flag: Int) = flags and flag != 0
    fun subtreeHas(mask: Int) = subtreeFlags and mask != 0

    /** True if this node or any ancestor is flagged (for example, inside a project root). */
    fun insideFlagged(flag: Int): Boolean {
        var node: DirNode? = this
        while (node != null) {
            if (node.flags and flag != 0) return true
            node = node.parent
        }
        return false
    }

    fun isAncestorOf(other: DirNode): Boolean {
        var node = other.parent
        while (node != null) {
            if (node === this) return true
            node = node.parent
        }
        return false
    }

    fun child(name: String): DirNode? = dirs.firstOrNull { it.name == name }

    fun walkDirs(visit: (DirNode) -> Unit) {
        val stack = ArrayDeque<DirNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visit(node)
            for (i in node.dirs.indices.reversed()) stack.addLast(node.dirs[i])
        }
    }

    fun walkFiles(visit: (FileNode) -> Unit) = walkDirs { dir -> dir.files.forEach(visit) }

    override fun toString(): String = path
}

/** The in-memory map of shared storage produced by a scan. */
class StorageTree(val root: DirNode) {
    val rootPath: String get() = root.path

    fun find(relPath: String): DirNode? {
        if (relPath.isEmpty()) return root
        var node = root
        for (part in relPath.split('/')) {
            node = node.child(part) ?: return null
        }
        return node
    }

    fun findAbsolute(path: String): DirNode? = when {
        path == rootPath -> root
        path.startsWith("$rootPath/") -> find(path.removePrefix("$rootPath/"))
        else -> null
    }
}
