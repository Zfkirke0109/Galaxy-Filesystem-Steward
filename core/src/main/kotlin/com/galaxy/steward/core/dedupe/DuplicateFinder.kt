package com.galaxy.steward.core.dedupe

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.hash.FileIdentity
import com.galaxy.steward.core.hash.HashCache
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.DuplicateCopy
import com.galaxy.steward.core.plan.DuplicateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

fun interface HashListener {
    fun onProgress(stage: HashStage, done: Long, total: Long, bytesDone: Long, bytesTotal: Long)
}

enum class HashStage { FINGERPRINT, FULL_HASH }

class FileDuplicateResult(
    val groups: List<DuplicateGroup>,
    /** Full hashes computed during detection, reused by the folder analyzer. */
    val fullHashes: Map<FileNode, String>,
)

/**
 * Exact duplicate detection: size buckets -> quick head/tail fingerprints -> full SHA-256, hashed in parallel
 * with a persistent cache. Only durable, user-reachable storage participates; app-owned data, the quarantine,
 * Git internals and credential-like files are never candidates.
 */
class DuplicateFinder(
    private val settings: StewardSettings,
    private val cache: HashCache,
    private val listener: HashListener? = null,
) {
    suspend fun find(tree: StorageTree): FileDuplicateResult {
        val candidates = candidates(tree)
        val sizeBuckets = candidates.groupBy { it.size }.values.filter { it.size > 1 }

        // Stage 1: quick fingerprints for everything sharing a size.
        val stage1 = sizeBuckets.flatten()
        val quick = parallelHash(stage1, HashStage.FINGERPRINT) { f ->
            cache.quick(f.path, f.size, f.mtime)
                ?: Hashing.quickFingerprint(f.path, f.size).also { cache.putQuick(f.path, f.size, f.mtime, it) }
        }
        val quickGroups = stage1.filter { it in quick }
            .groupBy { it.size to quick.getValue(it) }
            .values.filter { it.size > 1 }

        // Stage 2: full SHA-256 only for files whose fingerprints still collide.
        val stage2 = quickGroups.flatten()
        val full = parallelHash(stage2, HashStage.FULL_HASH) { f -> cache.fullHash(f.path, f.size, f.mtime) }
        val groups = stage2.filter { it in full }
            .groupBy { it.size to full.getValue(it) }
            .values.filter { it.size > 1 }
            .mapNotNull { members -> buildGroup(full.getValue(members.first()), collapseHardLinks(members)) }
            .sortedByDescending { it.reclaimBytes }
        return FileDuplicateResult(groups, full)
    }

    private fun candidates(tree: StorageTree): List<FileNode> {
        val out = ArrayList<FileNode>()
        walkEligible(tree.root, insideHidden = false) { dir ->
            for (f in dir.files) {
                if (f.size < settings.minDuplicateBytes || f.size <= 0) continue
                if (!settings.includeHiddenInDuplicates && f.hidden) continue
                if (SafetyPolicy.isCredentialName(f.name) || SafetyPolicy.isInProgressDownload(f.name)) continue
                out.add(f)
            }
        }
        return out
    }

    private fun walkEligible(dir: DirNode, insideHidden: Boolean, visit: (DirNode) -> Unit) {
        val hidden = insideHidden || dir.hidden
        if (!dir.zone.durable || dir.hasFlag(NodeFlags.GIT_DIR)) return
        if (hidden && !settings.includeHiddenInDuplicates) return
        visit(dir)
        for (child in dir.dirs) walkEligible(child, hidden, visit)
    }

    private suspend fun parallelHash(
        files: List<FileNode>,
        stage: HashStage,
        hash: (FileNode) -> String,
    ): Map<FileNode, String> {
        if (files.isEmpty()) return emptyMap()
        val total = files.size.toLong()
        val bytesTotal = files.sumOf { if (stage == HashStage.FINGERPRINT) minOf(it.size, Hashing.QUICK_WINDOW * 2L) else it.size }
        val done = AtomicLong()
        val bytesDone = AtomicLong()
        val workers = settings.effectiveHashWorkers()

        @OptIn(ExperimentalCoroutinesApi::class)
        val dispatcher = Dispatchers.IO.limitedParallelism(workers)
        val chunkSize = maxOf(1, files.size / (workers * 16))
        val results = coroutineScope {
            files.chunked(chunkSize).map { chunk ->
                async(dispatcher) {
                    chunk.mapNotNull { f ->
                        ensureActive()
                        val result = try {
                            f to hash(f)
                        } catch (_: IOException) {
                            null
                        } catch (_: SecurityException) {
                            null
                        }
                        val n = done.incrementAndGet()
                        val b = bytesDone.addAndGet(if (stage == HashStage.FINGERPRINT) minOf(f.size, Hashing.QUICK_WINDOW * 2L) else f.size)
                        if (n % 64 == 0L || n == total) listener?.onProgress(stage, n, total, b, bytesTotal)
                        result
                    }
                }
            }.awaitAll().flatten()
        }
        return results.toMap()
    }

    /** Hard links share storage; deleting one frees nothing, so they collapse into a single member. */
    private fun collapseHardLinks(members: List<FileNode>): List<FileNode> {
        val seen = HashSet<Any>()
        return members.filter { f ->
            val key = FileIdentity.of(f.path)?.key ?: return@filter true
            seen.add(key)
        }
    }

    private fun buildGroup(sha: String, members: List<FileNode>): DuplicateGroup? {
        if (members.size < 2) return null
        val ranked = members.sortedWith(KeeperRanking.fileComparator)
        val keeper = ranked.first()
        val copies = ranked.map { DuplicateCopy(it.path, it.size, it.mtime, it.zone) }
        return DuplicateGroup(
            id = "dup:${sha.take(16)}:${keeper.size}",
            sha256 = sha,
            size = keeper.size,
            copies = copies,
            keeperIndex = 0,
            keepReason = KeeperRanking.reason(keeper, ranked[1]),
        )
    }
}

