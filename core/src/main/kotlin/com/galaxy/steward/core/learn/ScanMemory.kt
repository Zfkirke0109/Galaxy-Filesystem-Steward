package com.galaxy.steward.core.learn

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.Insight
import com.galaxy.steward.core.plan.Severity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Files and folders you moved yourself since the last scan, by where they are now; [files] counts the files in them. */
data class YourMoves(val paths: Set<String>, val files: Int) {
    companion object {
        val NONE = YourMoves(emptySet(), 0)
    }
}

/** One scan's totals: what the scan saw, the volume's free and total space (-1 when not known), and the biggest top folders. */
data class StoragePoint(val time: Long, val scanned: Long, val free: Long, val total: Long, val folders: Map<String, Long>)

/**
 * What the steward remembers between scans, in the app's private folder; nothing leaves the phone.
 *
 * Where each of your files was, by name and size (a sorted table of 64-bit keys, gzipped: a few bytes a file). The
 * next scan compares: a file that is somewhere else now, and not because the steward moved it or undid a move, is one
 * you moved yourself. Where you put things is the best evidence of where such things belong, and something you just
 * put somewhere isn't suggested to move again.
 *
 * And how full storage was after each scan, to say what grows and how fast.
 */
class ScanMemory(private val directory: File, private val journals: JournalStore? = null) {
    private val placesFile get() = File(directory, "places.bin")
    private val historyFile get() = File(directory, "storage-history.tsv")

    private class Places(val runs: Set<String>, val rollbacks: Set<String>, val folders: List<String>, val keys: LongArray, val ids: IntArray)

    /** What you moved yourself since the places were last remembered; nothing on the first scan. */
    fun movesSince(tree: StorageTree): YourMoves {
        val places = loadPlaces() ?: return YourMoves.NONE
        val steward = stewardPaths(places)
        val now = ArrayList<FileNode>()
        eligible(tree.root) { now += it }
        val keys = LongArray(now.size) { key(now[it]) }
        val sorted = keys.sortedArray()
        val paths = HashSet<String>()
        var files = 0
        for ((i, f) in now.withIndex()) {
            val k = keys[i]
            // Two files alike now (a copy, not a move), or two alike then: no telling which went where.
            if (count(sorted, k) != 1) continue
            val at = places.keys.binarySearch(k)
            if (at < 0 || places.ids[at] < 0) continue
            val before = places.folders[places.ids[at]]
            if (before == f.dir.relPath) continue
            if (stewardMoved(f, steward)) continue
            paths += tree.rootPath + "/" + movedNode(before + "/" + f.name, f.relPath)
            files++
        }
        return YourMoves(paths, files)
    }

    /** Remembers where every file is now, for the next scan's [movesSince]. */
    fun rememberPlaces(tree: StorageTree) {
        val files = ArrayList<FileNode>()
        eligible(tree.root) { if (files.size < MAX_FILES) files += it }
        val folderIds = HashMap<DirNode, Int>()
        val folders = ArrayList<String>()
        val raw = LongArray(files.size) { key(files[it]) }
        val order = files.indices.sortedBy { raw[it] }
        val pairs = LongArray(files.size)
        val ids = IntArray(files.size)
        for ((j, i) in order.withIndex()) {
            val f = files[i]
            pairs[j] = raw[i]
            ids[j] = folderIds.getOrPut(f.dir) { folders += f.dir.relPath; folders.size - 1 }
        }
        // A key held twice is ambiguous: marked, so neither copy counts as moved.
        for (j in 1 until pairs.size) if (pairs[j] == pairs[j - 1]) { ids[j] = -1; ids[j - 1] = -1 }
        val runs = journals?.list().orEmpty()
        writeAtomically(placesFile) { out ->
            out.writeInt(MAGIC)
            out.writeInt(runs.size)
            runs.forEach { out.writeUTF(it.id) }
            val undone = runs.filter { it.rolledBackAt != null }
            out.writeInt(undone.size)
            undone.forEach { out.writeUTF(it.id) }
            out.writeInt(folders.size)
            folders.forEach { out.writeUTF(it) }
            out.writeInt(pairs.size)
            pairs.forEach(out::writeLong)
            ids.forEach(out::writeInt)
        }
    }

    fun forgetPlaces() {
        placesFile.delete()
    }

    // ------------------------------------------------------------------ storage history

    fun history(): List<StoragePoint> = try {
        if (!historyFile.isFile) emptyList() else historyFile.readLines().mapNotNull(::decode)
    } catch (_: IOException) {
        emptyList()
    }

    fun record(point: StoragePoint) {
        try {
            directory.mkdirs()
            val lines = (history() + point).takeLast(MAX_POINTS).map(::encode)
            writeText(historyFile, lines.joinToString("\n", postfix = "\n"))
        } catch (_: IOException) {
            // History is a convenience: a full disk must not fail the scan.
        }
    }

    // ------------------------------------------------------------------ internals

