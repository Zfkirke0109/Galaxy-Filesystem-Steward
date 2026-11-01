package com.galaxy.steward.core.exec

import com.galaxy.steward.core.hash.FileIdentity
import com.galaxy.steward.core.hash.Hashing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

data class RollbackSummary(
    val restored: Int,
    val skipped: Int,
    val failed: Int,
    val messages: List<String>,
    val changedPaths: List<String>,
    /** Quarantined items still in the quarantine after this undo; while there are any, the run can be undone again. */
    val remaining: Int = 0,
)

/**
 * Undoes a run by replaying its journal backwards, like `rollback_shared_organization_v18`. Each step is
 * verified first: a moved file must still have its journaled size and hash, a deleted duplicate is recreated
 * only from a kept copy whose SHA-256 still matches, and nothing that reappeared at the original path is
 * ever overwritten.
 */
class RollbackEngine(rootPath: String, private val journals: JournalStore?) {
    private val guard = PathGuard(rootPath)

    suspend fun rollback(runId: String, listener: ExecutionListener? = null): RollbackSummary {
        val store = requireNotNull(journals) { "No journal store" }
        // An unreadable journal says nothing about the run: it isn't "undone with nothing to restore".
        val journal = store.readEntries(runId) ?: throw IOException("This run's journal couldn't be read, so nothing was undone")
        val summary = rollbackEntries(journal, listener)
        // Undone once nothing is left in the quarantine, recorded or not. A run marked undone loses its Undo button, and
        // whatever had stayed behind would be lost when the quarantine is emptied.
        val left = summary.remaining + QuarantineManager(guard, store).unrecorded(runId, journal).size
        if (left == 0) store.appendMeta(runId, "rolledback", System.currentTimeMillis().toString())
        return summary.copy(remaining = left)
    }

    /**
     * Whether a change written ahead and never confirmed (the run was stopped in between, [JournalAction.settle]) shows in
     * the files: the item is at its new place and not at its old one, or the zip is there.
     */
    fun happened(e: JournalEntry): Boolean {
        if (!guard.wellFormed(e.a) || (e.b.isNotEmpty() && !guard.wellFormed(e.b))) return false
        val a = Paths.get(e.a)
        return when (e.action) {
            JournalAction.PACKED -> guard.exists(a)
            JournalAction.DEDUPED -> !guard.exists(a) && guard.exists(Paths.get(e.b))
            // Its new place was checked free just before (in the quarantine, only this run writes there): what is there
            // now is what moved, even if something new has taken the old place since.
            else -> e.b.isNotEmpty() && guard.exists(Paths.get(e.b))
        }
    }

    /** Reverts [journal] (in journal order) without touching any journal file - used by the Shizuku helper. */
    suspend fun rollbackEntries(journal: List<JournalEntry>, listener: ExecutionListener? = null): RollbackSummary {
        val journalEntries = JournalAction.settle(journal, ::happened)
        val entries = journalEntries.asReversed()
        var restored = 0
        var skipped = 0
        var failed = 0
        val messages = ArrayList<String>()
        val changed = LinkedHashSet<String>()

        fun note(status: String, msg: String, path: String) {
            if (messages.size < 200) messages.add("$status $msg: ${guard.relative(path)}")
        }

        entries.forEachIndexed { index, e ->
            currentCoroutineContext().ensureActive()
            listener?.onProgress(index, entries.size, e.a.substringAfterLast('/'))
            restoredAs = null
            val result: String? = try {
                when (e.action) {
                    JournalAction.MOVED -> restoreMovedFile(e)
                    JournalAction.MOVED_DIR -> restoreMove(e.b, e.a, directory = true)
                    JournalAction.QUARANTINED, JournalAction.DEDUPED_QUARANTINED ->
                        changedInQuarantine(e) ?: restoreMove(e.b, e.a, directory = null, beside = true)
                    JournalAction.DEDUPED -> recreateDuplicate(e)
                    JournalAction.RMDIR -> recreateDir(e.a)
                    JournalAction.MKDIR -> removeCreatedDir(e.a)
                    JournalAction.PACKED -> removeCreatedZip(e)
                    JournalAction.RELOCATED -> "Moved into Termux: moving it back goes through Termux"
                    // Caches, logs and temp files were deleted for good; there is nothing to bring back.
                    JournalAction.PURGED -> ""
                    else -> "Unknown journal action"
                }
            } catch (ex: IOException) {
                failed++
                note("FAILED", ex.message ?: "I/O error", e.a)
                return@forEachIndexed
            }
            if (result == null) {
                restored++
                changed += restoredAs ?: e.a
                if (e.b.isNotEmpty()) changed += e.b
                restoredAs?.let { note("RESTORED", "beside the newer item that has its name now", it) }
            } else if (result.isNotEmpty()) {
                skipped++
                note("SKIPPED", result, e.a)
            }
        }
        listener?.onProgress(entries.size, entries.size, "")
        val remaining = journalEntries.count { e ->
            (e.action == JournalAction.QUARANTINED || e.action == JournalAction.DEDUPED_QUARANTINED) &&
                guard.wellFormed(e.b) && guard.exists(Paths.get(e.b))
        }
        return RollbackSummary(restored, skipped, failed, messages, changed.toList(), remaining)
    }

