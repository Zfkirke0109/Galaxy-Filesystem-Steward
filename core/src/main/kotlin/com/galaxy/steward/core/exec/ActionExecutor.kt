package com.galaxy.steward.core.exec

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.hash.FileIdentity
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.junk.ExtractedArchives
import com.galaxy.steward.core.plan.DeleteDuplicateOp
import com.galaxy.steward.core.plan.MoveDirOp
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.Operation
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.core.plan.QuarantineOp
import com.galaxy.steward.core.plan.RemoveEmptyDirOp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

data class ExecutorOptions(
    /** Quarantine verified duplicates instead of deleting them. */
    val quarantineDuplicates: Boolean = false,
    val protectedFolders: List<String> = emptyList(),
    /** Remove folders that became empty because their last file was moved or deduped away. */
    val pruneEmptyParents: Boolean = true,
)

data class ExecutionSummary(
    val runId: String,
    val completedItemIds: Set<String>,
    val partialItemIds: Set<String>,
    val moved: Int,
    val deduped: Int,
    val quarantined: Int,
    val removedDirs: Int,
    val skipped: Int,
    val failed: Int,
    val bytesFreed: Long,
    val bytesQuarantined: Long,
    val bytesMoved: Long,
    val messages: List<String>,
    /** Paths whose existence changed - handed to the media scanner. */
    val changedPaths: List<String>,
    /** Regenerable files (caches, logs, temp) deleted for good. */
    val cleared: Int = 0,
    /** Why files were skipped or failed, with how many each: "Source is gone" to 12. Counts files, also inside merges. */
    val reasons: Map<String, Int> = emptyMap(),
) {
    val changedAnything: Boolean get() = moved + deduped + quarantined + removedDirs > 0

    /** Something in this run can be reverted from History. */
    val undoable: Boolean get() = changedAnything
}

fun interface ExecutionListener {
    fun onProgress(done: Int, total: Int, current: String)
}

/**
 * Applies plan items one operation at a time. Nothing is trusted from the plan: every operation re-checks
 * paths and file identity at run time, deletions re-hash both copies with a fresh SHA-256, moves never
 * overwrite, and each verified change is journaled before the next one starts.
 */
