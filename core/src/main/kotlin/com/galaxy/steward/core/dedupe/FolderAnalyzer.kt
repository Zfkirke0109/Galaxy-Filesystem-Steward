package com.galaxy.steward.core.dedupe

import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.hash.HashCache
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.DeleteDuplicateOp
import com.galaxy.steward.core.plan.FolderCopy
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.FolderEntry
import com.galaxy.steward.core.plan.FolderMerge
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.Operation
import com.galaxy.steward.core.plan.RemoveEmptyDirOp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.MessageDigest

class FolderAnalysis(val exact: List<FolderDuplicateGroup>, val merges: List<FolderMerge>)

/**
 * Folder-level deduplication, following the v18 duplicate-folder engine:
 *  1. exact duplicate trees (same names, sizes and SHA-256 content), reporting only the top-most pair;
 *  2. partial overlaps - folders that share most of their bytes - offered as a hash-verified merge.
 */
class FolderAnalyzer(
    private val settings: StewardSettings,
    private val cache: HashCache,
    private val fileDuplicates: FileDuplicateResult,
    private val listener: HashListener? = null,
) {
    private val knownHashes = HashMap<FileNode, String>(fileDuplicates.fullHashes)

    suspend fun analyze(tree: StorageTree): FolderAnalysis {
        val exact = exactDuplicates(tree)
        val covered = HashSet<DirNode>()
        exact.forEach { group -> group.copies.forEach { copy -> tree.findAbsolute(copy.path)?.let { covered.add(it) } } }
        val merges = overlaps(covered)
        return FolderAnalysis(exact, merges)
    }

    // ---------------------------------------------------------------- exact duplicate trees

    private suspend fun exactDuplicates(tree: StorageTree): List<FolderDuplicateGroup> {
        val signatures = HashMap<DirNode, String>()
        structuralSignature(tree.root, insideHidden = false, signatures)

        val candidates = signatures.entries
            .filter { (dir, _) -> eligibleFolder(dir) }
            .groupBy({ it.value }, { it.key })
            .values.filter { it.size > 1 }
            .sortedBy { members -> members.minOf { it.depth } }

        val total = candidates.sumOf { it.size }.toLong()
        var done = 0L
        val covered = HashSet<DirNode>()
        val out = ArrayList<FolderDuplicateGroup>()
        for (members in candidates) {
            currentCoroutineContext().ensureActive()
            val open = members.filter { m -> covered.none { it === m || it.isAncestorOf(m) } }
            done += members.size
            listener?.onProgress(HashStage.FULL_HASH, done, total, 0, 0)
            if (open.size < 2) continue
            val byContent = open.mapNotNull { dir -> contentHash(dir)?.let { dir to it } }.groupBy({ it.second }, { it.first })
            for ((hash, same) in byContent) {
                if (same.size < 2) continue
                val ranked = same.sortedWith(KeeperRanking.dirComparator)
                val keeper = ranked.first()
                out.add(
                    FolderDuplicateGroup(
                        id = "folder:${hash.take(16)}",
                        treeHash = hash,
                        bytes = keeper.totalBytes,
                        fileCount = keeper.totalFiles,
                        copies = ranked.map { FolderCopy(it.path, it.zone) },
                        keeperIndex = 0,
                        keepReason = KeeperRanking.reason(keeper, ranked[1]),
                        entries = entriesOf(keeper),
                        subdirs = subdirsDeepestFirst(keeper),
                    ),
                )
                covered.addAll(same)
            }
        }
        return out.sortedByDescending { it.reclaimBytes }
    }

    private fun eligibleFolder(dir: DirNode): Boolean =
        !dir.isRoot && dir.depth >= 2 && dir.zone.durable && dir.zone != Zone.PATH_SENSITIVE &&
            dir.totalFiles >= 2 && dir.totalBytes >= settings.minDuplicateFolderBytes

    /** Names + sizes signature, computed bottom-up. Folders containing anything unsafe get no signature. */
    private fun structuralSignature(dir: DirNode, insideHidden: Boolean, out: MutableMap<DirNode, String>): String? {
        val hidden = insideHidden || dir.hidden
        val childSigs = dir.dirs.map { it to structuralSignature(it, hidden, out) }
        if (!dir.zone.durable || dir.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)) return null
        if (hidden && !settings.includeHiddenInDuplicates) return null
        if (childSigs.any { it.second == null }) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (f in dir.files) digest.update("F\u0000${f.name}\u0000${f.size}\n".toByteArray())
        for ((child, sig) in childSigs) digest.update("D\u0000${child.name}\u0000$sig\n".toByteArray())
        val sig = digest.digest().joinToString("") { "%02x".format(it) }
        out[dir] = sig
        return sig
    }

    private fun fullHash(f: FileNode): String? = knownHashes[f] ?: try {
        cache.fullHash(f.path, f.size, f.mtime).also { knownHashes[f] = it }
    } catch (_: IOException) {
        null
    }

    private suspend fun contentHash(dir: DirNode): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = ArrayList<Pair<String, FileNode>>()
        collect(dir, "", files)
        for ((rel, f) in files.sortedBy { it.first }) {
            currentCoroutineContext().ensureActive()
            val sha = fullHash(f) ?: return null
            digest.update("$rel\u0000${f.size}\u0000$sha\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun collect(dir: DirNode, prefix: String, out: MutableList<Pair<String, FileNode>>) {
        for (f in dir.files) out.add((prefix + f.name) to f)
        for (d in dir.dirs) collect(d, prefix + d.name + "/", out)
    }

    private fun entriesOf(dir: DirNode): List<FolderEntry> {
        val files = ArrayList<Pair<String, FileNode>>()
        collect(dir, "", files)
        return files.map { (rel, f) -> FolderEntry(rel, f.size, knownHashes.getValue(f)) }
    }

    private fun subdirsDeepestFirst(dir: DirNode): List<String> {
        val out = ArrayList<Pair<String, Int>>()
        fun walk(d: DirNode, prefix: String) {
            for (c in d.dirs) {
                val rel = prefix + c.name
                out.add(rel to c.depth)
                walk(c, "$rel/")
            }
        }
        walk(dir, "")
        return out.sortedByDescending { it.second }.map { it.first }
    }

    // ---------------------------------------------------------------- partial overlaps

    private suspend fun overlaps(covered: Set<DirNode>): List<FolderMerge> {
        data class Pair2(val a: DirNode, val b: DirNode)

        val common = HashMap<Pair2, Long>()
        // Resolve each duplicate group's parent directories through the hashed file nodes (one map pass).
        val byHash = HashMap<String, MutableList<FileNode>>()
        for ((node, sha) in fileDuplicates.fullHashes) byHash.getOrPut(sha) { ArrayList() }.add(node)
        for (group in fileDuplicates.groups) {
            val nodes = byHash[group.sha256]?.filter { it.size == group.size } ?: continue
            val parents = nodes.map { it.dir }.distinct().filter { mergeable(it, covered) }.take(10)
            for (i in parents.indices) for (j in i + 1 until parents.size) {
                val a = parents[i]
                val b = parents[j]
                if (a.isAncestorOf(b) || b.isAncestorOf(a)) continue
                val key = if (a.path < b.path) Pair2(a, b) else Pair2(b, a)
                common[key] = (common[key] ?: 0L) + group.size
            }
        }
        val threshold = maxOf(5 * MIB, settings.minDuplicateFolderBytes)
        val shortlist = common.entries
            .filter { it.value >= threshold }
            .map { (pair, bytes) ->
                val ranked = listOf(pair.a, pair.b).sortedWith(KeeperRanking.dirComparator)
                Triple(ranked[0], ranked[1], bytes)
            }
            .filter { (_, source, bytes) -> bytes * 2 >= source.directBytes }
            .sortedByDescending { it.third }
            .take(25)

        val out = ArrayList<FolderMerge>()
        for ((target, source, _) in shortlist) {
            currentCoroutineContext().ensureActive()
            planMerge(target, source)?.let(out::add)
        }
        return out.sortedByDescending { it.commonBytes }
    }

    private fun mergeable(dir: DirNode, covered: Set<DirNode>): Boolean {
        if (dir.depth < 2) return false
        if (dir.zone != Zone.USER_MANAGED && dir.zone != Zone.MEDIA_LIBRARY) return false
        if (dir.relPath.startsWith("DCIM/")) return false
        if (dir.hasFlag(NodeFlags.PROJECT_ROOT) || dir.hasFlag(NodeFlags.GIT_DIR)) return false
        return covered.none { it === dir || it.isAncestorOf(dir) }
    }

    private fun planMerge(target: DirNode, source: DirNode): FolderMerge? {
        val targetByHash = HashMap<String, FileNode>()
        for (f in target.files) {
            if (f.hidden) continue
            fullHash(f)?.let { targetByHash.putIfAbsent(it, f) }
        }
        val ops = ArrayList<Operation>()
        var commonFiles = 0
        var commonBytes = 0L
        var uniqueFiles = 0
        var uniqueBytes = 0L
        for (f in source.files) {
            if (f.hidden || SafetyPolicy.isCredentialName(f.name) || SafetyPolicy.isInProgressDownload(f.name)) continue
            val sha = fullHash(f) ?: continue
            val match = targetByHash[sha]
            if (match != null && match.size == f.size) {
                ops.add(DeleteDuplicateOp(f.path, match.path, f.size, sha))
                commonFiles++
                commonBytes += f.size
            } else {
                ops.add(MoveFileOp(f.path, target.path + "/" + f.name, f.size, f.mtime, sha))
                uniqueFiles++
                uniqueBytes += f.size
            }
        }
        if (commonFiles == 0 || commonBytes * 2 < source.directBytes) return null
        ops.add(RemoveEmptyDirOp(source.path))
        return FolderMerge(
            id = "merge:${source.path.hashCode()}:${target.path.hashCode()}",
            target = target.path,
            source = source.path,
            sourceBytes = source.directBytes,
            commonFiles = commonFiles,
            commonBytes = commonBytes,
            uniqueFiles = uniqueFiles,
            uniqueBytes = uniqueBytes,
            operations = ops,
        )
    }
}
