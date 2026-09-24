package com.galaxy.steward.core

import com.galaxy.steward.core.dedupe.DuplicateFinder
import com.galaxy.steward.core.dedupe.FolderAnalyzer
import com.galaxy.steward.core.dedupe.HashListener
import com.galaxy.steward.core.dedupe.HashStage
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.hash.HashCache
import com.galaxy.steward.core.junk.JunkPlanner
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.optimize.OptimizePlanner
import com.galaxy.steward.core.optimize.VolumeSpace
import com.galaxy.steward.core.organize.OrganizePlanner
import com.galaxy.steward.core.plan.KindStat
import com.galaxy.steward.core.plan.LargeFile
import com.galaxy.steward.core.plan.PlanHygiene
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.plan.StorageSummary
import com.galaxy.steward.core.scan.TreeScanner
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
) {
    suspend fun scan(space: VolumeSpace? = null, onProgress: (ScanProgress) -> Unit = {}): ScanReport {
        val started = environment.nowMillis()
        onProgress(ScanProgress(ScanPhase.MAPPING))
        val tree = TreeScanner(rootPath, settings).scan { dirs, files, bytes, current ->
            onProgress(ScanProgress(ScanPhase.MAPPING, dirs.toLong(), 0, current, files, bytes))
        }
        val filesSeen = tree.root.totalFiles.toLong()
        val bytesSeen = tree.root.totalBytes

        onProgress(ScanProgress(ScanPhase.JUNK, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val junk = JunkPlanner(settings, environment).plan(tree)

        val cache = HashCache(hashCacheFile)
        val hashListener = HashListener { stage, done, total, _, _ ->
            val phase = if (stage == HashStage.FINGERPRINT) ScanPhase.FINGERPRINT else ScanPhase.HASHING
            onProgress(ScanProgress(phase, done, total, "", filesSeen, bytesSeen))
        }
        onProgress(ScanProgress(ScanPhase.FINGERPRINT, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val duplicates = DuplicateFinder(settings, cache, hashListener).find(tree)

        onProgress(ScanProgress(ScanPhase.FOLDERS, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val folderListener = HashListener { _, done, total, _, _ ->
            onProgress(ScanProgress(ScanPhase.FOLDERS, done, total, "", filesSeen, bytesSeen))
        }
        val folders = FolderAnalyzer(settings, cache, duplicates, folderListener).analyze(tree)
        cache.save()
        // Copies inside a folder that is itself a removable duplicate are covered by that folder group;
        // listing them again would double-count the reclaimable space.
        val folderRemovals = folders.exact.flatMap { g -> g.removals.map { it.path + "/" } }
        val fileGroups = if (folderRemovals.isEmpty()) {
            duplicates.groups
        } else {
            duplicates.groups.mapNotNull { g -> g.excluding(rootPath) { path -> folderRemovals.any { path.startsWith(it) } } }
        }

        onProgress(ScanProgress(ScanPhase.PLANNING, filesSeen = filesSeen, bytesSeen = bytesSeen))
        val organize = OrganizePlanner(settings, environment).plan(tree)
        val optimize = OptimizePlanner(settings, environment).plan(tree, space)
        val hygiene = PlanHygiene(PathGuard(rootPath, settings.protectedFolders))

        // Something already proposed for dedupe or cleanup should not also be filed: drop conflicting moves.
        val claimed = HashSet<String>()
        fileGroups.forEach { g -> g.removals.forEach { claimed += it.path } }
        folders.exact.forEach { g -> g.removals.forEach { claimed += it.path } }
        junk.forEach { claimed += it.path }
        val moves = hygiene.organize(organize.moves).filterNot { move ->
            var path = move.source
            var hit = false
            while (!hit && path.length > rootPath.length) {
                hit = path in claimed
                path = path.substringBeforeLast('/')
            }
            hit
        }

        val report = ScanReport(
            tree = tree,
            startedAt = started,
            finishedAt = environment.nowMillis(),
            summary = summarize(tree),
            duplicates = fileGroups,
            folderDuplicates = folders.exact,
            folderMerges = folders.merges,
            junk = hygiene.junk(junk),
            organize = moves,
            optimize = hygiene.optimize(optimize.items),
            insights = optimize.insights + organize.insights.take(50),
        )
        onProgress(ScanProgress(ScanPhase.DONE, 1, 1, "", filesSeen, bytesSeen))
        return report
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