    /** Where the entry being reverted went back to when that wasn't its own path; see [restoreMove]. */
    private var restoredAs: String? = null

    private fun ensureParent(path: Path): Boolean = guard.ensureDirectories(path.parent) { }

    /** Returns null on success, a reason when skipped. */
    private fun restoreMovedFile(e: JournalEntry): String? {
        val original = Paths.get(e.a)
        val current = Paths.get(e.b)
        if (!guard.wellFormed(e.a) || !guard.wellFormed(e.b)) return "Unsafe path"
        if (guard.exists(original)) return "Original path is occupied"
        val id = FileIdentity.of(e.b) ?: return "Moved file is gone"
        if (id.size != e.size) return "Moved file changed"
        if (e.sha256 != null && Hashing.sha256(e.b) != e.sha256) return "Moved file changed"
        if (!guard.resolvesBeneath(current)) return "Unsafe path"
        if (!ensureParent(original)) return "Cannot recreate the original folder"
        Files.move(current, original)
        return null
    }

    /**
     * Moves [from] back to [to]. With [beside], something that has taken the name since keeps it, and what comes back
     * out of the quarantine goes next to it ("IMG_1 (restored).jpg"): left in the quarantine, it would be lost when that
     * is emptied. Nothing is ever overwritten.
     */
    private fun restoreMove(from: String, to: String, directory: Boolean?, beside: Boolean = false): String? {
        val src = Paths.get(from)
        var dst = Paths.get(to)
        if (!guard.wellFormed(to) || !from.startsWith(guard.rootString + "/")) return "Unsafe path"
        if (guard.exists(dst)) {
            if (!beside || !guard.exists(src)) return "Original path is occupied"
            // An app's own folder taken again (the app was installed again): "com.app (restored)" next to it is a folder no
            // app or scan looks at. It stays in the quarantine, and the run stays undoable.
            if (isAppFolder(to)) return "The app has made a new folder since; this one stays in the quarantine"
            dst = freeNameBeside(dst, guard.isRealDirectory(src)) ?: return "Original path is occupied"
            restoredAs = dst.toString()
        }
        if (!guard.exists(src)) return "No longer available (quarantine emptied?)"
        if (directory == true && !guard.isRealDirectory(src)) return "Not a folder"
        if (Files.isSymbolicLink(src)) return "Unsafe path"
        if (!ensureParent(dst)) return "Cannot recreate the original folder"
        Files.move(src, dst)
        return null
    }

