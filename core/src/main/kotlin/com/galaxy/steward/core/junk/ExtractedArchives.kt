package com.galaxy.steward.core.junk

import com.galaxy.steward.core.GIB
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.plan.ExtractedCopy
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * An archive whose every file is already unpacked next to it is a second copy of that folder, usually a download that
 * was opened and forgotten, or a backup of a folder that is still there. Planning compares the archive's list of files
 * with the scanned tree (names and sizes); right before the archive moves to the quarantine, [verify] reads every
 * unpacked file again and checks it against the CRC-32 of its copy in the archive. Zips store that CRC; for tar and
 * tar.gz it is computed while reading the archive.
 */
object ExtractedArchives {
    /** Archives with more entries or more unpacked bytes than this are not checked. */
    const val MAX_ENTRIES = 20_000
    val MAX_BYTES = 4 * GIB

    data class Entry(val name: String, val size: Long, val crc: Long)

    private val SUFFIXES = listOf(".tar.gz", ".tgz", ".tar", ".zip")

    /** True for the archive types this can list: zip, tar and gzip-compressed tar. */
    fun isSupported(name: String): Boolean = name.lowercase().let { n -> SUFFIXES.any { n.endsWith(it) && n.length > it.length } }

    /** "backup.tar.gz" -> "backup": the folder name an archive usually unpacks to. */
    fun stem(name: String): String {
        val lower = name.lowercase()
        val suffix = SUFFIXES.firstOrNull { lower.endsWith(it) } ?: return name.substringBeforeLast('.')
        return name.dropLast(suffix.length)
    }

    /**
     * The archive's files (folders and macOS metadata left out), or null when it can't be read, is too big, holds links,
     * or names a path that would land outside the folder it is unpacked into.
     */
    fun entries(archive: String): List<Entry>? {
        val lower = archive.lowercase()
        return when {
            lower.endsWith(".zip") -> zipEntries(archive)
            lower.endsWith(".tar") -> tarEntries(File(archive), gzip = false)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> tarEntries(File(archive), gzip = true)
            else -> null
        }
    }

    private fun zipEntries(zip: String): List<Entry>? = try {
        ZipFile(File(zip)).use { z ->
            val out = ArrayList<Entry>()
            var bytes = 0L
            val all = z.entries()
            while (all.hasMoreElements()) {
                val e = all.nextElement()
                val name = e.name
                if (e.isDirectory || name.startsWith("__MACOSX/") || name.substringAfterLast('/') == ".DS_Store") continue
                if (!safeName(name) || e.size < 0 || e.crc < 0) return null
                bytes += e.size
                out += Entry(name, e.size, e.crc)
                if (out.size > MAX_ENTRIES || bytes > MAX_BYTES) return null
            }
            out.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        // Unreadable, not a zip, or names in an encoding the platform refuses.
        null
    }

    /**
     * Reads a (gzip-compressed) tar from start to end: ustar and GNU long names, pax paths, regular files and folders.
     * Links, devices and sparse files make it null: unpacking those doesn't give back plain files to compare.
     */
    private fun tarEntries(file: File, gzip: Boolean): List<Entry>? = try {
        val raw = BufferedInputStream(file.inputStream(), 1 shl 16)
        (if (gzip) GZIPInputStream(raw, 1 shl 16) else raw).use { input ->
            val out = ArrayList<Entry>()
            val header = ByteArray(512)
            val buffer = ByteArray(1 shl 16)
            var bytes = 0L
            var longName: String? = null
            var paxPath: String? = null
            while (true) {
                if (!readBlock(input, header)) break
                if (header.all { it == 0.toByte() }) break
                val size = tarNumber(header, 124, 12) ?: return null
                val type = header[156].toInt().toChar()
                when (type) {
                    'L' -> {
                        longName = readString(input, size) ?: return null
                        continue
                    }
                    'x' -> {
                        paxPath = readString(input, size)?.let(::paxPath) ?: return null
                        continue
                    }
                    'g' -> {
                        if (!skip(input, padded(size))) return null
                        continue
                    }
                }
                val ustar = String(header, 257, 5, Charsets.US_ASCII) == "ustar"
                val short = cString(header, 0, 100)
                val prefix = if (ustar) cString(header, 345, 155) else ""
                val name = (paxPath ?: longName ?: if (prefix.isNotEmpty()) "$prefix/$short" else short).removePrefix("./")
                longName = null
                paxPath = null
                when (type) {
                    '0', '\u0000', '7' -> {
                        if (name.startsWith("__MACOSX/") || name.substringAfterLast('/') == ".DS_Store") {
                            if (!skip(input, padded(size))) return null
                            continue
                        }
                        if (!safeName(name)) return null
                        val crc = CRC32()
                        var left = size
                        while (left > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (n < 0) return null
                            crc.update(buffer, 0, n)
                            left -= n
                        }
                        if (!skip(input, padded(size) - size)) return null
                        bytes += size
                        out += Entry(name, size, crc.value)
                        if (out.size > MAX_ENTRIES || bytes > MAX_BYTES) return null
                    }
                    '5' -> if (!skip(input, padded(size))) return null
                    else -> return null
                }
            }
            out.takeIf { it.isNotEmpty() }
        }
    } catch (_: IOException) {
        null
    }

    private fun padded(size: Long) = (size + 511) / 512 * 512

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var off = 0
        while (off < block.size) {
            val n = input.read(block, off, block.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skip(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) return false
                left--
            } else {
                left -= n
            }
        }
        return true
    }

