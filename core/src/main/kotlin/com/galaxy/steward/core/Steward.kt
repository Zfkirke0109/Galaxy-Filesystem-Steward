package com.galaxy.steward.core

import com.galaxy.steward.core.dedupe.DirSketch
import com.galaxy.steward.core.dedupe.DuplicateFinder
import com.galaxy.steward.core.dedupe.FolderAnalyzer
import com.galaxy.steward.core.dedupe.FolderSketch
import com.galaxy.steward.core.dedupe.HashListener
import com.galaxy.steward.core.dedupe.HashStage
import com.galaxy.steward.core.dedupe.NearCopy
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.hash.HashCache
import com.galaxy.steward.core.junk.JunkPlanner
import com.galaxy.steward.core.learn.ScanMemory
import com.galaxy.steward.core.learn.YourMoves
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.optimize.OptimizePlanner
import com.galaxy.steward.core.optimize.VolumeSpace
import com.galaxy.steward.core.organize.OrganizePlanner
import com.galaxy.steward.core.plan.DeleteDuplicateOp
import com.galaxy.steward.core.plan.Insight
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.KindStat
import com.galaxy.steward.core.plan.LargeFile
import com.galaxy.steward.core.plan.PlanHygiene
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.plan.Severity
import com.galaxy.steward.core.plan.StorageSummary
import com.galaxy.steward.core.scan.TreeScanner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.PriorityQueue

enum class ScanPhase(val label: String, val koa: String) {
    MAPPING("Mapping storage", "Koa is walking every folder"),
    JUNK("Looking for clutter", "Koa is sniffing out leftovers"),
    FINGERPRINT("Fingerprinting look-alikes", "Koa is comparing same-sized files"),
    HASHING("Verifying duplicates", "Koa is checking SHA-256 byte for byte"),
    FOLDERS("Comparing folders", "Koa is matching whole folder trees"),
    PLANNING("Planning smart layout", "Koa is deciding where things belong"),
    DONE("Done", "Koa finished the survey"),
}

data class ScanProgress(
    val phase: ScanPhase,
    val done: Long = 0,
    val total: Long = 0,
    val detail: String = "",
    val filesSeen: Long = 0,
    val bytesSeen: Long = 0,
) {
    /** 0..1 progress within the phase, or null when the total is unknown. */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    /** Rough overall progress across phases for a single progress bar. */
    val overall: Float
        get() {
            val weights = floatArrayOf(0.30f, 0.05f, 0.15f, 0.30f, 0.12f, 0.08f, 0f)
            var base = 0f
            for (i in 0 until phase.ordinal) base += weights[i]
            return (base + weights[phase.ordinal] * (fraction ?: 0.5f)).coerceIn(0f, 1f)
        }
}

/**
 * One full, read-only survey: map -> junk -> file duplicates -> folder duplicates -> organize -> optimize.
 * Nothing on disk changes here; the report is a set of proposals the user reviews before anything is applied.
 */
