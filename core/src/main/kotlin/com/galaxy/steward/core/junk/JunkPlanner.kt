package com.galaxy.steward.core.junk

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.DeviceEnvironment
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem

/**
 * Finds reclaimable clutter. Everything except empty-folder removal goes through the quarantine, so a
 * mistaken category can always be restored until the quarantine is emptied.
 */
class JunkPlanner(
    private val settings: StewardSettings,
    private val environment: DeviceEnvironment,
) {
    private val now = environment.nowMillis()
    private val items = ArrayList<JunkItem>()

    fun plan(tree: StorageTree): List<JunkItem> {
        items.clear()
        walk(tree.root, insideHidden = false)
        planEmptyFolders(tree.root)
        planOrphanedAppFolders(tree.root)
        return items.sortedWith(compareBy<JunkItem> { it.category.ordinal }.thenByDescending { it.bytes }.thenBy { it.path })
    }

    private fun walk(dir: DirNode, insideHidden: Boolean) {
        if (!dir.zone.removable) return
        val hidden = insideHidden || dir.hidden
        if (dir.name == ".thumbnails" && dir.totalBytes > 0 && !dir.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)) {
            add(JunkCategory.THUMBNAIL_CACHES, dir.path, true, dir.totalBytes, dir.mtime, "${dir.totalFiles} cached thumbnails")
            return
        }
        val inLogDir = dir.relPath == "log" || dir.relPath.startsWith("log/")
        for (f in dir.files) classifyFile(f, dir, inLogDir, hidden)
        for (child in dir.dirs) walk(child, hidden)
    }

    private fun classifyFile(f: FileNode, dir: DirNode, inLogDir: Boolean, insideHidden: Boolean) {
        val ageDays = (now - f.mtime) / DAY_MS
        val name = f.name
        when {
            SafetyPolicy.isCredentialName(name) -> return
            name.startsWith(".trashed-") ->
                add(JunkCategory.TRASHED_MEDIA, f.path, false, f.size, f.mtime, "In the system trash for $ageDays days")
            dir.relPath.let { it == "Download" || it.startsWith("Download/") } && isAbandonedDownload(name) && ageDays >= settings.staleTempDays ->
                add(JunkCategory.STALE_DOWNLOADS, f.path, false, f.size, f.mtime, "Unfinished for $ageDays days")
            inLogDir && ageDays >= settings.oldLogDays ->
                add(JunkCategory.OLD_LOGS, f.path, false, f.size, f.mtime, "$ageDays days old")
            f.size == 0L && !f.hidden && !insideHidden && !SafetyPolicy.isMarkerFile(name) && !name.lowercase().endsWith(".lock") ->
                add(JunkCategory.ZERO_BYTE_FILES, f.path, false, 0, f.mtime, "0 bytes")
            f.extension == "apk" && dir.zone == Zone.USER_MANAGED -> installedApk(f)
        }
    }

    private fun isAbandonedDownload(name: String): Boolean =
        SafetyPolicy.isInProgressDownload(name) || name.startsWith(".pending-") || name.startsWith(".com.google.Chrome.")

    private fun installedApk(f: FileNode) {
        val info = environment.apkInfo(f.path) ?: return
        val installed = environment.installedVersionCode(info.packageName) ?: return
        if (installed >= info.versionCode) {
            val version = info.versionName?.let { " $it" } ?: ""
            add(JunkCategory.INSTALLED_APKS, f.path, false, f.size, f.mtime, "${info.packageName}$version is already installed")
        }
    }

    /** Top-most empty folders; the whole empty subtree is removed deepest-first. */
    private fun planEmptyFolders(root: DirNode) {
        val cutoff = now - DAY_MS
        fun emptyEligible(d: DirNode): Boolean =
            !d.isRoot && d.totalFiles == 0 && !d.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) && d.zone.removable &&
                !d.hidden && !(d.depth == 1 && SafetyPolicy.isKeptTopDir(d.name)) && d.mtime < cutoff

        fun visit(d: DirNode, insideHidden: Boolean) {
            val hidden = insideHidden || d.hidden
            if (!d.isRoot && (!d.zone.removable || hidden)) return
            if (emptyEligible(d) && (d.parent == null || !emptyEligible(d.parent))) {
                val all = ArrayList<DirNode>()
                d.walkDirs { all.add(it) }
                val deepestFirst = all.sortedByDescending { it.depth }.map { it.path }
                val note = if (all.size == 1) "Empty" else "Empty, with ${all.size - 1} empty sub-folders"
                add(JunkCategory.EMPTY_FOLDERS, d.path, true, 0, d.mtime, note, deepestFirst)
                return
            }
            for (c in d.dirs) visit(c, hidden)
        }
        visit(root, false)
    }

    private val packageName = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private fun planOrphanedAppFolders(root: DirNode) {
        if (!environment.canQueryPackages) return
        for (d in root.dirs) {
            if (d.name in SafetyPolicy.STANDARD_TOP_DIRS || d.hidden || !packageName.matches(d.name)) continue
            if (d.zone != Zone.OTHER_SHARED || d.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) || d.totalFiles == 0) continue
            if (environment.installedVersionCode(d.name) == null) {
                add(JunkCategory.ORPHANED_APP_FOLDERS, d.path, true, d.totalBytes, d.mtime, "${d.name} is not installed")
            }
        }
    }

    private fun add(
        category: JunkCategory,
        path: String,
        isDirectory: Boolean,
        bytes: Long,
        mtime: Long,
        note: String,
        emptyDirs: List<String> = emptyList(),
    ) {
        items.add(JunkItem("junk:${category.name}:${path.hashCode().toString(16)}:${path.length}", category, path, isDirectory, bytes, mtime, note, emptyDirs))
    }
}
