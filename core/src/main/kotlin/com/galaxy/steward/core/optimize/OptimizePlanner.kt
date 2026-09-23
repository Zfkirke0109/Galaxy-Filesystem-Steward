package com.galaxy.steward.core.optimize

import com.galaxy.steward.core.DeviceEnvironment
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.Insight
import com.galaxy.steward.core.plan.MoveDirOp
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.Operation
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.RemoveEmptyDirOp
import com.galaxy.steward.core.plan.Severity
import java.time.Instant
import java.time.ZoneId

class OptimizePlan(val items: List<OptimizeItem>, val insights: List<Insight>)

/** Free/total bytes of the storage volume, when the platform can tell us. */
data class VolumeSpace(val freeBytes: Long, val totalBytes: Long)

/**
 * Layout optimisations that make storage faster to browse and index: collapsing redundant nested folders
 * (typical of extracted archives) and splitting huge flat folders into year buckets, plus report-only
 * health insights such as low free space, which slows flash storage down.
 */
class OptimizePlanner(
    private val settings: StewardSettings,
    private val environment: DeviceEnvironment,
) {
    private val recentCutoff = environment.nowMillis() - settings.recentFileGuardMinutes * 60_000L

    fun plan(tree: StorageTree, space: VolumeSpace?): OptimizePlan {
        val items = ArrayList<OptimizeItem>()
        val insights = ArrayList<Insight>()
        val flatReportOnly = ArrayList<DirNode>()
        var deepest: FileNode? = null
        var deepCount = 0

        tree.root.walkDirs { dir ->
            if (!dir.zone.durable) return@walkDirs
            flattenCandidate(dir)?.let(items::add)
            if (dir.files.size > settings.flatDirThreshold) {
                val bucket = bucketCandidate(dir)
                if (bucket != null) items.add(bucket) else if (!dir.hidden) flatReportOnly.add(dir)
            }
            for (f in dir.files) {
                if (f.depth > 12 || f.path.length > 240) {
                    deepCount++
                    if (deepest == null || f.depth > deepest!!.depth) deepest = f
                }
            }
        }

        space?.let { insights += spaceInsight(it) }
        for (dir in flatReportOnly.sortedByDescending { it.files.size }.take(5)) {
            insights += Insight(
                Severity.INFO,
                "${dir.files.size} files in one folder",
                "${dir.relPath} is very flat. Apps that own it expect this layout, so the steward leaves it alone, " +
                    "but galleries and file managers list it more slowly.",
                dir.path,
            )
        }
        deepest?.let {
            insights += Insight(
                Severity.ADVICE,
                "$deepCount deeply nested file" + if (deepCount == 1) "" else "s",
                "Very deep or long paths are slow to browse and break some apps. Deepest: ${it.relPath}",
                it.path,
            )
        }
        tree.find("Download")?.let { download ->
            val loose = download.files.count { !it.hidden }
            if (loose >= 200) {
                insights += Insight(Severity.ADVICE, "$loose loose files in Download", "Smart Organize can file them into meaningful folders.", download.path)
            }
        }
        tree.root.dirs.filter { it.hidden && it.zone == Zone.OTHER_SHARED && it.totalBytes >= 100 * MIB }
            .sortedByDescending { it.totalBytes }.take(3)
            .forEach {
                insights += Insight(Severity.INFO, "Hidden folder ${it.name} uses ${it.totalBytes.humanBytes()}", "Hidden app data in shared storage. Review it in the storage map.", it.path)
            }
        return OptimizePlan(items, insights)
    }

    private fun spaceInsight(space: VolumeSpace): List<Insight> {
        if (space.totalBytes <= 0) return emptyList()
        val freePct = space.freeBytes * 100 / space.totalBytes
        return when {
            freePct < 10 -> listOf(
                Insight(
                    Severity.WARNING,
                    "Only $freePct% free (${space.freeBytes.humanBytes()})",
                    "Flash storage writes slow down and wear faster when nearly full. Aim to keep 10-15% free.",
                ),
            )
            freePct < 20 -> listOf(
                Insight(Severity.ADVICE, "$freePct% free (${space.freeBytes.humanBytes()})", "Keeping at least 10-15% free keeps writes and app installs fast."),
            )
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ redundant wrappers

    private val extractNames = setOf("files", "new folder", "untitled folder", "folder", "extracted", "contents", "content", "output", "unzipped")
    private val suffix = Regex("""(?i)(\s?\(\d+\)|[-_ ](main|master|extracted|unzipped|copy))$""")

    private fun baseName(name: String) = name.replace(suffix, "").trim().lowercase()

    private fun flattenCandidate(wrapper: DirNode): OptimizeItem? {
        if (wrapper.depth < 2 || wrapper.zone != Zone.USER_MANAGED || wrapper.hidden) return null
        if (wrapper.files.isNotEmpty() || wrapper.dirs.size != 1) return null
        val inner = wrapper.dirs[0]
        if (inner.zone != Zone.USER_MANAGED || inner.hidden) return null
        if (wrapper.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)) return null
        val sameName = baseName(inner.name) == baseName(wrapper.name)
        if (!sameName && inner.name.trim().lowercase() !in extractNames) return null
        if (inner.dirs.any { it.name == inner.name } || inner.files.any { it.name == inner.name }) return null
        if (inner.dirs.isEmpty() && inner.files.isEmpty()) return null
        var newest = inner.mtime
        inner.walkFiles { if (it.mtime > newest) newest = it.mtime }
        if (newest > recentCutoff) return null

        val ops = ArrayList<Operation>()
        for (d in inner.dirs) ops += MoveDirOp(d.path, "${wrapper.path}/${d.name}", allowRename = true)
        for (f in inner.files) ops += MoveFileOp(f.path, "${wrapper.path}/${f.name}", f.size, f.mtime)
        ops += RemoveEmptyDirOp(inner.path)
        return OptimizeItem(
            id = "flatten:${wrapper.path.hashCode().toString(16)}:${wrapper.path.length}",
            kind = OptimizeKind.FLATTEN_WRAPPER,
            path = wrapper.path,
            title = "${wrapper.name}/${inner.name}",
            detail = "Move ${inner.files.size + inner.dirs.size} item(s) up one level and remove the redundant \"${inner.name}\" folder.",
            fileCount = inner.totalFiles,
            defaultSelected = true,
            operations = ops,
        )
    }

    // ------------------------------------------------------------------ oversized flat folders

    private fun bucketCandidate(dir: DirNode): OptimizeItem? {
        if (!dir.managed || dir.depth < 2 || dir.hidden) return null
        if (dir.zone != Zone.USER_MANAGED && dir.zone != Zone.MEDIA_LIBRARY) return null
        val movable = dir.files.filter {
            !it.hidden && it.mtime <= recentCutoff && !SafetyPolicy.isCredentialName(it.name) &&
                !SafetyPolicy.isInProgressDownload(it.name) && !SafetyPolicy.isMarkerFile(it.name)
        }
        if (movable.size <= settings.flatDirThreshold / 2) return null
        val zone = ZoneId.systemDefault()
        val years = movable.groupingBy { Instant.ofEpochMilli(it.mtime).atZone(zone).year }.eachCount()
        val monthly = years.values.any { it > settings.flatDirThreshold }
        fun bucketOf(f: FileNode): String {
            val date = Instant.ofEpochMilli(f.mtime).atZone(zone)
            return if (monthly) "%04d-%02d".format(date.year, date.monthValue) else date.year.toString()
        }
        val ops = movable.map { MoveFileOp(it.path, "${dir.path}/${bucketOf(it)}/${it.name}", it.size, it.mtime) }
        val buckets = movable.map(::bucketOf).distinct().size
        return OptimizeItem(
            id = "bucket:${dir.path.hashCode().toString(16)}:${dir.path.length}",
            kind = OptimizeKind.BUCKET_FLAT_DIR,
            path = dir.path,
            title = dir.relPath,
            detail = "Sort ${movable.size} files into $buckets ${if (monthly) "month" else "year"} folders for faster browsing and indexing.",
            fileCount = movable.size,
            defaultSelected = dir.zone == Zone.MEDIA_LIBRARY,
            operations = ops,
        )
    }
}
