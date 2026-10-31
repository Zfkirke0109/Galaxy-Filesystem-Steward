package com.galaxy.steward.core.exec

import com.galaxy.steward.core.hash.FileIdentity
import com.galaxy.steward.core.plan.DeleteDuplicateOp
import com.galaxy.steward.core.plan.MoveDirOp
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.Operation
import com.galaxy.steward.core.plan.PackDirOp
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.core.plan.QuarantineOp
import com.galaxy.steward.core.plan.RemoveEmptyDirOp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Paths

/** What a run would find, from [Preflight.check]: steps as scanned, and the others by why they would be skipped. */
data class PreflightResult(val total: Int, val stale: Map<String, Int>) {
    val skipped: Int get() = stale.values.sum()
    val ready: Int get() = total - skipped
}

/**
 * The dry run: what applying [PlanItem]s would find now, worked out without changing anything. For each step, the checks
 * a run makes first that cost no reading: the file or folder is still there, as big and as old as the scan saw it, not
 * protected since (pinned, a key), and a zip's name still free; and, in order as a run takes them, a folder going to the
 * quarantine that holds a copy kept for a duplicate removed before it. A run makes these again, and more (a fresh
 * SHA-256 before every delete), right before each step; this tells you beforehand what has changed since the scan.
 */
object Preflight {
    const val GONE = "Gone since the scan"
    const val CHANGED = "Changed since the scan"
    const val PROTECTED = "Protected"
    const val KEPT_MISSING = "Kept copy is missing"
    const val ZIP_TAKEN = "A file with the zip's name is there"
    const val SAME_FILE = "Same file as the kept copy (a hard link)"
    const val KEPT_COPY = "Holds a copy kept in this run"

    suspend fun check(rootPath: String, items: List<PlanItem>, protectedFolders: List<String> = emptyList()): PreflightResult {
        val run = Run(PathGuard(rootPath, protectedFolders))
        val stale = LinkedHashMap<String, Int>()
        var total = 0
        for (item in items) {
            for (op in item.operations) {
                currentCoroutineContext().ensureActive()
                total++
                run.reason(op)?.let { stale[it] = (stale[it] ?: 0) + 1 }
            }
        }
        return PreflightResult(total, stale)
    }

    /** The run so far, as a run would take the steps: copies kept for the duplicates it removes, folders it moves away. */
    private class Run(val guard: PathGuard) {
        val kept = java.util.TreeSet<String>()
        val away = HashSet<String>()

        fun reason(op: Operation): String? = check(op).also { why ->
            if (why != null) return@also
            when (op) {
                is DeleteDuplicateOp -> if (FileIdentity.of(op.path) != null) kept += op.canonical
                is QuarantineOp -> away += op.path
                is PackDirOp -> away += op.path
                else -> Unit
            }
        }

        private fun holdsKept(path: String) = path in kept || kept.ceiling("$path/")?.startsWith("$path/") == true

        private fun movedAway(path: String): Boolean {
            var at = path
            while (at.isNotEmpty()) {
                if (at in away) return true
                at = at.substringBeforeLast('/', "")
            }
            return false
        }

        /** Why [op] would be skipped now, or null when it is as the scan planned it. */
        private fun check(op: Operation): String? = when (op) {
            is MoveFileOp -> {
                val id = FileIdentity.of(op.src)
                when {
                    guard.protectionReason(op.src) != null || guard.protectionReason(op.dst) != null -> PROTECTED
                    id == null -> GONE
                    id.size != op.size || (op.mtime >= 0 && id.mtime != op.mtime) -> CHANGED
                    else -> null
                }
            }
            is MoveDirOp -> when {
                guard.protectionReason(op.src) != null || guard.protectionReason(op.dst) != null -> PROTECTED
                !guard.isRealDirectory(Paths.get(op.src)) -> GONE
                else -> null
            }
            is DeleteDuplicateOp -> {
                val id = FileIdentity.of(op.path)
                val canon = FileIdentity.of(op.canonical)
                when {
                    guard.protectionReason(op.path) != null -> PROTECTED
                    // Gone already: nothing left to do, which a run counts as done.
                    id == null -> null
                    canon == null || movedAway(op.canonical) -> KEPT_MISSING
                    id.size != op.size || canon.size != op.size -> CHANGED
                    id.key != null && id.key == canon.key -> SAME_FILE
                    else -> null
                }
            }
            is QuarantineOp -> when {
                guard.protectionReason(op.path) != null -> PROTECTED
                holdsKept(op.path) -> KEPT_COPY
                !guard.exists(Paths.get(op.path)) -> null // gone already: nothing to do
                op.isDirectory -> if (guard.isRealDirectory(Paths.get(op.path))) null else CHANGED
                // A run checks the date exactly here: a file in the quarantine is put back as it was.
                else -> FileIdentity.of(op.path).let { if (it == null || it.size != op.size || it.mtime != op.mtime) CHANGED else null }
            }
            is RemoveEmptyDirOp -> if (guard.protectionReason(op.path) != null) PROTECTED else null
            is PackDirOp -> when {
                guard.protectionReason(op.path) != null || guard.protectionReason(op.zip) != null -> PROTECTED
                holdsKept(op.path) -> KEPT_COPY
                !guard.isRealDirectory(Paths.get(op.path)) -> GONE
                guard.exists(Paths.get(op.zip)) -> ZIP_TAKEN
                else -> null
            }
        }
    }
}