/**
 * Chooses which copy survives. Protected and path-sensitive copies always win, then the media library, then
 * organized Documents over the Download inbox; within a zone an original-looking name beats "(1)" / "copy",
 * shallower paths beat deeper ones and the oldest copy is treated as the original.
 */
object KeeperRanking {
    fun zoneRank(zone: Zone, relPath: String): Int = when (zone) {
        Zone.USER_PROTECTED -> 12
        Zone.PATH_SENSITIVE -> 10
        // Camera originals beat copies elsewhere in the media library.
        Zone.MEDIA_LIBRARY -> if (relPath.startsWith("DCIM/")) 9 else 8
        Zone.USER_MANAGED -> if (relPath.startsWith("Documents/")) 6 else 4
        Zone.OTHER_SHARED -> 2
        Zone.APP_OWNED, Zone.STEWARD -> 0
    }

    val fileComparator: Comparator<FileNode> = compareByDescending<FileNode> { zoneRank(it.zone, it.relPath) }
        .thenBy { if (SafetyPolicy.hasCopyMarker(it.name)) 1 else 0 }
        .thenBy { it.depth }
        .thenBy { it.mtime }
        .thenBy { it.path }

    /** Same ordering as [fileComparator] for already-built duplicate copies. */
    fun copyComparator(rootPath: String): Comparator<DuplicateCopy> =
        compareByDescending<DuplicateCopy> { zoneRank(it.zone, it.path.removePrefix("$rootPath/")) }
            .thenBy { if (SafetyPolicy.hasCopyMarker(it.name)) 1 else 0 }
            .thenBy { it.path.count { c -> c == '/' } }
            .thenBy { it.mtime }
            .thenBy { it.path }

    val dirComparator: Comparator<DirNode> = compareByDescending<DirNode> { zoneRank(it.zone, it.relPath + "/") }
        .thenBy { if (SafetyPolicy.hasCopyMarker(it.name)) 1 else 0 }
        .thenBy { it.depth }
        .thenBy { it.mtime }
        .thenBy { it.path }

    fun reason(keeper: FileNode, runnerUp: FileNode): String = reason(
        keeper.zone, keeper.relPath, keeper.name, keeper.depth, keeper.mtime,
        runnerUp.zone, runnerUp.relPath, runnerUp.name, runnerUp.depth, runnerUp.mtime,
    )

    fun reason(keeper: DirNode, runnerUp: DirNode): String = reason(
        keeper.zone, keeper.relPath + "/", keeper.name, keeper.depth, keeper.mtime,
        runnerUp.zone, runnerUp.relPath + "/", runnerUp.name, runnerUp.depth, runnerUp.mtime,
    )

    private fun reason(
        zone: Zone, rel: String, name: String, depth: Int, mtime: Long,
        otherZone: Zone, otherRel: String, otherName: String, otherDepth: Int, otherMtime: Long,
    ): String {
        val rank = zoneRank(zone, rel)
        val otherRank = zoneRank(otherZone, otherRel)
        if (rank != otherRank) {
            return when (zone) {
                Zone.USER_PROTECTED -> "Kept: in a folder you pinned"
                Zone.PATH_SENSITIVE -> "Kept: part of a project or repository"
                Zone.MEDIA_LIBRARY -> "Kept: in your media library (${rel.substringBefore('/')})"
                Zone.USER_MANAGED -> if (rel.startsWith("Documents/")) "Kept: already filed in Documents" else "Kept: in Download"
                else -> "Kept: most durable location"
            }
        }
        val marker = SafetyPolicy.hasCopyMarker(name)
        if (marker != SafetyPolicy.hasCopyMarker(otherName)) return "Kept: original name - the others look like copies"
        if (depth != otherDepth) return "Kept: shortest, most accessible path"
        if (mtime != otherMtime) return "Kept: oldest copy (likely the original)"
        return "Kept: first by path"
    }
}