    /**
     * Why [e] must not go back, when it goes into an app's private folder: `Android/data` or `obb`, which only that app
     * and the shell can write, and where Undo of an app-folder run writes with the shell's rights. The quarantine is in
     * shared storage, where any app with All-files access can add to it or change it; what goes back must be what the
     * run moved. A file must have its journaled size and date, a folder the digest of what it held ([TreeDigest]).
     * Lines written before 1.2.14, or by a run stopped between a folder's move and its line, have no digest: those
     * folders go back as before.
     */
    private fun changedInQuarantine(e: JournalEntry): String? {
        val parts = guard.relative(e.a).split('/')
        val private = parts.size >= 3 && parts[0].equals("Android", ignoreCase = true) &&
            (parts[1].equals("data", ignoreCase = true) || parts[1].equals("obb", ignoreCase = true))
        if (!private || !guard.wellFormed(e.b)) return null
        val src = Paths.get(e.b)
        val a = try {
            Files.readAttributes(src, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return null // Gone: restoreMove says so.
        }
        return when {
            a.isSymbolicLink || a.isOther -> "Unsafe path"
            a.isRegularFile -> if (a.size() != e.size || (e.mtime > 0 && a.lastModifiedTime().toMillis() != e.mtime)) CHANGED_IN_QUARANTINE else null
            e.sha256?.startsWith(TreeDigest.PREFIX) == true -> if (TreeDigest.of(src) != e.sha256) CHANGED_IN_QUARANTINE else null
            else -> null
        }
    }

    /** Android/data, obb or media's folder for one app: `Android/data/com.app`. */
    private fun isAppFolder(path: String): Boolean {
        val parts = guard.relative(path).split('/')
        return parts.size == 3 && parts[0] == "Android" && parts[1] in setOf("data", "obb", "media")
    }

    private fun freeNameBeside(path: Path, directory: Boolean): Path? {
        val name = path.fileName.toString()
        val dot = if (directory) -1 else name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        return (1..99).asSequence()
            .map { n -> path.resolveSibling("$stem (restored${if (n == 1) "" else " $n"})$ext") }
            .firstOrNull { !guard.exists(it) }
    }

    private fun recreateDuplicate(e: JournalEntry): String? {
        val original = Paths.get(e.a)
        if (!guard.wellFormed(e.a) || !guard.wellFormed(e.b)) return "Unsafe path"
        if (guard.exists(original)) return "Original path is occupied"
        val canon = FileIdentity.of(e.b) ?: return "Kept copy is gone"
        if (canon.size != e.size || (e.sha256 != null && Hashing.sha256(e.b) != e.sha256)) return "Kept copy changed"
        if (!ensureParent(original)) return "Cannot recreate the original folder"
        Files.copy(Paths.get(e.b), original)
        if (e.mtime > 0) Files.setLastModifiedTime(original, FileTime.fromMillis(e.mtime))
        return null
    }

    private fun recreateDir(path: String): String? {
        if (!guard.wellFormed(path)) return "Unsafe path"
        val p = Paths.get(path)
        if (guard.isRealDirectory(p)) return ""
        if (!guard.ensureDirectories(p) { }) return "Cannot recreate folder"
        return null
    }

    /** The zip a pack run made, once its folder is back: only while it is exactly the zip that run wrote. */
    private fun removeCreatedZip(e: JournalEntry): String? {
        if (!guard.wellFormed(e.a) || !guard.wellFormed(e.b)) return "Unsafe path"
        val id = FileIdentity.of(e.a) ?: return ""
        if (!guard.isRealDirectory(Paths.get(e.b))) return "The folder isn't back, so its zip stays"
        if (id.size != e.size || (e.sha256 != null && Hashing.sha256(e.a) != e.sha256)) return "The zip changed since it was made, so it stays"
        Files.delete(Paths.get(e.a))
        return null
    }

    private fun removeCreatedDir(path: String): String? {
        val p = Paths.get(path)
        if (!guard.wellFormed(path) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return ""
        val empty = Files.newDirectoryStream(p).use { !it.iterator().hasNext() }
        if (!empty) return ""
        Files.delete(p)
        return null
    }

    companion object {
        /** Why Undo leaves something in the quarantine that changed there since the run ([changedInQuarantine]). */
        const val CHANGED_IN_QUARANTINE = "Changed in the quarantine since the run, so it stays there"
    }
}
