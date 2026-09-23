package com.galaxy.steward.core.appdata

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.exec.ExecutionListener
import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalSink
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.QuarantineManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Run-time checks for app folders, re-applied before every change: the path must sit inside
 * `Android/{data,obb,media}/<package>`, resolve without symlinks, and never belong to a protected app.
 */
class AppDataGuard(rootPath: String, private val ownPackage: String?) {
    val paths = PathGuard(rootPath)

    data class Location(val area: AppArea, val packageName: String, val rest: String)

    fun locate(path: String): Location? {
        if (!paths.wellFormed(path)) return null
        val parts = paths.relative(path).split('/')
        if (parts.size < 3 || parts[0] != "Android") return null
        val area = AppArea.ofDir(parts[1]) ?: return null
        if (!AppPolicy.isPackageName(parts[2])) return null
        return Location(area, parts[2], parts.drop(3).joinToString("/"))
    }

    /** Reason [path] must not be changed, or null when it may be. */
    fun reason(path: String, packageName: String): String? {
        val loc = locate(path) ?: return "not inside an app folder"
        if (loc.packageName != packageName) return "belongs to another app"
        if (AppPolicy.isProtected(loc.packageName, ownPackage)) return "protected app"
        if (SafetyPolicy.isCredentialName(path.substringAfterLast('/'))) return "credential-like name"
        if (!paths.resolvesBeneath(Paths.get(path))) return "symlinked or unsafe path"
        return null
    }
}

/**
 * Cleans app folders. Caches, logs and temp files are deleted for good (apps regenerate them) and journaled as
 * [JournalAction.PURGED]; outdated OBBs and leftovers of removed apps go to the quarantine and stay undoable.
 */
