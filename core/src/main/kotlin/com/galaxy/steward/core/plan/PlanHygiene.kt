package com.galaxy.steward.core.plan

import com.galaxy.steward.core.exec.PathGuard

/**
 * Leaves out of a plan what the executor would refuse for a reason that can't change before the run: a pinned folder,
 * a key's name, app-owned storage. Such an item was offered on every scan and skipped on every run; a phone showed
 * the same 1,685 moves skipped by two Autopilot runs in a row.
 */
class PlanHygiene(private val guard: PathGuard) {
    private fun refused(path: String) = guard.protectionReason(path) != null

    private fun refused(op: Operation): Boolean = when (op) {
        is MoveFileOp -> refused(op.src) || refused(op.dst)
        is MoveDirOp -> refused(op.src) || refused(op.dst)
        is QuarantineOp -> refused(op.path)
        is DeleteDuplicateOp -> refused(op.path)
        is RemoveEmptyDirOp -> refused(op.path)
        is PackDirOp -> refused(op.path) || refused(op.zip)
    }

    fun junk(items: List<JunkItem>): List<JunkItem> = items.filterNot { refused(it.path) }

    fun organize(moves: List<OrganizeMove>): List<OrganizeMove> = moves.filterNot { m -> m.operations.any(::refused) }

    /** Items keep the operations that can run; an item with nothing left to move or remove is dropped. */
    fun optimize(items: List<OptimizeItem>): List<OptimizeItem> = items.mapNotNull { item ->
        val kept = item.operations.filterNot(::refused)
        when {
            kept.size == item.operations.size -> item
            kept.none { it !is RemoveEmptyDirOp } -> null
            else -> item.copy(operations = kept, fileCount = kept.count { it is MoveFileOp }.takeIf { it > 0 } ?: item.fileCount)
        }
    }
}
