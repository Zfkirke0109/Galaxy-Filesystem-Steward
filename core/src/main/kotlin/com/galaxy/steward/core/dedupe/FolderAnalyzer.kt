package com.galaxy.steward.core.dedupe

import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.hash.HashCache
import com.galaxy.steward.core.hash.Hashing
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.organize.BuiltInRules
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
    /** For homes named after the device (firmware): where same-name folders gather. */
    private val deviceLabel: String = "",
) {
    private val knownHashes = HashMap<FileNode, String>(fileDuplicates.fullHashes)

    suspend fun analyze(tree: StorageTree): FolderAnalysis {
        val exact = exactDuplicates(tree)
        val covered = HashSet<DirNode>()
        exact.forEach { group -> group.copies.forEach { copy -> tree.findAbsolute(copy.path)?.let { covered.add(it) } } }
        val merges = overlaps(covered)
        merges.forEach { m -> listOf(m.source, m.target).forEach { p -> tree.findAbsolute(p)?.let { covered.add(it) } } }
        return FolderAnalysis(exact, merges + nameMerges(tree, covered))
    }

    // ---------------------------------------------------------------- folders of one name in two places

    /**
     * Folders with the same name (case, spaces and punctuation aside) in different places of your own files, like
     * Documents/LogcatX and Documents/Reports/Diagnostics/LogcatX: what is in one goes into the other, keeping its
     * layout, identical files go once, and different files with one name both stay. Only particular names (not
     * "images" or "backup"), folders of your own files that aren't projects, code or pinned, and up to 2,000 files.
     *
     * A copy beside its original ("Camera (1)" next to "Camera", "Scripts - Copy" next to "Scripts") merges back into
     * it whatever the name, in your files or the media library: the copy marker says what it is. A copy's name also
     * counts as the original's elsewhere ("LogcatX (1)" joins "LogcatX"). The merge goes into the copy that is already
     * where the organizer files such things (a home like Reports/Diagnostics), else the original name, else the fuller.
     */
    private suspend fun nameMerges(tree: StorageTree, covered: Set<DirNode>): List<FolderMerge> {
        val byName = HashMap<String, MutableList<DirNode>>()
        val pairs = ArrayList<Pair<DirNode, DirNode>>()
        fun free(d: DirNode) = d.totalFiles in 1..MAX_NAME_MERGE_FILES && !d.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) &&
            !d.insideFlagged(NodeFlags.PROJECT_ROOT or NodeFlags.GIT_DIR or NodeFlags.CODE_TREE) &&
            covered.none { it === d || it.isAncestorOf(d) || d.isAncestorOf(it) }
        tree.root.walkDirs { d ->
            // A copy next to its original, of any name.
            for (copy in d.dirs) {
                if (copy.hidden || copy.depth < 2 || (copy.zone != Zone.USER_MANAGED && copy.zone != Zone.MEDIA_LIBRARY)) continue
                val base = SafetyPolicy.withoutCopyMarker(copy.name, isDirectory = true) ?: continue
                val original = d.dirs.firstOrNull { it !== copy && it.name.equals(base, ignoreCase = true) && it.zone == copy.zone } ?: continue
                if (free(copy) && free(original)) pairs += original to copy
            }
            if (d.depth < 2 || d.hidden || d.zone != Zone.USER_MANAGED || !free(d)) return@walkDirs
            val key = nameKey(d.name)
            if (key.length < 4 || key in GENERIC_NAMES || key.all { it.isDigit() }) return@walkDirs
            byName.getOrPut(key) { ArrayList() } += d
        }
        val out = ArrayList<FolderMerge>()
        // A folder takes part in one merge per scan, as source or target, and neither inside nor around another one's:
        // no two merges move the same files.
        val used = ArrayList<DirNode>()
        fun clashes(d: DirNode) = used.any { it === d || it.isAncestorOf(d) || d.isAncestorOf(it) }
        for ((original, copy) in pairs) {
            currentCoroutineContext().ensureActive()
            if (clashes(copy) || clashes(original)) continue
            planTreeMerge(original, copy)?.let {
                out += it
                used += copy
                used += original
            }
        }
        for ((_, dirs) in byName) {
            val open = dirs.filter { !clashes(it) }
            if (open.size < 2) continue
            val ranked = open.sortedWith(nameMergeTarget)
            val target = ranked.first()
            var merged = false
            for (source in ranked.drop(1)) {
                currentCoroutineContext().ensureActive()
                if (target.isAncestorOf(source) || source.isAncestorOf(target) || clashes(source)) continue
                planTreeMerge(target, source)?.let {
                    out += it
                    used += source
                    merged = true
                }
            }
            if (merged) used += target
        }
        return out.sortedByDescending { it.sourceBytes }.take(25)
    }

    /** "LogcatX (1)" and "logcat-x" are one name: copy marker, case, spaces and punctuation aside. */
    private fun nameKey(name: String): String =
        (SafetyPolicy.withoutCopyMarker(name, isDirectory = true) ?: name).lowercase().filter { it.isLetterOrDigit() }

    /** Where same-name folders gather: in a home of the organizer's, then the name without a copy marker, then the fuller. */
    private val nameMergeTarget: Comparator<DirNode> =
        compareByDescending<DirNode> { d -> d.parent?.let { BuiltInRules.isExactHome(it.relPath, deviceLabel, settings.customRules) } ?: false }
            .thenBy { if (SafetyPolicy.withoutCopyMarker(it.name, isDirectory = true) != null) 1 else 0 }
            .thenByDescending { it.totalFiles }
            .then(KeeperRanking.dirComparator)

    /** Every file of [source] into the same place under [target]; an identical one already there makes it a duplicate. */
    private fun planTreeMerge(target: DirNode, source: DirNode): FolderMerge? {
        val there = HashMap<String, FileNode>()
        fun index(d: DirNode, prefix: String) {
            d.files.forEach { there[prefix + it.name] = it }
            d.dirs.forEach { index(it, prefix + it.name + "/") }
        }
        index(target, "")
        val ops = ArrayList<Operation>()
        var commonFiles = 0
        var commonBytes = 0L
        var uniqueFiles = 0
        var uniqueBytes = 0L
        // Folders that keep a file the merge leaves (hidden files, keys, downloads in progress), and so stay.
        val keeps = HashSet<DirNode>()
        fun walk(d: DirNode, prefix: String) {
            for (f in d.files) {
                // Hidden files stay too: a ".nomedia" moved into DCIM/Camera would hide the whole camera roll.
                if (f.hidden || SafetyPolicy.isCredentialName(f.name) || SafetyPolicy.isInProgressDownload(f.name)) {
                    var up: DirNode? = d
                    while (up != null && keeps.add(up) && up !== source) up = up.parent
                    continue
                }
                val rel = prefix + f.name
                val match = there[rel]?.takeIf { it.size == f.size }
                val sha = if (match != null) fullHash(f) else null
                if (match != null && sha != null && fullHash(match) == sha) {
                    ops.add(DeleteDuplicateOp(f.path, match.path, f.size, sha))
                    commonFiles++
                    commonBytes += f.size
                } else {
                    // A different file of the same name gets a free name beside it when it is moved.
                    ops.add(MoveFileOp(f.path, target.path + "/" + rel, f.size, f.mtime, sha))
                    uniqueFiles++
                    uniqueBytes += f.size
                }
            }
            d.dirs.forEach { walk(it, prefix + it.name + "/") }
        }
        walk(source, "")
        if (commonFiles + uniqueFiles == 0) return null
        val stays = keeps.map { it.path }.toSet()
        subdirsDeepestFirst(source).map { source.path + "/" + it }.filter { it !in stays }.forEach { ops.add(RemoveEmptyDirOp(it)) }
        if (source.path !in stays) ops.add(RemoveEmptyDirOp(source.path))
        return FolderMerge(
            id = "namemerge:${source.path.hashCode()}:${target.path.hashCode()}",
            target = target.path,
            source = source.path,
            sourceBytes = source.totalBytes,
            commonFiles = commonFiles,
            commonBytes = commonBytes,
            uniqueFiles = uniqueFiles,
            uniqueBytes = uniqueBytes,
            operations = ops,
            sameName = true,
        )
    }

    // ---------------------------------------------------------------- exact duplicate trees

    private suspend fun exactDuplicates(tree: StorageTree): List<FolderDuplicateGroup> {
        val signatures = HashMap<DirNode, String>()
        structuralSignature(tree.root, insideHidden = false, signatures)

        val candidates = signatures.entries
            .filter { (dir, _) -> eligibleFolder(dir) }
            .groupBy({ it.value }, { it.key })
            .entries.filter { it.value.size > 1 }
            .sortedBy { (_, members) -> members.minOf { it.depth } }

        val total = candidates.sumOf { it.value.size }.toLong()
        var done = 0L
        val covered = HashSet<DirNode>()
        val out = ArrayList<FolderDuplicateGroup>()
        for ((signature, members) in candidates) {
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
                        // The same files can make two groups whose layouts differ only by an empty folder: the layout
                        // keeps their ids apart (one id twice crashed the Duplicates screen's list).
                        id = "folder:${hash.take(16)}:${signature.take(8)}",
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
        val sig = Hashing.hex(digest.digest())
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
        return Hashing.hex(digest.digest())
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
            // By path, so a group of many copies always offers the same ten folders, whatever order the hashes came in.
            val nodes = byHash[group.sha256]?.filter { it.size == group.size }?.sortedBy { it.path } ?: continue
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
        val candidates = common.entries
            .filter { it.value >= threshold }
            .map { (pair, bytes) ->
                val ranked = listOf(pair.a, pair.b).sortedWith(KeeperRanking.dirComparator)
                Triple(ranked[0], ranked[1], bytes)
            }
            .filter { (_, source, bytes) -> bytes * 2 >= source.directBytes }
            // The biggest overlaps first (they delete the most and move the least), then the better target, then by
            // path: the same plan from the same folders every time.
            .sortedWith(
                compareByDescending<Triple<DirNode, DirNode, Long>> { it.third }
                    .thenComparator { x, y -> KeeperRanking.dirComparator.compare(x.first, y.first) }
                    .thenBy { it.second.path },
            )

        // Merges that can all be applied together, in any order and any subset: a folder is merged away at most once,
        // never into a folder another merge empties, and neither inside nor around another merge's folders. A target may
        // take in several sources (three copies of one folder become one). Chains (A into B, B into C) wait for the
        // next scan, which sees B's new content.
        val sources = ArrayList<DirNode>()
        val targets = ArrayList<DirNode>()
        fun near(a: DirNode, b: DirNode) = a === b || a.isAncestorOf(b) || b.isAncestorOf(a)
        val out = ArrayList<FolderMerge>()
        var planned = 0
        for ((target, source, _) in candidates) {
            if (planned >= MAX_OVERLAP_MERGES) break
            if (sources.any { near(it, source) } || targets.any { near(it, source) }) continue
            if (sources.any { near(it, target) } || targets.any { it !== target && near(it, target) }) continue
            currentCoroutineContext().ensureActive()
            planned++
            planMerge(target, source)?.let {
                out += it
                sources += source
                targets += target
            }
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
        var left = 0
        for (f in source.files) {
            if (f.hidden || SafetyPolicy.isCredentialName(f.name) || SafetyPolicy.isInProgressDownload(f.name)) {
                left++
                continue
            }
            val sha = fullHash(f)
            if (sha == null) {
                left++
                continue
            }
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
        // Only this folder's own files take part. The folder goes once nothing of it is left: with sub-folders or files
        // that stay (hidden, keys, downloads in progress) the merge is done without it, rather than "skipped".
        if (left == 0 && source.dirs.isEmpty()) ops.add(RemoveEmptyDirOp(source.path))
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

    private companion object {
        const val MAX_NAME_MERGE_FILES = 2_000

        /** Overlap merges planned per scan; each hashes both folders in full. */
        const val MAX_OVERLAP_MERGES = 25

        /** Names too common to mean two folders hold the same things. */
        val GENERIC_NAMES = setOf(
            "data", "files", "assets", "images", "image", "photos", "videos", "audio", "music", "docs", "documents", "source",
            "sources", "build", "output", "outputs", "input", "inputs", "backup", "backups", "archive", "archives", "export",
            "exports", "import", "imports", "temp", "cache", "logs", "misc", "other", "others", "test", "tests", "sample",
            "samples", "download", "downloads", "screenshots", "media", "resources", "config", "configs", "settings", "release",
            "releases", "debug", "latest", "current", "previous", "final", "drafts", "draft", "notes", "reports", "report",
            "results", "result", "projects", "project", "scripts", "tools", "models", "fonts", "icons", "thumbs", "thumbnails",
        )
    }
}