    private fun readString(input: InputStream, size: Long): String? {
        if (size !in 0..65_536) return null
        val data = ByteArray(size.toInt())
        if (!readBlock(input, data) || !skip(input, padded(size) - size)) return null
        return String(data, Charsets.UTF_8).trimEnd('\u0000')
    }

    /** The "path" record of a pax header ("30 path=some/long/name\n"), or null when it has none. */
    private fun paxPath(records: String): String? =
        records.lineSequence().mapNotNull { line -> line.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=") }.lastOrNull()

    private fun cString(block: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && block[end] != 0.toByte()) end++
        return String(block, off, end - off, Charsets.UTF_8)
    }

    /** Octal, or GNU base-256 for sizes over 8 GiB. */
    private fun tarNumber(block: ByteArray, off: Int, len: Int): Long? {
        if (block[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (block[i].toLong() and 0xff)
            return v.takeIf { it >= 0 }
        }
        val text = cString(block, off, len).trim()
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8)
    }

    private fun safeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && name.none { it < ' ' } &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /**
     * Where [zip] was unpacked, judged from the scanned tree: a folder next to it named after the zip (with or without
     * the zip's own top folder inside), or its single top folder unpacked next to it. Null unless every file of the
     * zip is there with the same size.
     */
    fun extractedCopy(zip: FileNode, entries: List<Entry>): ExtractedCopy? {
        val parent = zip.dir
        val stem = stem(zip.name)
        val top = entries.map { it.name.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { it.isNotEmpty() }
        val candidates = buildList {
            parent.child(stem)?.let { folder ->
                add(folder to "")
                if (top != null) add(folder to "$top/")
            }
            if (top != null && top != stem) parent.child(top)?.let { add(it to "$top/") }
        }
        return candidates.firstOrNull { (folder, strip) -> entries.all { holds(folder, it.name.removePrefix(strip), it.size) } }
            ?.let { (folder, strip) -> ExtractedCopy(folder.path, strip) }
    }

    private fun holds(folder: DirNode, rel: String, size: Long): Boolean {
        var dir = folder
        val parts = rel.split('/')
        for (i in 0 until parts.size - 1) dir = dir.child(parts[i]) ?: return false
        return dir.files.any { it.name == parts.last() && it.size == size }
    }

    /** True when every file of [zip] is unpacked at [copy] as a regular file with the same size and CRC-32. */
    fun verify(zip: String, copy: ExtractedCopy): Boolean {
        val entries = entries(zip) ?: return false
        val root = Paths.get(copy.folder)
        return entries.all { e ->
            if (!e.name.startsWith(copy.stripPrefix)) return@all false
            val file = root.resolve(e.name.removePrefix(copy.stripPrefix))
            val attrs = try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return@all false
            }
            attrs.isRegularFile && attrs.size() == e.size && crcOf(file.toFile()) == e.crc
        }
    }

    private fun crcOf(file: File): Long? = try {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        crc.value
    } catch (_: IOException) {
        null
    }
}