    private fun loadPlaces(): Places? {
        if (!placesFile.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(placesFile)))).use { input ->
                if (input.readInt() != MAGIC) return null
                val runs = HashSet<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
                val rollbacks = HashSet<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
                val folders = ArrayList<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
                val n = input.readInt()
                val keys = LongArray(n) { input.readLong() }
                val ids = IntArray(n) { input.readInt() }
                Places(runs, rollbacks, folders, keys, ids)
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Where the steward's own runs since then put things, and where undoing a run put them back. */
    private fun stewardPaths(places: Places): Set<String> {
        val store = journals ?: return emptySet()
        val out = HashSet<String>()
        for (run in store.list()) {
            val newRun = run.id !in places.runs
            val newUndo = run.rolledBackAt != null && run.id !in places.rollbacks
            if (!newRun && !newUndo) continue
            for (e in store.entries(run.id)) {
                if (e.action != JournalAction.MOVED && e.action != JournalAction.MOVED_DIR) continue
                if (newRun) out += e.b
                if (newUndo) out += e.a
            }
        }
        return out
    }

    private fun stewardMoved(f: FileNode, steward: Set<String>): Boolean {
        if (steward.isEmpty()) return false
        if (f.path in steward) return true
        var d: DirNode? = f.dir
        while (d != null && !d.isRoot) {
            if (d.path in steward) return true
            d = d.parent
        }
        return false
    }

    private fun count(sorted: LongArray, k: Long): Int {
        var i = sorted.binarySearch(k)
        if (i < 0) return 0
        while (i > 0 && sorted[i - 1] == k) i--
        var n = 0
        while (i + n < sorted.size && sorted[i + n] == k) n++
        return n
    }

    private fun writeAtomically(target: File, write: (DataOutputStream) -> Unit) {
        try {
            directory.mkdirs()
            val tmp = File(directory, target.name + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(tmp)))).use(write)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: IOException) {
            // Remembering is a convenience: a full disk must not fail the scan.
        }
    }

    private fun writeText(target: File, text: String) {
        val tmp = File(directory, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun encode(p: StoragePoint): String =
        (listOf("S", p.time, p.scanned, p.free, p.total) + p.folders.flatMap { (k, v) -> listOf(k.replace('\t', ' '), v) }).joinToString("\t")

    private fun decode(line: String): StoragePoint? {
        val p = line.split('\t')
        if (p.size < 5 || p[0] != "S") return null
        val folders = LinkedHashMap<String, Long>()
        var i = 5
        while (i + 1 < p.size) {
            p[i + 1].toLongOrNull()?.let { folders[p[i]] = it }
            i += 2
        }
        return StoragePoint(p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: -1, p[4].toLongOrNull() ?: -1, folders)
    }

    companion object {
        private const val MAGIC = 0x47535031 // "GSP1"
        private const val MAX_FILES = 400_000
        private const val MAX_POINTS = 400

        /** Your own files: not hidden, not in projects, source trees, app folders or the steward's own. */
        internal fun eligible(dir: DirNode, visit: (FileNode) -> Unit) {
            if (!dir.isRoot && dir.hidden) return
            if (dir.hasFlag(NodeFlags.CODE_TREE) || dir.hasFlag(NodeFlags.PROJECT_ROOT) || dir.hasFlag(NodeFlags.GIT_DIR)) return
            if (dir.zone == Zone.APP_OWNED || dir.zone == Zone.STEWARD) return
            if (dir.zone == Zone.USER_MANAGED || dir.zone == Zone.MEDIA_LIBRARY) {
                for (f in dir.files) if (!f.hidden && f.size > 0) visit(f)
            }
            for (d in dir.dirs) eligible(d, visit)
        }

        /** FNV-1a over the name, then the size mixed in. */
        internal fun key(f: FileNode): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in f.name) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            h = h xor f.size
            h *= -0x61c8864680b583ebL
            return h xor (h ushr 31)
        }

        /**
         * The highest thing that moved: "Documents/Ratchet/a.iso" now at "Documents/Games/PS2/Ratchet/a.iso" means the
         * folder Ratchet moved, not only the file.
         */
        internal fun movedNode(before: String, now: String): String {
            val a = before.split('/')
            val b = now.split('/')
            var same = 1
            while (same < a.size && same < b.size && a[a.size - 1 - same] == b[b.size - 1 - same]) same++
            return b.take(b.size - same + 1).joinToString("/")
        }

        /**
         * How storage changed since a scan at least [minDays] days ago (the oldest within [windowDays]): how much it grew,
         * which top folders grew most, and when it would be full at that pace. Null without such a scan or real growth.
         */
        fun growthInsight(history: List<StoragePoint>, now: StoragePoint, minDays: Int = 3, windowDays: Int = 45): Insight? {
            val then = history.filter { now.time - it.time in minDays * DAY_MS..windowDays * DAY_MS }.minByOrNull { it.time } ?: return null
            val days = ((now.time - then.time) / DAY_MS).coerceAtLeast(1)
            // The volume's used space counts apps' data too; the scan's total when the volume wasn't known.
            fun used(p: StoragePoint) = if (p.total > 0 && p.free >= 0) p.total - p.free else p.scanned
            val grew = if (now.total > 0 && then.total > 0) used(now) - used(then) else now.scanned - then.scanned
            if (grew < 256 * MIB) return null
            val growers = now.folders.map { (k, v) -> k to v - (then.folders[k] ?: 0L) }.filter { it.second >= 64 * MIB }
                .sortedByDescending { it.second }.take(3)
            val perDay = grew / days
            val full = if (now.free > 0 && perDay > 0) now.free / perDay else -1
            val severity = if (full in 0..60) Severity.WARNING else Severity.INFO
            return Insight(
                severity,
                "Storage grew ${grew.humanBytes()} in $days days",
                "About ${perDay.humanBytes()} a day since ${if (days == 1L) "yesterday" else "$days days ago"}" +
                    (if (growers.isNotEmpty()) ", most in " + growers.joinToString(", ") { (k, v) -> "$k (+${v.humanBytes()})" } else "") +
                    "." + (if (full >= 0) " At this pace the free ${now.free.humanBytes()} lasts about $full days." else ""),
            )
        }

        /** The totals of [tree] for the history: the 12 biggest top folders. */
        fun pointOf(tree: StorageTree, time: Long, free: Long, total: Long): StoragePoint = StoragePoint(
            time,
            tree.root.totalBytes,
            free,
            total,
            tree.root.dirs.filter { !it.hidden }.sortedByDescending { it.totalBytes }.take(12).associate { it.name to it.totalBytes },
        )
    }
}