class AppDataExecutor(
    rootPath: String,
    private val sink: JournalSink,
    private val runId: String,
    ownPackage: String?,
    /** Packages installed right now; a leftover is only removed if its app is still missing. Null skips leftovers. */
    private val installedNow: Set<String>?,
) {
    private val guard = AppDataGuard(rootPath, ownPackage)
    private val quarantine = QuarantineManager(guard.paths, null)

    private var cleared = 0
    private var quarantined = 0
    private var removedDirs = 0
    private var skipped = 0
    private var failed = 0
    private var bytesFreed = 0L
    private var bytesQuarantined = 0L
    private val messages = ArrayList<String>()
    private val changed = LinkedHashSet<String>()

    suspend fun execute(title: String, items: List<AppJunkItem>, listener: ExecutionListener? = null): ExecutionSummary {
        sink.meta("title", title)
        sink.meta("kind", KIND)
        sink.meta("started", System.currentTimeMillis().toString())
        sink.meta("items", items.size.toString())
        val completed = LinkedHashSet<String>()
        val partial = LinkedHashSet<String>()
        val total = items.sumOf { it.targets.size }
        var done = 0
        try {
            for (item in items) {
                var ok = true
                for (target in item.targets) {
                    currentCoroutineContext().ensureActive()
                    listener?.onProgress(done, total, "${item.packageName}: ${target.path.substringAfterLast('/')}")
                    val problem = try {
                        run(item, target)
                    } catch (e: IOException) {
                        failed++
                        "I/O error: ${e.message ?: e.javaClass.simpleName}"
                    } catch (_: SecurityException) {
                        failed++
                        "Permission denied"
                    }
                    if (problem != null) {
                        ok = false
                        if (messages.size < 200) messages += "$problem: ${guard.paths.relative(target.path)}"
                    }
                    done++
                }
                if (ok) completed += item.id else partial += item.id
            }
        } finally {
            sink.meta("finished", System.currentTimeMillis().toString())
            sink.meta(
                "stats",
                "cleared=$cleared quarantined=$quarantined rmdir=$removedDirs skipped=$skipped failed=$failed " +
                    "freed=$bytesFreed quarantinedBytes=$bytesQuarantined",
            )
        }
        listener?.onProgress(total, total, "")
        return ExecutionSummary(
            runId = runId,
            completedItemIds = completed,
            partialItemIds = partial,
            moved = 0,
            deduped = 0,
            quarantined = quarantined,
            removedDirs = removedDirs,
            skipped = skipped,
            failed = failed,
            bytesFreed = bytesFreed,
            bytesQuarantined = bytesQuarantined,
            bytesMoved = 0,
            messages = messages.toList(),
            changedPaths = changed.toList(),
            cleared = cleared,
        )
    }

    /** Returns null on success, or why the target was skipped. */
    private fun run(item: AppJunkItem, target: AppTarget): String? {
        guard.reason(target.path, item.packageName)?.let { return skip("Protected ($it)") }
        val path = Paths.get(target.path)
        return when (item.kind) {
            AppJunkKind.LEFTOVERS -> quarantineLeftover(item, path)
            AppJunkKind.OBSOLETE_OBB -> quarantineFile(target, path)
            else -> if (target.isDirectory) clearInside(path, item.cutoff) else deleteFile(target, path)
        }
    }

    private fun skip(reason: String): String {
        skipped++
        return reason
    }

    private fun attrs(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    }

    private fun purged(path: String, files: Int, bytes: Long) {
        sink.entry(JournalEntry(JournalAction.PURGED, path, "files=$files", bytes, -1, null))
        cleared += files
        bytesFreed += bytes
    }

    // ------------------------------------------------------------------ permanent clean-ups

    private fun deleteFile(target: AppTarget, path: Path): String? {
        val a = attrs(path) ?: return null
        if (!a.isRegularFile) return skip("No longer a regular file")
        if (a.size() != target.size || a.lastModifiedTime().toMillis() != target.mtime) return skip("Changed since the scan")
        Files.delete(path)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return skip("Delete verification failed")
        purged(target.path, 1, target.size)
        changed += target.path
        return null
    }

    /**
     * Deletes regular files older than [cutoff] below [dir] without following links, then removes subfolders that
     * became empty. [dir] itself stays: apps expect their cache and log folders to exist.
     */
    private fun clearInside(dir: Path, cutoff: Long): String? {
        val top = attrs(dir) ?: return null
        if (!top.isDirectory) return skip("No longer a folder")
        var files = 0
        var bytes = 0L
        var kept = 0
        val dirs = ArrayList<Path>()
        val stack = ArrayDeque<Path>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val entries = try {
                Files.newDirectoryStream(current).use { it.toList() }
            } catch (_: IOException) {
                kept++
                continue
            }
            for (entry in entries) {
                val a = attrs(entry) ?: continue
                val name = entry.fileName.toString()
                when {
                    a.isSymbolicLink || a.isOther || SafetyPolicy.isUnsafeName(name) -> kept++
                    a.isDirectory -> {
                        dirs.add(entry)
                        stack.addLast(entry)
                    }
                    SafetyPolicy.isCredentialName(name) -> kept++
                    a.lastModifiedTime().toMillis() > cutoff -> kept++
                    else -> try {
                        Files.delete(entry)
                        files++
                        bytes += a.size()
                    } catch (_: IOException) {
                        kept++
                    }
                }
            }
        }
        for (d in dirs.sortedByDescending { it.nameCount }) {
            try {
                val empty = Files.newDirectoryStream(d).use { !it.iterator().hasNext() }
                if (empty) Files.delete(d)
            } catch (_: IOException) {
            }
        }
        if (files > 0) {
            purged(dir.toString(), files, bytes)
            changed += dir.toString()
        }
        return null
    }

    // ------------------------------------------------------------------ quarantine (undoable)

    private fun quarantineTarget(path: Path): Path? {
        if (!quarantine.prepare()) return null
        val q = quarantine.dirFor(runId).resolve(guard.paths.relative(path.toString()))
        if (!guard.paths.ensureDirectories(q.parent) { }) return null
        return if (Files.exists(q, LinkOption.NOFOLLOW_LINKS)) null else q
    }

    private fun quarantineFile(target: AppTarget, path: Path): String? {
        val a = attrs(path) ?: return null
        if (!a.isRegularFile) return skip("No longer a regular file")
        if (a.size() != target.size || a.lastModifiedTime().toMillis() != target.mtime) return skip("Changed since the scan")
        val q = quarantineTarget(path) ?: return skip("Quarantine unavailable")
        Files.move(path, q)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.exists(q, LinkOption.NOFOLLOW_LINKS)) {
            failed++
            return "Post-quarantine verification failed"
        }
        sink.entry(JournalEntry(JournalAction.QUARANTINED, target.path, q.toString(), target.size, target.mtime, null))
        quarantined++
        bytesQuarantined += target.size
        changed += target.path
        return null
    }

    private fun quarantineLeftover(item: AppJunkItem, dir: Path): String? {
        val installed = installedNow ?: return skip("Installed apps unknown")
        if (item.packageName in installed) return skip("App is installed again")
        val loc = guard.locate(dir.toString()) ?: return skip("Not an app folder")
        if (loc.rest.isNotEmpty()) return skip("Not an app folder")
        val a = attrs(dir) ?: return null
        if (!a.isDirectory) return skip("No longer a folder")
        val q = quarantineTarget(dir) ?: return skip("Quarantine unavailable")
        val renamed = try {
            Files.move(dir, q)
            true
        } catch (_: IOException) {
            false // Android/data sits behind its own mount on some builds: fall back to moving file by file.
        }
        if (renamed) {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                failed++
                return "Post-quarantine verification failed"
            }
            sink.entry(JournalEntry(JournalAction.QUARANTINED, dir.toString(), q.toString(), item.bytes, a.lastModifiedTime().toMillis(), null))
            quarantined++
            bytesQuarantined += item.bytes
            changed += dir.toString()
            return null
        }
        return quarantineFileByFile(dir)
    }

    private fun quarantineFileByFile(dir: Path): String? {
        val dirs = ArrayList<Path>()
        val stack = ArrayDeque<Path>()
        stack.addLast(dir)
        var left = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            dirs.add(current)
            val entries = try {
                Files.newDirectoryStream(current).use { it.toList() }
            } catch (_: IOException) {
                left++
                continue
            }
            for (entry in entries) {
                val a = attrs(entry) ?: continue
                when {
                    a.isSymbolicLink || a.isOther || SafetyPolicy.isUnsafeName(entry.fileName.toString()) -> left++
                    a.isDirectory -> stack.addLast(entry)
                    SafetyPolicy.isCredentialName(entry.fileName.toString()) -> left++
                    else -> {
                        val q = quarantineTarget(entry)
                        if (q == null) {
                            left++
                            continue
                        }
                        try {
                            Files.move(entry, q)
                        } catch (_: IOException) {
                            left++
                            continue
                        }
                        val mtime = a.lastModifiedTime().toMillis()
                        sink.entry(JournalEntry(JournalAction.QUARANTINED, entry.toString(), q.toString(), a.size(), mtime, null))
                        quarantined++
                        bytesQuarantined += a.size()
                    }
                }
            }
        }
        for (d in dirs.sortedByDescending { it.nameCount }) {
            try {
                val empty = Files.newDirectoryStream(d).use { !it.iterator().hasNext() }
                if (!empty) continue
                val mtime = Files.getLastModifiedTime(d, LinkOption.NOFOLLOW_LINKS).toMillis()
                Files.delete(d)
                sink.entry(JournalEntry(JournalAction.RMDIR, d.toString(), "", -1, mtime, null))
                removedDirs++
            } catch (_: IOException) {
            }
        }
        changed += dir.toString()
        return if (left > 0) skip("$left protected or unreadable item(s) left in place") else null
    }

    companion object {
        const val KIND = "appdata"
    }
}