class ActionExecutor(
    rootPath: String,
    private val journals: JournalStore,
    private val options: ExecutorOptions = ExecutorOptions(),
) {
    private val guard = PathGuard(rootPath, options.protectedFolders)
    private val quarantine = QuarantineManager(guard, journals)

    private enum class Status { DONE, NOOP, SKIPPED, FAILED }

    private class Outcome(val status: Status, val message: String? = null)

    private lateinit var runId: String
    private lateinit var journal: JournalWriter
    private var moved = 0
    private var deduped = 0
    private var quarantined = 0
    private var removedDirs = 0
    private var skipped = 0
    private var failed = 0
    private var bytesFreed = 0L
    private var bytesQuarantined = 0L
    private var bytesMoved = 0L
    private val messages = ArrayList<String>()
    private val reasons = LinkedHashMap<String, Int>()
    private val changed = LinkedHashSet<String>()
    private val touchedParents = LinkedHashSet<Path>()

    suspend fun execute(
        title: String,
        kind: String,
        items: List<PlanItem>,
        listener: ExecutionListener? = null,
    ): ExecutionSummary {
        runId = journals.newId()
        val completed = LinkedHashSet<String>()
        val partial = LinkedHashSet<String>()
        val total = items.sumOf { it.operations.size }
        var done = 0
        JournalWriter(journals.fileFor(runId)).use { writer ->
            journal = writer
            writer.meta("title", title)
            writer.meta("kind", kind)
            writer.meta("started", System.currentTimeMillis().toString())
            writer.meta("items", items.size.toString())
            try {
                for (item in items) {
                    var allGood = true
                    for (op in item.operations) {
                        currentCoroutineContext().ensureActive()
                        listener?.onProgress(done, total, describe(op))
                        val outcome = try {
                            run(op)
                        } catch (e: IOException) {
                            Outcome(Status.FAILED, "I/O error: ${e.message ?: e.javaClass.simpleName}").also { tally("I/O error") }
                        } catch (e: SecurityException) {
                            fail("Permission denied")
                        }
                        when (outcome.status) {
                            Status.DONE, Status.NOOP -> Unit
                            Status.SKIPPED -> {
                                skipped++
                                allGood = false
                            }
                            Status.FAILED -> {
                                failed++
                                allGood = false
                            }
                        }
                        if (outcome.message != null && outcome.status >= Status.SKIPPED && messages.size < 200) {
                            messages.add("${outcome.message}: ${guard.relative(targetOf(op))}")
                        }
                        done++
                    }
                    if (allGood) completed.add(item.id) else partial.add(item.id)
                }
            } finally {
                if (options.pruneEmptyParents) pruneEmptyParents()
                writer.meta("finished", System.currentTimeMillis().toString())
                writer.meta(
                    "stats",
                    "moved=$moved deduped=$deduped quarantined=$quarantined rmdir=$removedDirs skipped=$skipped " +
                        "failed=$failed freed=$bytesFreed quarantinedBytes=$bytesQuarantined movedBytes=$bytesMoved",
                )
            }
        }
        listener?.onProgress(total, total, "")
        return ExecutionSummary(
            runId, completed, partial, moved, deduped, quarantined, removedDirs, skipped, failed,
            bytesFreed, bytesQuarantined, bytesMoved, messages.toList(), changed.toList(),
            reasons = reasons.toMap(),
        )
    }

    private fun describe(op: Operation): String = targetOf(op).substringAfterLast('/')

    private fun targetOf(op: Operation): String = when (op) {
        is MoveFileOp -> op.src
        is MoveDirOp -> op.src
        is DeleteDuplicateOp -> op.path
        is QuarantineOp -> op.path
        is RemoveEmptyDirOp -> op.path
    }

    private fun run(op: Operation): Outcome = when (op) {
        is MoveFileOp -> moveFile(op.src, op.dst, op.size, op.mtime, op.sha256)
        is MoveDirOp -> moveDir(op)
        is DeleteDuplicateOp -> deleteDuplicate(op)
        is QuarantineOp -> quarantineOp(op)
        is RemoveEmptyDirOp -> removeEmptyDir(op.path)
    }

    private fun skip(message: String) = Outcome(Status.SKIPPED, message).also { tally(message) }
    private fun fail(message: String) = Outcome(Status.FAILED, message).also { tally(message) }

    /** Counted by reason, so a run that skips thousands of files says why in one line of the log. */
    private fun tally(message: String) {
        reasons[message] = (reasons[message] ?: 0) + 1
    }

    private fun record(action: String, a: String, b: String = "", size: Long = -1, mtime: Long = -1, sha: String? = null) {
        journal.entry(JournalEntry(action, a, b, size, mtime, sha))
    }

    private fun mkdirs(dir: Path): Boolean = guard.ensureDirectories(dir) { created ->
        record(JournalAction.MKDIR, created.toString())
    }

    // ------------------------------------------------------------------ move file

    private fun moveFile(src: String, requestedDst: String, size: Long, mtime: Long, planSha: String?): Outcome {
        guard.protectionReason(src)?.let { return skip("Protected ($it)") }
        guard.protectionReason(requestedDst)?.let { return skip("Protected destination ($it)") }
        val srcPath = Paths.get(src)
        val id = FileIdentity.of(src) ?: return skip("Source is gone")
        if (id.size != size || (mtime >= 0 && id.mtime != mtime)) return skip("Changed since the scan")
        if (!guard.resolvesBeneath(srcPath)) return skip("Unsafe source path")

        var dst = requestedDst
        if (guard.exists(Paths.get(dst))) {
            val existing = FileIdentity.of(dst)
            val srcSha = Hashing.sha256(src)
            if (existing != null && existing.size == id.size) {
                if (existing.key != null && existing.key == id.key) return Outcome(Status.NOOP)
                if (Hashing.sha256(dst) == srcSha) {
                    // Identical file already filed at the destination: this copy is a verified duplicate.
                    return removeVerifiedDuplicate(src, dst, id, srcSha)
                }
            }
            dst = guard.uniqueCollisionPath(dst, planSha ?: srcSha)
        }
        val dstPath = Paths.get(dst)
        if (!mkdirs(dstPath.parent)) return fail("Cannot create destination folder")
        if (guard.exists(dstPath)) return skip("Destination appeared during the move")
        Files.move(srcPath, dstPath)
        val after = FileIdentity.of(dst)
        if (guard.exists(srcPath) || after == null || after.size != id.size) {
            if (!guard.exists(srcPath) && after != null) {
                try {
                    Files.move(dstPath, srcPath)
                } catch (_: IOException) {
                }
            }
            return fail("Post-move verification failed")
        }
        record(JournalAction.MOVED, src, dst, id.size, id.mtime, planSha)
        moved++
        bytesMoved += id.size
        changed += src
        changed += dst
        touchedParents.add(srcPath.parent)
        return Outcome(Status.DONE)
    }

    // ------------------------------------------------------------------ move directory

    private fun moveDir(op: MoveDirOp): Outcome {
        guard.protectionReason(op.src)?.let { return skip("Protected ($it)") }
        guard.protectionReason(op.dst)?.let { return skip("Protected destination ($it)") }
        if (op.dst == op.src || op.dst.startsWith(op.src + "/")) return fail("Cannot move a folder into itself")
        val src = Paths.get(op.src)
        val dst = Paths.get(op.dst)
        if (!guard.isRealDirectory(src)) return skip("Folder is gone")
        if (!guard.resolvesBeneath(src)) return skip("Unsafe source path")
        if (op.allowRename && !guard.exists(dst)) {
            if (!mkdirs(dst.parent)) return fail("Cannot create destination folder")
            val renamed = try {
                if (guard.exists(dst)) false else {
                    Files.move(src, dst)
                    true
                }
            } catch (_: IOException) {
                false
            }
            if (renamed) {
                if (guard.exists(src) || !guard.isRealDirectory(dst)) return fail("Post-move verification failed")
                record(JournalAction.MOVED_DIR, op.src, op.dst)
                moved++
                changed += op.src
                changed += op.dst
                touchedParents.add(src.parent)
                return Outcome(Status.DONE)
            }
        }
        return mergeDir(src, dst)
    }

    /** File-by-file merge. Nested projects, Git trees, credentials, symlinks and specials stay where they are. */
    private fun mergeDir(src: Path, dst: Path): Outcome {
        val files = ArrayList<Pair<Path, BasicFileAttributes>>()
        val dirs = ArrayList<Path>()
        var leftBehind = 0
        val stack = ArrayDeque<Path>()
        stack.addLast(src)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            dirs.add(dir)
            val entries = try {
                Files.newDirectoryStream(dir).use { s -> s.toList() }
            } catch (_: IOException) {
                leftBehind++
                continue
            }
            if (dir != src && SafetyPolicy.isProjectRoot(entries.map { it.fileName.toString() })) {
                leftBehind++
                continue
            }
            for (entry in entries) {
                val name = entry.fileName.toString()
                val attrs = try {
                    Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    leftBehind++
                    continue
                }
                when {
                    attrs.isSymbolicLink || attrs.isOther || SafetyPolicy.isUnsafeName(name) -> leftBehind++
                    attrs.isDirectory -> if (name == ".git") leftBehind++ else stack.addLast(entry)
                    SafetyPolicy.isCredentialName(name) -> leftBehind++
                    else -> files.add(entry to attrs)
                }
            }
        }
        var problems = 0
        for ((file, attrs) in files) {
            val target = dst.resolve(src.relativize(file).toString()).toString()
            val outcome = moveFile(file.toString(), target, attrs.size(), attrs.lastModifiedTime().toMillis(), null)
            if (outcome.status == Status.SKIPPED || outcome.status == Status.FAILED) problems++
        }
        for (dir in dirs.sortedByDescending { it.nameCount }) removeEmptyDir(dir.toString())
        return when {
            problems > 0 -> Outcome(Status.SKIPPED, "$problems file(s) could not be merged")
            leftBehind > 0 -> Outcome(Status.SKIPPED, "$leftBehind protected item(s) left in place").also { tally("Protected items left in place") }
            else -> Outcome(Status.DONE)
        }
    }

    // ------------------------------------------------------------------ duplicates

    private fun deleteDuplicate(op: DeleteDuplicateOp): Outcome {
        if (op.path == op.canonical) return fail("Refusing to delete the kept copy")
        guard.protectionReason(op.path)?.let { return skip("Protected ($it)") }
        if (!guard.wellFormed(op.canonical)) return skip("Unsafe canonical path")
        val id = FileIdentity.of(op.path) ?: return Outcome(Status.NOOP)
        val canon = FileIdentity.of(op.canonical) ?: return skip("Kept copy is missing")
        if (id.size != op.size || canon.size != op.size) return skip("Size changed since the scan")
        if (id.key != null && id.key == canon.key) return skip("Same physical file (hard link)")
        if (!guard.resolvesBeneath(Paths.get(op.path)) || !guard.resolvesBeneath(Paths.get(op.canonical))) {
            return skip("Unsafe path")
        }
        // Fresh, uncached SHA-256 of both copies immediately before deletion.
        val sha = Hashing.sha256(op.path)
        if (sha != op.sha256) return skip("Content changed since the scan")
        if (Hashing.sha256(op.canonical) != sha) return skip("Kept copy no longer matches")
        return removeVerifiedDuplicate(op.path, op.canonical, id, sha)
    }

    private fun removeVerifiedDuplicate(path: String, canonical: String, id: FileIdentity, sha: String): Outcome {
        val p = Paths.get(path)
        if (options.quarantineDuplicates) {
            val q = quarantineTarget(path) ?: return fail("Quarantine unavailable")
            Files.move(p, q)
            if (guard.exists(p)) return fail("Post-quarantine verification failed")
            record(JournalAction.DEDUPED_QUARANTINED, path, q.toString(), id.size, id.mtime, sha)
            bytesQuarantined += id.size
        } else {
            Files.delete(p)
            if (guard.exists(p)) return fail("Delete verification failed")
            record(JournalAction.DEDUPED, path, canonical, id.size, id.mtime, sha)
            bytesFreed += id.size
        }
        deduped++
        changed += path
        touchedParents.add(p.parent)
        return Outcome(Status.DONE)
    }

    // ------------------------------------------------------------------ quarantine

    private fun quarantineTarget(path: String): Path? {
        if (!quarantine.prepare()) return null
        val target = quarantine.dirFor(runId).resolve(guard.relative(path))
        if (!guard.ensureDirectories(target.parent) { }) return null
        return if (guard.exists(target)) null else target
    }

    private fun quarantineOp(op: QuarantineOp): Outcome {
        guard.protectionReason(op.path)?.let { return skip("Protected ($it)") }
        val p = Paths.get(op.path)
        if (!guard.exists(p)) return Outcome(Status.NOOP)
        if (!guard.resolvesBeneath(p)) return skip("Unsafe path")
        var mtime = op.mtime
        if (op.isDirectory) {
            if (!guard.isRealDirectory(p)) return skip("No longer a folder")
        } else {
            val id = FileIdentity.of(op.path) ?: return skip("No longer a regular file")
            if (id.size != op.size || id.mtime != op.mtime) return skip("Changed since the scan")
            mtime = id.mtime
        }
        if (op.extracted != null && !ExtractedArchives.verify(op.path, op.extracted)) {
            return skip("The unpacked copy no longer matches the zip")
        }
        val q = quarantineTarget(op.path) ?: return fail("Quarantine unavailable")
        Files.move(p, q)
        if (guard.exists(p) || !guard.exists(q)) return fail("Post-quarantine verification failed")
        record(JournalAction.QUARANTINED, op.path, q.toString(), op.size, mtime)
        quarantined++
        bytesQuarantined += op.size
        changed += op.path
        touchedParents.add(p.parent)
        return Outcome(Status.DONE)
    }

    // ------------------------------------------------------------------ directories

    private fun removeEmptyDir(path: String): Outcome {
        guard.protectionReason(path)?.let { return skip("Protected ($it)") }
        val p = Paths.get(path)
        if (!guard.exists(p)) return Outcome(Status.NOOP)
        if (!guard.isRealDirectory(p)) return skip("Not a folder")
        val rel = guard.relative(path)
        if (!rel.contains('/') && SafetyPolicy.isKeptTopDir(rel)) return skip("Standard Android folder")
        val empty = Files.newDirectoryStream(p).use { !it.iterator().hasNext() }
        if (!empty) return skip("Folder is not empty")
        val mtime = try {
            Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis()
        } catch (_: IOException) {
            -1L
        }
        Files.delete(p)
        record(JournalAction.RMDIR, path, mtime = mtime)
        removedDirs++
        changed += path
        touchedParents.add(p.parent)
        return Outcome(Status.DONE)
    }

    /** Collapses folders emptied by this run, walking upwards but never removing a top-level folder. */
    private fun pruneEmptyParents() {
        val queue = ArrayDeque(touchedParents.sortedByDescending { it.nameCount })
        val seen = HashSet<Path>()
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (!seen.add(dir) || !dir.startsWith(guard.root) || dir == guard.root) continue
            val rel = guard.root.relativize(dir)
            if (rel.nameCount < 2 || rel.any { it.toString().startsWith('.') }) continue
            if (guard.protectionReason(dir.toString()) != null || !guard.isRealDirectory(dir)) continue
            val empty = try {
                Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }
            } catch (_: IOException) {
                false
            }
            if (empty && removeEmptyDir(dir.toString()).status == Status.DONE) queue.addLast(dir.parent)
        }
    }
}
