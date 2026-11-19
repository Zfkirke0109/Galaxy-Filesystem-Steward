package com.galaxy.steward.core.appdata

import com.galaxy.steward.core.SafetyPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** One file or folder in an app folder listing, with the size of everything below it. */
data class AppFolderEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val files: Int,
    val mtime: Long,
    /** Why this entry can't be picked for removal (a link, a special file, a key-like name, a protected app), or null. */
    val locked: String? = null,
    /** Part of the folder couldn't be read, so its size is a lower bound. */
    val partial: Boolean = false,
)

data class AppFolderListing(
    val path: String,
    val area: AppArea,
    val packageName: String,
    /** The whole app is view-only (Amazon Music, Audible, this app). */
    val protected: Boolean,
    /** When the listing was made: files changed after this are left alone by a removal. */
    val listedAt: Long,
    /** Largest first. */
    val entries: List<AppFolderEntry>,
    /** Entries left out because the folder holds more than a listing shows. */
    val hidden: Int,
) {
    /**
     * Removal items for the entries you picked: quarantined (undoable) or deleted for good. Locked entries are left
     * out, and files changed after [listedAt] are kept.
     */
    fun itemsFor(picked: Collection<AppFolderEntry>, quarantine: Boolean): List<AppJunkItem> =
        picked.filter { it.locked == null && !protected }.map { e ->
            AppJunkItem(
                id = "pick:${e.path}",
                kind = if (quarantine) AppJunkKind.PICKED else AppJunkKind.PICKED_DELETE,
                packageName = packageName,
                area = area,
                targets = listOf(AppTarget(e.path, e.isDirectory, e.bytes, e.mtime)),
                bytes = e.bytes,
                fileCount = e.files,
                cutoff = listedAt,
                note = e.name,
            )
        }
}

/**
 * Lists one folder inside `Android/{data,obb,media}/<package>` the way a file manager does, with the total size of
 * every subfolder. Read-only. It runs where those folders can be read: in the Shizuku helper for data and obb, or
 * in the app for media. What may be removed is checked again by [AppDataExecutor] right before each change.
 */
class AppDataBrowser(
    rootPath: String,
    private val ownPackage: String?,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val guard = AppDataGuard(rootPath, ownPackage)

    fun list(path: String, now: Long = System.currentTimeMillis()): AppFolderListing {
        val location = guard.locate(path) ?: throw IllegalArgumentException("Not inside an app folder")
        val dir = Paths.get(path)
        if (!guard.paths.resolvesBeneath(dir)) throw IllegalArgumentException("Symlinked or unsafe path")
        val attrs = attrs(dir) ?: throw IOException("This folder no longer exists")
        if (!attrs.isDirectory) throw IllegalArgumentException("Not a folder")
        val protected = AppPolicy.isProtected(location.packageName, ownPackage)
        val children = listDir(dir) ?: throw IOException("This folder can't be read")
        val entries = ArrayList<AppFolderEntry>(children.size)
        for ((child, a) in children) {
            val name = child.fileName.toString()
            // Names with tabs or line breaks can't be shown or journaled safely; the steward never touches them.
            if (SafetyPolicy.isUnsafeName(name)) continue
            val mtime = a.lastModifiedTime().toMillis()
            val entry = when {
                a.isSymbolicLink -> AppFolderEntry(name, child.toString(), false, 0, 0, mtime, locked = "link")
                a.isDirectory -> {
                    val size = measure(child)
                    AppFolderEntry(name, child.toString(), true, size.bytes, size.files, mtime, partial = size.partial)
                }
                a.isRegularFile -> AppFolderEntry(name, child.toString(), false, a.size(), 1, mtime)
                else -> AppFolderEntry(name, child.toString(), false, 0, 0, mtime, locked = "special file")
            }
            entries += entry.copy(
                locked = entry.locked ?: when {
                    protected -> "protected app"
                    SafetyPolicy.isCredentialName(name) -> "key or credential"
                    else -> null
                },
            )
        }
        entries.sortWith(compareByDescending<AppFolderEntry> { it.bytes }.thenBy { it.name.lowercase() })
        return AppFolderListing(
            path = path,
            area = location.area,
            packageName = location.packageName,
            protected = protected,
            listedAt = now,
            entries = entries.take(maxEntries),
            hidden = (entries.size - maxEntries).coerceAtLeast(0),
        )
    }

    private class Size(var bytes: Long = 0, var files: Int = 0, var partial: Boolean = false)

    /** Bytes and files below [dir], without following links. */
    private fun measure(dir: Path): Size {
        val size = Size()
        val stack = ArrayDeque<Pair<Path, Int>>()
        stack.addLast(dir to 0)
        while (stack.isNotEmpty()) {
            val (current, depth) = stack.removeLast()
            val children = listDir(current)
            if (children == null) {
                size.partial = true
                continue
            }
            for ((child, a) in children) {
                when {
                    a.isSymbolicLink || a.isOther -> Unit
                    a.isDirectory -> if (depth < MAX_DEPTH) stack.addLast(child to depth + 1) else size.partial = true
                    a.isRegularFile -> {
                        size.bytes += a.size()
                        size.files++
                    }
                }
            }
        }
        return size
    }

    private fun attrs(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }

    private fun listDir(dir: Path): List<Pair<Path, BasicFileAttributes>>? = try {
        Files.newDirectoryStream(dir).use { stream -> stream.mapNotNull { p -> attrs(p)?.let { p to it } } }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val MAX_ENTRIES = 2_000
        private const val MAX_DEPTH = 64
    }
}
