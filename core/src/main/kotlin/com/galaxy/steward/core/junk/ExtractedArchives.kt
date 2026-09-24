package com.galaxy.steward.core.junk

import com.galaxy.steward.core.GIB
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.plan.ExtractedCopy
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * A zip whose every file is already unpacked next to it is a second copy of that folder, usually a download that was
 * opened and forgotten. Planning compares the zip's central directory with the scanned tree (names and sizes, nothing
 * is read); right before the zip moves to the quarantine, [verify] reads every unpacked file again and checks it
 * against the CRC-32 the zip stores for it.
 */
object ExtractedArchives {
    /** Zips with more entries or more unpacked bytes than this are not checked. */
    const val MAX_ENTRIES = 20_000
    val MAX_BYTES = 4 * GIB

    data class Entry(val name: String, val size: Long, val crc: Long)

    /**
     * The zip's files (folders and macOS metadata left out), or null when it can't be read, is too big, or names a
     * path that would land outside the folder it is unpacked into.
     */
    fun entries(zip: String): List<Entry>? = try {
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
        val stem = zip.name.substringBeforeLast('.')
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
