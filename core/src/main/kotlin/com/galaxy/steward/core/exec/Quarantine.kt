package com.galaxy.steward.core.exec

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.SafetyPolicy
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The quarantine lives at `<storage>/.StorageSteward/Quarantine/<run id>/<original relative path>`, on the
 * same volume as the data so quarantining is an instant rename. Space is only released when a run's
 * quarantine is emptied - manually or after the retention period.
 */
class QuarantineManager(private val guard: PathGuard, private val journals: JournalStore?) {
    fun dirFor(runId: String): Path = guard.quarantineRoot.resolve(runId)

    /** Creates the steward folder with a `.nomedia` marker so galleries never index quarantined media. */
    fun prepare(): Boolean {
        return try {
            Files.createDirectories(guard.quarantineRoot)
            val marker = guard.stewardDir.resolve(".nomedia")
            if (!Files.exists(marker)) Files.createFile(marker)
            guard.isRealDirectory(guard.quarantineRoot)
        } catch (_: IOException) {
            false
        }
    }

    fun sizeOf(runId: String): Long = treeSize(dirFor(runId))

    fun totalSize(): Long = treeSize(guard.quarantineRoot)

    fun runsWithContent(): List<String> = try {
        Files.newDirectoryStream(guard.quarantineRoot).use { stream ->
            stream.filter { guard.isRealDirectory(it) }.map { it.fileName.toString() }
        }
    } catch (_: IOException) {
        emptyList()
    }

    /**
     * Permanently deletes one run's quarantine and, once nothing of it is left, marks its journal as purged. Returns
     * the bytes actually freed: a file that can't be deleted stays, with its run's Undo, and isn't counted.
     */
    fun purge(runId: String, now: Long = System.currentTimeMillis()): Long {
        val dir = dirFor(runId)
        if (!dir.startsWith(guard.quarantineRoot) || dir == guard.quarantineRoot || !guard.isRealDirectory(dir)) return 0
        val before = treeSize(dir)
        deleteTree(dir)
        val left = treeSize(dir)
        if (left == 0L && journals != null && journals.fileFor(runId).exists()) journals.appendMeta(runId, "purged", now.toString())
        return before - left
    }

    fun purgeAll(now: Long = System.currentTimeMillis()): Long = runsWithContent().sumOf { purge(it, now) }

    /** Empties quarantines of runs that finished more than [retentionDays] ago. */
    fun purgeExpired(retentionDays: Int, now: Long = System.currentTimeMillis()): Long {
        if (retentionDays <= 0) return 0
        val cutoff = now - retentionDays * DAY_MS
        return runsWithContent().sumOf { id ->
            val info = journals?.info(id)
            val finished = info?.finishedAt ?: info?.startedAt ?: 0L
            if (finished in 1 until cutoff) purge(id, now) else 0L
        }
    }

    /**
     * Runs whose quarantine is still here but whose journal isn't: the journals live in app storage, which a reinstall
     * (the only way onto a build signed with another key) wipes, while the quarantine in shared storage stays. History
     * can't undo these, and they are never emptied on their own (purgeExpired goes by the journal's time).
     */
    fun orphans(): List<String> = if (journals == null) emptyList() else runsWithContent().filterNot { journals.fileFor(it).exists() }

    /**
     * Undo entries for what [runId] left in the quarantine, rebuilt from the quarantine's own layout (it keeps each
     * item's path): an entry for every item whose path is free now, the folder's contents one level down where a
     * folder of that name exists again, and the item itself, to go beside it, where a file has its name. Fed to
     * [RollbackEngine.rollbackEntries], which checks every one again.
     *
     * Not what went there from Android/ (app folders) or the steward's own folder: this app can't see other apps'
     * Android/data and obb folders, so it would misjudge which exist, and only the shell helper could write them, which
     * would let any app that can write the quarantine have it write into an app's folder. They stay in the quarantine.
     */
    fun orphanEntries(runId: String): List<JournalEntry> {
        val dir = dirFor(runId)
        if (!dir.startsWith(guard.quarantineRoot) || dir == guard.quarantineRoot || !guard.isRealDirectory(dir)) return emptyList()
        val entries = ArrayList<JournalEntry>()
        val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) {
            val folder = stack.removeLast()
            val children = try {
                Files.newDirectoryStream(folder).use { it.toList() }
            } catch (_: IOException) {
                continue
            }
            for (q in children) {
                if (Files.isSymbolicLink(q)) continue
                val rel = dir.relativize(q)
                if (rel.getName(0).toString().let { it == "Android" || it == SafetyPolicy.STEWARD_DIR }) continue
                val original = guard.root.resolve(rel.toString())
                if (guard.isRealDirectory(q) && guard.isRealDirectory(original)) {
                    stack.addLast(q)
                } else {
                    entries += JournalEntry(JournalAction.QUARANTINED, original.toString(), q.toString(), -1, -1, null)
                }
            }
        }
        return entries
    }

    /** Removes the folders [runId]'s quarantine is left with once everything in them went back. */
    fun pruneEmpty(runId: String) {
        val dir = dirFor(runId)
        if (!dir.startsWith(guard.quarantineRoot) || dir == guard.quarantineRoot || !guard.isRealDirectory(dir)) return
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.newDirectoryStream(d).use { s -> if (!s.iterator().hasNext()) Files.delete(d) }
                    } catch (_: IOException) {
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
    }

    private fun treeSize(dir: Path): Long {
        if (!guard.isRealDirectory(dir)) return 0
        var total = 0L
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
        }
        return total
    }

    /** Deletes a tree without following symlinks (links themselves are removed, never their targets). */
    private fun deleteTree(dir: Path) {
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        Files.deleteIfExists(file)
                    } catch (_: IOException) {
                        // Left for the next time; the rest still goes.
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.deleteIfExists(d)
                    } catch (_: IOException) {
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
    }
}
