package com.galaxy.steward.core.optimize

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone

/** A project or decompiled app in shared storage that would work faster inside Termux's home. */
data class ProjectCandidate(val path: String, val relPath: String, val files: Int, val bytes: Long, val newest: Long)

/**
 * Shared storage goes through Android's FUSE layer, which is slow with many small files: a phone had 243,637 of its
 * 251,374 files (97%) in Download/Projects. Each project there is a candidate to move into Termux's home, where Git,
 * jadx, apktool and the steward's own scans read it several times faster. The projects inside a folder like Projects or
 * workspace are offered one by one; elsewhere, any folder that is a project or a decompiled app.
 */
object ProjectMoves {
    fun candidates(tree: StorageTree, minFiles: Int = 200): List<ProjectCandidate> {
        val out = ArrayList<DirNode>()
        fun visit(d: DirNode) {
            if (d.hidden || d.zone == Zone.APP_OWNED || d.zone == Zone.STEWARD || d.zone == Zone.USER_PROTECTED) return
            if (d.depth == 1 && d.name == "Android") return
            if (d.depth >= 2 && SafetyPolicy.isDevContainerName(d.name)) {
                d.dirs.filterTo(out) { !it.hidden && it.totalFiles >= minFiles && it.zone != Zone.USER_PROTECTED }
                return
            }
            if (d.depth >= 2 && (d.hasFlag(NodeFlags.PROJECT_ROOT) || d.hasFlag(NodeFlags.CODE_TREE)) && d.totalFiles >= minFiles) {
                out += d
                return
            }
            d.dirs.forEach(::visit)
        }
        visit(tree.root)
        return out.map { d ->
            var newest = 0L
            d.walkFiles { if (it.mtime > newest) newest = it.mtime }
            ProjectCandidate(d.path, d.relPath, d.totalFiles, d.totalBytes, newest)
        }.sortedByDescending { it.files }
    }
}
