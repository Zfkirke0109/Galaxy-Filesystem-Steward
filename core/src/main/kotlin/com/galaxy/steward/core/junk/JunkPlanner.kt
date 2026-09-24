package com.galaxy.steward.core.junk

import com.galaxy.steward.core.ApkInfo
import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.DeviceEnvironment
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.ExtractedCopy
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
    private val recentCutoff = now - settings.recentFileGuardMinutes * 60_000L
    private val items = ArrayList<JunkItem>()
    private val installers = ArrayList<Pair<FileNode, ApkInfo>>()
    private var archiveBudget = ARCHIVE_BUDGET

    fun plan(tree: StorageTree): List<JunkItem> {
        items.clear()
        installers.clear()
        archiveBudget = ARCHIVE_BUDGET
        walk(tree.root, insideHidden = false)
        planOlderInstallers()
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
        recycleBinOwner(dir)?.let { owner ->
            recycleBin(dir, owner)
            return
        }
        val inLogDir = dir.relPath == "log" || dir.relPath.startsWith("log/")
        for (f in dir.files) classifyFile(f, dir, inLogDir, hidden)
        val oldRuns = if (hidden) emptySet() else oldRuns(dir)
        for (child in dir.dirs) if (child !in oldRuns) walk(child, hidden)
    }

    /**
     * Run folders a tool writes each time (`Ultimate-Cleanup/runs/20260903-122520-14326`, seen on a phone at 1 GiB for
     * three runs): three or more siblings named by date and time. The newest, and any from the last two weeks, stay.
     */
    private fun oldRuns(parent: DirNode): Set<DirNode> {
        if (parent.zone == Zone.MEDIA_LIBRARY || parent.insideFlagged(NodeFlags.CODE_TREE or NodeFlags.PROJECT_ROOT)) return emptySet()
        val runs = parent.dirs.filter { !it.hidden && RUN_NAME.any { r -> r.matches(it.name) } && it.totalFiles > 0 }
        if (runs.size < 3) return emptySet()
        val newestOf = runs.associateWith { d -> var n = d.mtime; d.walkFiles { if (it.mtime > n) n = it.mtime }; n }
        val keep = newestOf.maxByOrNull { it.value }!!.key
        val cutoff = now - RUN_KEEP_DAYS * DAY_MS
        val old = runs.filter { it !== keep && newestOf.getValue(it) < cutoff && !it.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) }
        for (run in old) {
            add(JunkCategory.OLD_RUNS, run.path, true, run.totalBytes, newestOf.getValue(run), "${run.totalFiles} files; ${keep.name} is newer and stays")
        }
        return old.toSet()
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
            f.extension == "hprof" && f.size > 0 && ageDays >= HEAP_DUMP_DAYS ->
                add(JunkCategory.HEAP_DUMPS, f.path, false, f.size, f.mtime, "Heap dump, $ageDays days old")
            // Anywhere you can reach, not only your own folders: MT Manager keeps the APKs it extracts in MT2/apks.
            f.extension == "apk" && f.size > 0 -> installedApk(f)
            dir.zone == Zone.USER_MANAGED && ExtractedArchives.isSupported(name) && !f.hidden && !insideHidden && f.size > 0 &&
                f.mtime < recentCutoff -> extractedArchive(f)
        }
    }

    /**
     * A zip unpacked next to itself, with every file still there at the same size (checked again, by CRC, before it
     * moves). Only in your own folders: an app may keep a zip and its unpacked copy side by side on purpose.
     */
    private fun extractedArchive(f: FileNode) {
        if (f.dir.dirs.isEmpty()) return
        // Tar has no index: listing one reads all of it, so only so much is read per scan.
        if (!f.name.lowercase().endsWith(".zip")) {
            val stem = ExtractedArchives.stem(f.name)
            if (f.dir.dirs.none { it.name == stem } || f.size > archiveBudget) return
            archiveBudget -= f.size
        }
        val entries = ExtractedArchives.entries(f.path) ?: return
        val copy = ExtractedArchives.extractedCopy(f, entries) ?: return
        add(
            JunkCategory.EXTRACTED_ARCHIVES, f.path, false, f.size, f.mtime,
            "All ${entries.size} files are unpacked in ${copy.folder.substringAfterLast('/')}",
            extracted = copy,
        )
    }

    /** File managers that keep deleted files in shared storage, by bin folder. */
    private fun recycleBinOwner(dir: DirNode): String? = when {
        dir.relPath == "MT2/.recycle" -> "MT Manager"
        dir.depth == 1 && dir.name.startsWith(".Trash-") -> "a Linux-style trash"
        else -> null
    }

    /**
     * Each deleted item in the bin on its own, so one that holds keys or links stays while the rest go. They go to the
     * quarantine, so they can still come back until it is emptied.
     */
    private fun recycleBin(bin: DirNode, owner: String) {
        for (item in bin.dirs) {
            if (item.totalFiles == 0 || item.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)) continue
            val shown = item.dirs.singleOrNull()?.takeIf { item.files.isEmpty() }?.name ?: item.name
            add(JunkCategory.RECYCLE_BINS, item.path, true, item.totalBytes, item.mtime, "$shown, deleted in $owner")
        }
        for (f in bin.files) {
            if (SafetyPolicy.isCredentialName(f.name)) continue
            add(JunkCategory.RECYCLE_BINS, f.path, false, f.size, f.mtime, "Deleted in $owner")
        }
    }

    private fun isAbandonedDownload(name: String): Boolean =
        SafetyPolicy.isInProgressDownload(name) || name.startsWith(".pending-") || name.startsWith(".com.google.Chrome.")

    private fun installedApk(f: FileNode) {
        val info = environment.apkInfo(f.path) ?: return
        val installed = environment.installedVersionCode(info.packageName)
        if (installed != null && installed >= info.versionCode) {
            val version = info.versionName?.let { " $it" } ?: ""
            add(JunkCategory.INSTALLED_APKS, f.path, false, f.size, f.mtime, "${info.packageName}$version is already installed")
        } else {
            installers += f to info
        }
    }

    /** Of several installers for one app that isn't installed at their version, all but the newest. */
    private fun planOlderInstallers() {
        for ((pkg, apks) in installers.groupBy { it.second.packageName }) {
            if (apks.size < 2) continue
            val newest = apks.maxWithOrNull(compareBy<Pair<FileNode, ApkInfo>> { it.second.versionCode }.thenBy { it.first.mtime })!!
            val shown = newest.second.versionName ?: newest.second.versionCode.toString()
            for ((f, info) in apks) {
                if (f === newest.first) continue
                val version = info.versionName ?: info.versionCode.toString()
                add(JunkCategory.OLD_INSTALLERS, f.path, false, f.size, f.mtime, "$pkg $version; $shown is in ${newest.first.dir.name}")
            }
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

    companion object {
        /** Heap dumps older than this are clutter: the leak they show was either fixed or shows up again. */
        const val HEAP_DUMP_DAYS = 3

        /** Run folders written in the last two weeks stay, whatever their order. */
        const val RUN_KEEP_DAYS = 14

        /** Bytes of tar archives read per scan to see whether they were unpacked. */
        const val ARCHIVE_BUDGET = 1024L * 1024 * 1024

        /** `20260903-122520-14326`, `run_20260903T1225`, `2026-09-03_12-25-20`, `backup-2026-09-03 12.25`. */
        val RUN_NAME = listOf(
            Regex("""^(?:[A-Za-z]+[-_])?\d{8}[-_T]?\d{4,6}(?:[-_.].*)?$"""),
            Regex("""^(?:[A-Za-z]+[-_])?\d{4}-\d{2}-\d{2}[_T ]\d{2}[-:._h]?\d{2}(?:[-:._m]?\d{2}s?)?(?:[-_.].*)?$"""),
        )
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
        extracted: ExtractedCopy? = null,
    ) {
        items.add(
            JunkItem("junk:${category.name}:${path.hashCode().toString(16)}:${path.length}", category, path, isDirectory, bytes, mtime, note, emptyDirs, extracted),
        )
    }
}
