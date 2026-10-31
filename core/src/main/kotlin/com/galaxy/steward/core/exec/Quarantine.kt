package com.galaxy.steward.core.exec

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.SafetyPolicy
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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

    fun sizeOf(runId: String): Long = runDir(runId)?.let(::treeSize) ?: 0L

    fun totalSize(): Long = treeSize(guard.quarantineRoot)

    fun runsWithContent(): List<String> = try {
        // A quarantine that leads anywhere else (a link put in its place) is not the quarantine: nothing in it is listed,
        // so nothing outside is ever emptied.
        if (!guard.resolvesBeneath(guard.quarantineRoot)) {
            emptyList()
        } else {
            Files.newDirectoryStream(guard.quarantineRoot).use { stream ->
                stream.filter { guard.isRealDirectory(it) }.map { it.fileName.toString() }
            }
        }
    } catch (_: IOException) {
        emptyList()
    }

    /** [runId]'s folder in the quarantine, when it really is one, beneath the root through no link. */
    private fun runDir(runId: String): Path? {
        val dir = dirFor(runId)
        if (!dir.startsWith(guard.quarantineRoot) || dir == guard.quarantineRoot || !guard.isRealDirectory(dir)) return null
        return dir.takeIf { guard.resolvesBeneath(it) }
    }

    /** Where [journal] (one run's, as read) put things in that run's quarantine. */
    private fun recorded(runId: String, journal: List<JournalEntry>): List<Path> {
        val dir = dirFor(runId)
        return JournalAction.settle(journal) { true }
            .filter { it.action == JournalAction.QUARANTINED || it.action == JournalAction.DEDUPED_QUARANTINED }
            .mapNotNull { e -> runCatching { Paths.get(e.b) }.getOrNull() }
            .filter { it.startsWith(dir) && it != dir }
    }

    /**
     * What [runId]'s quarantine holds that its [journal] doesn't record (a move whose line was lost when Android stopped
     * the app or the power went): the topmost such files and folders.
     */
    fun unrecorded(runId: String, journal: List<JournalEntry>): List<Path> {
        val dir = runDir(runId) ?: return emptyList()
        val known = recorded(runId, journal).toHashSet()
        if (known.contains(dir)) return emptyList()
        val out = ArrayList<Path>()
        val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) {
            val folder = stack.removeLast()
            val children = try {
                Files.newDirectoryStream(folder).use { it.toList() }
            } catch (_: IOException) {
                continue
            }
            for (c in children) {
                when {
                    c in known -> Unit
                    // A folder on the way to something recorded: look inside.
                    guard.isRealDirectory(c) && known.any { it.startsWith(c) } -> stack.addLast(c)
                    // Folders the executor made on the way, left empty: nothing to keep.
                    guard.isRealDirectory(c) && treeSize(c) == 0L && !holdsAnyFile(c) -> Unit
                    else -> out.add(c)
                }
            }
        }
        return out
    }

    private fun holdsAnyFile(dir: Path): Boolean = try {
        Files.walk(dir).use { s -> s.anyMatch { !Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) } }
    } catch (_: IOException) {
        true
    } catch (_: java.io.UncheckedIOException) {
        true
    }

    /**
     * Runs of this install that Android stopped mid-way and whose quarantine holds something their journal doesn't record.
     * Like [orphans], History offers to put that back; it is never emptied on its own.
     */
    fun interruptedWithUnrecorded(): List<String> {
        val store = journals ?: return emptyList()
        return runsWithContent().filter { id ->
            val info = store.info(id) ?: return@filter false
            info.finishedAt == null && unrecorded(id, store.readEntries(id) ?: return@filter false).isNotEmpty()
        }
    }

    /** Bytes of what [unrecorded] finds in [runId]'s quarantine. */
    fun unrecordedBytes(runId: String): Long {
        val store = journals ?: return 0
        return unrecorded(runId, store.readEntries(id = runId) ?: return 0).sumOf { if (guard.isRealDirectory(it)) treeSize(it) else Files.size(it) }
    }

    /**
     * Permanently deletes one run's quarantine and, once nothing of it is left, marks its journal as purged. Returns
     * the bytes actually freed: a file that can't be deleted stays, with its run's Undo, and isn't counted.
     *
     * With [recordedOnly] (emptying by age, which nobody watches), only what the run's journal records goes: anything
     * else there (a move whose journal line was lost, say to a power cut) is never deleted unasked.
     */
    fun purge(runId: String, now: Long = System.currentTimeMillis(), recordedOnly: Boolean = false): Long {
        val dir = runDir(runId) ?: return 0
        val before = treeSize(dir)
        if (recordedOnly) {
            val store = journals ?: return 0
            for (p in recorded(runId, store.readEntries(runId) ?: return 0)) {
                if (guard.isRealDirectory(p)) {
                    deleteTree(p)
                } else {
                    try {
                        Files.deleteIfExists(p)
                    } catch (_: IOException) {
                    }
                }
            }
            pruneEmpty(runId)
        } else {
            deleteTree(dir)
        }
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
            if (finished in 1 until cutoff) purge(id, now, recordedOnly = true) else 0L
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
        val dir = runDir(runId) ?: return emptyList()
        return entriesFor(dir, listOf(dir))
    }

    /** Put-back entries ([orphanEntries]) for what [unrecorded] finds in a run of this install. */
    fun unrecordedEntries(runId: String): List<JournalEntry> {
        val dir = runDir(runId) ?: return emptyList()
        val store = journals ?: return emptyList()
        val items = unrecorded(runId, store.readEntries(runId) ?: return emptyList())
        val (folders, files) = items.partition { guard.isRealDirectory(it) }
        return entriesFor(dir, folders) + files.mapNotNull { q -> entryFor(dir, q) }
    }

    private fun entryFor(dir: Path, q: Path): JournalEntry? {
        if (Files.isSymbolicLink(q)) return null
        val rel = dir.relativize(q)
        if (rel.getName(0).toString().let { it == "Android" || it == SafetyPolicy.STEWARD_DIR }) return null
        return JournalEntry(JournalAction.QUARANTINED, guard.root.resolve(rel.toString()).toString(), q.toString(), -1, -1, null)
    }

    private fun entriesFor(dir: Path, starts: List<Path>): List<JournalEntry> {
        val entries = ArrayList<JournalEntry>()
        val stack = ArrayDeque(starts)
        while (stack.isNotEmpty()) {
            val folder = stack.removeLast()
            if (folder != dir) {
                // A folder of its own (one [unrecorded] found): it goes back whole unless its folder exists again.
                val entry = entryFor(dir, folder) ?: continue
                if (!guard.isRealDirectory(Paths.get(entry.a))) {
                    entries += entry
                    continue
                }
            }
            val children = try {
                Files.newDirectoryStream(folder).use { it.toList() }
            } catch (_: IOException) {
                continue
            }
            for (q in children) {
                val entry = entryFor(dir, q) ?: continue
                if (guard.isRealDirectory(q) && guard.isRealDirectory(Paths.get(entry.a))) {
                    stack.addLast(q)
                } else {
                    entries += entry
                }
            }
        }
        return entries
    }

    /** Removes the folders [runId]'s quarantine is left with once everything in them went back. */
    fun pruneEmpty(runId: String) {
        val dir = runDir(runId) ?: return
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
