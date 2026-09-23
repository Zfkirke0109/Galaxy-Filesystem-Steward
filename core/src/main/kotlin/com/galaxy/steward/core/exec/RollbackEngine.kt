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
import java.nio.file.attribute.FileTime

data class RollbackSummary(
    val restored: Int,
    val skipped: Int,
    val failed: Int,
    val messages: List<String>,
    val changedPaths: List<String>,
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
        val summary = rollbackEntries(store.entries(runId), listener)
        store.appendMeta(runId, "rolledback", System.currentTimeMillis().toString())
        return summary
    }

    /** Reverts [journalEntries] (in journal order) without touching any journal file - used by the Shizuku helper. */
    suspend fun rollbackEntries(journalEntries: List<JournalEntry>, listener: ExecutionListener? = null): RollbackSummary {
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
            val result: String? = try {
                when (e.action) {
                    JournalAction.MOVED -> restoreMovedFile(e)
                    JournalAction.MOVED_DIR -> restoreMove(e.b, e.a, directory = true)
                    JournalAction.QUARANTINED, JournalAction.DEDUPED_QUARANTINED -> restoreMove(e.b, e.a, directory = null)
                    JournalAction.DEDUPED -> recreateDuplicate(e)
                    JournalAction.RMDIR -> recreateDir(e.a)
                    JournalAction.MKDIR -> removeCreatedDir(e.a)
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
                changed += e.a
                if (e.b.isNotEmpty()) changed += e.b
            } else if (result.isNotEmpty()) {
                skipped++
                note("SKIPPED", result, e.a)
            }
        }
        listener?.onProgress(entries.size, entries.size, "")
        return RollbackSummary(restored, skipped, failed, messages, changed.toList())
    }

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

    private fun restoreMove(from: String, to: String, directory: Boolean?): String? {
        val src = Paths.get(from)
        val dst = Paths.get(to)
        if (!guard.wellFormed(to) || !from.startsWith(guard.rootString + "/")) return "Unsafe path"
        if (guard.exists(dst)) return "Original path is occupied"
        if (!guard.exists(src)) return "No longer available (quarantine emptied?)"
        if (directory == true && !guard.isRealDirectory(src)) return "Not a folder"
        if (Files.isSymbolicLink(src)) return "Unsafe path"
        if (!ensureParent(dst)) return "Cannot recreate the original folder"
        Files.move(src, dst)
        return null
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

    private fun removeCreatedDir(path: String): String? {
        val p = Paths.get(path)
        if (!guard.wellFormed(path) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return ""
        val empty = Files.newDirectoryStream(p).use { !it.iterator().hasNext() }
        if (!empty) return ""
        Files.delete(p)
        return null
    }
}