class Steward(
    private val rootPath: String,
    private val settings: StewardSettings,
    private val environment: DeviceEnvironment,
    private val hashCacheFile: File?,
    /** Where your files were at the last scan and how full storage was: for learning your moves and storage growth. */
    private val memory: ScanMemory? = null,
) {
    /** [previous]: the last scan's tree, so unchanged code folders needn't be listed again ([TreeScanner]). */
    suspend fun scan(space: VolumeSpace? = null, previous: StorageTree? = null, onProgress: (ScanProgress) -> Unit = {}): ScanReport {
        val started = environment.nowMillis()
        onProgress(ScanProgress(ScanPhase.MAPPING))
        val scanner = TreeScanner(rootPath, settings, previous = previous)
        val tree = scanner.scan { dirs, files, bytes, current ->
            onProgress(ScanProgress(ScanPhase.MAPPING, dirs.toLong(), 0, current, files, bytes))
        }
        val filesSeen = tree.root.totalFiles.toLong()
        val bytesSeen = tree.root.totalBytes

        onProgress(ScanProgress(ScanPhase.JUNK, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val junkPlanner = JunkPlanner(settings, environment)
        val junk = junkPlanner.plan(tree)

        val cache = HashCache(hashCacheFile)
        val hashListener = HashListener { stage, done, total, _, _ ->
            val phase = if (stage == HashStage.FINGERPRINT) ScanPhase.FINGERPRINT else ScanPhase.HASHING
            onProgress(ScanProgress(phase, done, total, "", filesSeen, bytesSeen))
        }
        onProgress(ScanProgress(ScanPhase.FINGERPRINT, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val folderListener = HashListener { _, done, total, _, _ ->
            onProgress(ScanProgress(ScanPhase.FOLDERS, done, total, "", filesSeen, bytesSeen))
        }
        // Saved however hashing ends: a scan stopped by you, or the weekly upkeep stopped by Android, keeps what it worked
        // out, and the next one starts from there instead of from nothing.
        val (duplicates, folders) = try {
            val duplicates = DuplicateFinder(settings, cache, hashListener).find(tree)
            onProgress(ScanProgress(ScanPhase.FOLDERS, filesSeen = filesSeen, bytesSeen = bytesSeen))
            duplicates to FolderAnalyzer(settings, cache, duplicates, folderListener, environment.deviceLabel).analyze(tree)
        } finally {
            cache.save()
        }
        currentCoroutineContext().ensureActive()
        onProgress(ScanProgress(ScanPhase.PLANNING, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val sketches = FolderSketch.sketches(tree.root, minBytes = settings.nearCopyMinBytes)
        val exactPaths = folders.exact.flatMap { g -> g.copies.map { it.path } } + folders.merges.flatMap { listOf(it.source, it.target) }
        val nearCopies = FolderSketch.nearCopies(sketches).filterNot { pair ->
            exactPaths.any { p -> pair.a == p || pair.b == p || pair.a.startsWith("$p/") || pair.b.startsWith("$p/") }
        }
        val (nearJunk, nearInsights) = nearCopyItems(tree, sketches, nearCopies, junk)
        currentCoroutineContext().ensureActive()

        // The copy a duplicate group keeps must not sit in a folder offered for the quarantine (an older near-copy, an old
        // run): after the duplicates went, in this run or a later one from the same scan, quarantining that folder would
        // leave no copy outside the quarantine, and none at all once it is emptied. Such a group keeps a copy outside
        // instead, where it has one; clutter that would still take a kept copy, or a folder a merge fills, is left out.
        val quarantined = (junk + nearJunk).filter { it.category != JunkCategory.EMPTY_FOLDERS }.map { it.path }
        fun quarantinedAt(path: String) = quarantined.any { path == it || path.startsWith("$it/") }
        val exactGroups = if (quarantined.isEmpty()) folders.exact else folders.exact.map { it.keepingOutside(::quarantinedAt) }

        // Copies inside a folder that is itself a removable duplicate are covered by that folder group;
        // listing them again would double-count the reclaimable space.
        val folderRemovals = exactGroups.flatMap { g -> g.removals.map { it.path + "/" } }
        val unmerged = if (folderRemovals.isEmpty()) {
            duplicates.groups
        } else {
            duplicates.groups.mapNotNull { g -> g.excluding(rootPath) { path -> folderRemovals.any { path.startsWith(it) } } }
        }
        // A copy a folder merge removes keeps, in its duplicate group too, the copy the merge keeps: each would otherwise
        // keep its own pick, and whichever ran second would find its kept copy gone and stop.
        val mergeKeeps = HashMap<String, String>()
        folders.merges.forEach { m -> m.operations.forEach { op -> if (op is DeleteDuplicateOp) mergeKeeps[op.path] = op.canonical } }
        val merged = if (mergeKeeps.isEmpty()) unmerged else unmerged.map { it.keepingWhatMergesKeep(mergeKeeps) }
        val fileGroups = if (quarantined.isEmpty()) merged else merged.map { it.keepingOutside(rootPath, ::quarantinedAt) }

        val keptPaths = fileGroups.map { it.keeper.path } + exactGroups.map { it.keeper.path } + folders.merges.map { it.target }
        fun holdsKept(item: JunkItem) = item.category != JunkCategory.EMPTY_FOLDERS &&
            keptPaths.any { it == item.path || it.startsWith(item.path + "/") }
        val (keptJunk, freeJunk) = (junk + nearJunk).partition(::holdsKept)
        val keptNotes = keptJunk.take(20).map { item ->
            Insight(
                Severity.INFO,
                "Left in place: ${item.path.substringAfterLast('/')}",
                "It holds the copy a duplicate or a folder merge keeps, so it isn't offered for the quarantine with them. " +
                    "Once the duplicates are gone, the next scan looks at it again.",
                item.path,
            )
        }

        val yours = if (settings.learnFromFolders) memory?.movesSince(tree) ?: YourMoves.NONE else YourMoves.NONE
        val organize = OrganizePlanner(settings, environment).plan(tree, yours)
        currentCoroutineContext().ensureActive()
        val optimize = OptimizePlanner(settings, environment).plan(tree, space)
        currentCoroutineContext().ensureActive()
        val hygiene = PlanHygiene(PathGuard(rootPath, settings.protectedFolders))

        // Something already proposed for dedupe, a merge or cleanup should not also be filed: drop conflicting moves, and
        // moves into a folder a merge empties.
        val claimed = HashSet<String>()
        fileGroups.forEach { g -> g.removals.forEach { claimed += it.path } }
        exactGroups.forEach { g -> g.removals.forEach { claimed += it.path } }
        folders.merges.forEach { claimed += it.source }
        freeJunk.forEach { claimed += it.path }
        val mergedAway = folders.merges.map { it.source + "/" }
        val moves = hygiene.organize(organize.moves).filterNot { move ->
            var path = move.source
            var hit = false
            while (!hit && path.length > rootPath.length) {
                hit = path in claimed
                path = path.substringBeforeLast('/')
            }
            hit || mergedAway.any { move.destination.startsWith(it) }
        }

        val growth = memory?.let { m ->
            m.rememberPlaces(tree)
            val point = ScanMemory.pointOf(tree, environment.nowMillis(), space?.freeBytes ?: -1, space?.totalBytes ?: -1)
            ScanMemory.growthInsight(m.history(), point).also { m.record(point) }
        }

        val report = ScanReport(
            tree = tree,
            startedAt = started,
            finishedAt = environment.nowMillis(),
            summary = summarize(tree),
            duplicates = fileGroups,
            folderDuplicates = exactGroups,
            folderMerges = folders.merges,
            junk = hygiene.junk(freeJunk),
            organize = moves,
            optimize = hygiene.optimize(optimize.items),
            insights = listOfNotNull(growth) + junkPlanner.notes + keptNotes + optimize.insights + nearInsights + organize.insights.take(50),
            sketches = sketches,
            reusedFolders = scanner.reusedFolders,
        )
        onProgress(ScanProgress(ScanPhase.DONE, 1, 1, "", filesSeen, bytesSeen))
        return report
    }

    /**
     * The older folder of each near-copy pair, for review, when it may go as a unit; projects, source trees and anything
     * holding keys are only named.
     */
    private fun nearCopyItems(
        tree: StorageTree,
        sketches: List<DirSketch>,
        pairs: List<NearCopy>,
        junk: List<JunkItem>,
    ): Pair<List<JunkItem>, List<Insight>> {
        val byPath = sketches.associateBy { it.path }
        val claimed = junk.map { it.path }
        val items = ArrayList<JunkItem>()
        val insights = ArrayList<Insight>()
        fun near(a: String, b: String) = a == b || a.startsWith("$b/") || b.startsWith("$a/")
        // Pairs offered so far: the older folders that go and the newer ones that stay. A folder that stays for one pair
        // never goes for another, nor with a folder around it: nested pairs (Proj/vids a newer copy of an export, Proj an
        // older copy of Proj-v2) would otherwise put every copy in the quarantine.
        val going = ArrayList<String>()
        val staying = ArrayList<String>()
        for (pair in pairs) {
            val (older, newer) = listOf(pair.a, pair.b).sortedBy { byPath[it]?.newest ?: 0L }
            if (claimed.any { older == it || older.startsWith("$it/") || it.startsWith("$older/") }) continue
            if (staying.any { near(older, it) } || going.any { near(older, it) || near(newer, it) }) continue
            val node = tree.find(older.removePrefix(tree.rootPath + "/")) ?: continue
            val rel = { p: String -> p.removePrefix(tree.rootPath + "/") }
            val movable = node.zone.removable && !node.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)
            if (movable) {
                going += older
                staying += newer
                items += JunkItem(
                    id = "junk:NEAR_COPIES:${older.hashCode().toString(16)}:${older.length}",
                    category = JunkCategory.NEAR_COPIES,
                    path = older,
                    isDirectory = true,
                    bytes = node.totalBytes,
                    mtime = byPath[older]?.newest ?: node.mtime,
                    note = "${pair.percent}% the same files as ${rel(newer)}, which is newer and stays",
                )
            } else {
                insights += Insight(
                    Severity.ADVICE,
                    "Near-copies: ${older.substringAfterLast('/')} and ${newer.substringAfterLast('/')}",
                    "${rel(older)} and ${rel(newer)} share about ${pair.percent}% of their files by name and size; " +
                        "${rel(newer)} is the newer. The steward doesn't remove projects, source trees or folders with keys; " +
                        "if one is an old copy, delete it yourself.",
                    older,
                )
            }
        }
        return items to insights
    }

    companion object {
        fun summarize(tree: StorageTree, largest: Int = 100): StorageSummary {
            val byKind = HashMap<FileKind, LongArray>()
            val byZone = HashMap<Zone, Long>()
            val top = PriorityQueue<LargeFile>(compareBy { it.size })
            tree.root.walkFiles { f ->
                val k = byKind.getOrPut(f.kind) { LongArray(2) }
                k[0]++
                k[1] += f.size
                byZone[f.zone] = (byZone[f.zone] ?: 0L) + f.size
                if (f.zone.durable && (top.size < largest || f.size > top.peek().size)) {
                    top.add(LargeFile(f.path, f.size, f.mtime, f.zone))
                    if (top.size > largest) top.poll()
                }
            }
            return StorageSummary(
                totalFiles = tree.root.totalFiles,
                totalBytes = tree.root.totalBytes,
                byKind = byKind.mapValues { KindStat(it.value[0].toInt(), it.value[1]) },
                byZone = byZone,
                largestFiles = top.sortedByDescending { it.size },
            )
        }
    }
}
