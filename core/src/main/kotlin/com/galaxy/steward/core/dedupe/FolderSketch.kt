package com.galaxy.steward.core.dedupe

import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.Zone

/** A folder summarised as the [FolderSketch.K] smallest hashes of its "relative path<TAB>size" lines, sorted. */
class DirSketch(val path: String, val files: Int, val bytes: Long, val newest: Long, val hashes: LongArray)

/** Two folders whose files mostly match by name and size: probably two versions or copies of one thing. */
data class NearCopy(val a: String, val b: String, val similarity: Double) {
    val percent: Int get() = (similarity * 100).toInt()
}

/**
 * Near-copies of folders without reading a byte of them: each folder becomes a bottom-k sketch (MinHash) of its file
 * list, and two sketches estimate the share of files the folders have in common (Jaccard similarity). This finds what
 * exact duplicate detection can't: a second decompile of one app, three runs of one tool, an SDK installed twice. The
 * hash is the one termux-steward.sh uses, so shared storage and Termux folders compare with each other.
 */
object FolderSketch {
    const val K = 64
    private const val MOD = 4294967291L

    /**
     * h = (h * 31 + byte) mod 4294967291 over the line's UTF-8 bytes, then scattered by two multiplications, exactly as
     * the script's awk does it (every intermediate stays below 2^53, where awk's doubles are exact).
     */
    fun hash(line: String): Long {
        var h = 0L
        for (b in line.toByteArray(Charsets.UTF_8)) h = (h * 31 + (b.toInt() and 0xff)) % MOD
        h = mulmod(h + 1, 2654435761L)
        return (mulmod(h, h) + h) % MOD
    }

    private fun mulmod(x: Long, m: Long): Long {
        val hi = m / 65536
        val lo = m % 65536
        return ((x * hi) % MOD * 65536 + x * lo) % MOD
    }

    /** Keeps the [K] smallest distinct values it is given. */
    class BottomK {
        private val heap = LongArray(K)
        private var size = 0
        private val members = HashSet<Long>()

        fun add(h: Long) {
            if (size == K && h >= heap[0]) return
            if (!members.add(h)) return
            if (size < K) {
                heap[size] = h
                siftUp(size++)
            } else {
                members.remove(heap[0])
                heap[0] = h
                siftDown(0)
            }
        }

        fun sorted(): LongArray = heap.copyOf(size).also { it.sort() }

        private fun siftUp(start: Int) {
            var i = start
            while (i > 0) {
                val parent = (i - 1) / 2
                if (heap[parent] >= heap[i]) return
                heap[parent] = heap[i].also { heap[i] = heap[parent] }
                i = parent
            }
        }

        private fun siftDown(start: Int) {
            var i = start
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var big = i
                if (l < size && heap[l] > heap[big]) big = l
                if (r < size && heap[r] > heap[big]) big = r
                if (big == i) return
                heap[big] = heap[i].also { heap[i] = heap[big] }
                i = big
            }
        }
    }

    /** Estimated Jaccard similarity of two sorted sketches: the share of the union's k smallest that both hold. */
    fun similarity(a: LongArray, b: LongArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val k = minOf(K, maxOf(a.size, b.size))
        var i = 0
        var j = 0
        var taken = 0
        var both = 0
        while (taken < k && (i < a.size || j < b.size)) {
            when {
                j >= b.size || (i < a.size && a[i] < b[j]) -> i++
                i >= a.size || b[j] < a[i] -> j++
                else -> {
                    both++
                    i++
                    j++
                }
            }
            taken++
        }
        return if (taken == 0) 0.0 else both.toDouble() / taken
    }

    /**
     * Sketches of the folders in [root]'s tree worth comparing: 2 to 4 levels deep, at least [minFiles] files and
     * [minBytes], outside app-owned storage, the steward's folders and hidden folders.
     */
    fun sketches(root: DirNode, minFiles: Int = 20, minBytes: Long = 8L * 1024 * 1024): List<DirSketch> {
        val candidates = ArrayList<DirNode>()
        root.walkDirs { d ->
            if (d.depth in 2..4 && !d.hidden && d.totalFiles >= minFiles && d.totalBytes >= minBytes &&
                d.zone != Zone.APP_OWNED && d.zone != Zone.STEWARD && !d.hasFlag(NodeFlags.GIT_DIR)
            ) {
                candidates += d
            }
        }
        return candidates.map { d ->
            val bottom = BottomK()
            var newest = 0L
            val prefix = d.path.length + 1
            d.walkFiles { f ->
                bottom.add(hash(f.path.substring(prefix) + "\t" + f.size))
                if (f.mtime > newest) newest = f.mtime
            }
            DirSketch(d.path, d.totalFiles, d.totalBytes, newest, bottom.sorted())
        }
    }

    /**
     * Pairs of sketches at least [threshold] alike, biggest first. Neither may hold the other, sizes must be within a
     * factor of two, and a pair inside an already listed pair (`A/x` and `B/x` once `A` and `B` are listed) is left out.
     */
    fun nearCopies(sketches: List<DirSketch>, threshold: Double = 0.6): List<NearCopy> {
        val byHash = HashMap<Long, MutableList<Int>>()
        sketches.forEachIndexed { i, s -> s.hashes.forEach { h -> byHash.getOrPut(h) { ArrayList(2) } += i } }
        val shared = HashMap<Long, Int>()
        for (ids in byHash.values) {
            if (ids.size < 2 || ids.size > 40) continue
            for (x in ids.indices) for (y in x + 1 until ids.size) {
                val key = (ids[x].toLong() shl 32) or ids[y].toLong()
                shared[key] = (shared[key] ?: 0) + 1
            }
        }
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for ((key, count) in shared) {
            if (count < K / 8) continue
            val a = sketches[(key ushr 32).toInt()]
            val b = sketches[(key and 0xffffffffL).toInt()]
            if (a.path.startsWith(b.path + "/") || b.path.startsWith(a.path + "/")) continue
            val small = minOf(a.bytes, b.bytes)
            val large = maxOf(a.bytes, b.bytes)
            if (small <= 0 || large > small * 2) continue
            val sim = similarity(a.hashes, b.hashes)
            if (sim >= threshold) pairs += Triple((key ushr 32).toInt(), (key and 0xffffffffL).toInt(), sim)
        }
        val listed = ArrayList<String>()
        fun covered(path: String) = listed.any { path == it || path.startsWith("$it/") }
        val out = ArrayList<NearCopy>()
        val order = compareByDescending<Triple<Int, Int, Double>> { sketches[it.first].bytes + sketches[it.second].bytes }.thenByDescending { it.third }
        for ((x, y, sim) in pairs.sortedWith(order)) {
            val a = sketches[x].path
            val b = sketches[y].path
            if (covered(a) || covered(b)) continue
            listed += a
            listed += b
            out += NearCopy(a, b, sim)
        }
        return out
    }
}
