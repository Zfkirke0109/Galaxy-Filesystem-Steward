package com.galaxy.steward.core.optimize

import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.model.FileNode
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.Deflater

/**
 * Measures how well a folder's files deflate instead of guessing from their names: a slice from the biggest files of
 * each type, compressed the way packing compresses them. Encrypted ".log" files and ".dat" files that are text inside
 * then say so. At most [budget] bytes are read over a whole scan; past that, the guesses stand.
 */
internal class CompressionProbe(private val budget: Long = 24 * MIB) {
    private var spent = 0L

    /** Measured ratio (deflated / original) by extension, for the types holding most of [files]' bytes. */
    fun ratios(files: List<FileNode>): Map<String, Double> {
        val out = HashMap<String, Double>()
        val types = files.groupBy { it.extension }.entries.sortedByDescending { e -> e.value.sumOf { it.size } }.take(MAX_TYPES)
        for ((ext, list) in types) {
            var raw = 0L
            var packed = 0L
            for (f in list.sortedByDescending { it.size }.take(PER_TYPE)) {
                if (spent >= budget) break
                val (n, z) = deflated(f) ?: continue
                raw += n
                packed += z
            }
            if (raw > 0) out[ext] = packed.toDouble() / raw
        }
        return out
    }

    private fun deflated(f: FileNode): Pair<Long, Long>? {
        val buf = ByteArray(SLICE)
        var n = 0
        try {
            RandomAccessFile(f.path, "r").use { raf ->
                // From the middle of big files: a header compresses unlike the body.
                if (f.size > 2L * SLICE) raf.seek(f.size / 2 - SLICE / 2)
                while (n < SLICE) {
                    val r = raf.read(buf, n, SLICE - n)
                    if (r < 0) break
                    n += r
                }
            }
        } catch (_: IOException) {
            return null
        }
        if (n == 0) return null
        spent += n
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(buf, 0, n)
            deflater.finish()
            val out = ByteArray(16 * 1024)
            var total = 0L
            while (!deflater.finished()) total += deflater.deflate(out)
            n.toLong() to total
        } finally {
            deflater.end()
        }
    }

    companion object {
        private const val SLICE = 64 * 1024
        private const val MAX_TYPES = 8
        private const val PER_TYPE = 2

        /** A zip spends about this much on each file beyond its data: two headers, each with the file's path. */
        fun overhead(relPath: String): Long = 76L + 2 * relPath.length
    }
}
