package com.galaxy.steward.core.hash

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object Hashing {
    /** Bytes read from each end of a file for the quick fingerprint pre-filter. */
    const val QUICK_WINDOW = 64 * 1024

    private val buffers = ThreadLocal.withInitial { ByteArray(1024 * 1024) }

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(chars)
    }

    /** Full-content SHA-256. Throws [IOException] when the file cannot be read. */
    fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = buffers.get()
        File(path).inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    /**
     * Cheap pre-filter: size plus the first and last [QUICK_WINDOW] bytes. Files that differ here cannot be
     * identical, so only the survivors pay for a full SHA-256 (the v18.2 "hash hint" idea).
     */
    fun quickFingerprint(path: String, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray())
        val buffer = buffers.get()
        RandomAccessFile(path, "r").use { raf ->
            val head = minOf(size, QUICK_WINDOW.toLong()).toInt()
            raf.readFully(buffer, 0, head)
            digest.update(buffer, 0, head)
            if (size > QUICK_WINDOW) {
                val tailStart = maxOf(head.toLong(), size - QUICK_WINDOW)
                val tail = (size - tailStart).toInt()
                raf.seek(tailStart)
                raf.readFully(buffer, 0, tail)
                digest.update(buffer, 0, tail)
            }
        }
        return hex(digest.digest())
    }

    /** Files at most this large are fully covered by the quick fingerprint window. */
    fun quickCoversWholeFile(size: Long) = size <= QUICK_WINDOW * 2L
}

/** Live identity of a file: used to detect changes between planning and execution. */
data class FileIdentity(val size: Long, val mtime: Long, val key: Any?) {
    companion object {
        fun of(path: String): FileIdentity? = try {
            val attrs = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isRegularFile) null else FileIdentity(attrs.size(), attrs.lastModifiedTime().toMillis(), attrs.fileKey())
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * Persistent path -> (size, mtime, hashes) cache so repeated scans only hash what changed, like the
 * incremental catalog in v18.2. Entries are invalidated whenever size or mtime differ.
 */
class HashCache(private val file: File?) {
    private data class Entry(val size: Long, val mtime: Long, val quick: String?, val full: String?)

    private val map = ConcurrentHashMap<String, Entry>()
    private val touched = ConcurrentHashMap.newKeySet<String>()

    init {
        load()
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        try {
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size == 5) {
                        val size = parts[1].toLongOrNull() ?: return@forEach
                        val mtime = parts[2].toLongOrNull() ?: return@forEach
                        map[parts[0]] = Entry(size, mtime, parts[3].ifEmpty { null }, parts[4].ifEmpty { null })
                    }
                }
            }
        } catch (_: IOException) {
            map.clear()
        }
    }

    fun quick(path: String, size: Long, mtime: Long): String? =
        map[path]?.takeIf { it.size == size && it.mtime == mtime }?.quick?.also { touched.add(path) }

    fun full(path: String, size: Long, mtime: Long): String? =
        map[path]?.takeIf { it.size == size && it.mtime == mtime }?.full?.also { touched.add(path) }

    fun putQuick(path: String, size: Long, mtime: Long, quick: String) {
        touched.add(path)
        map.compute(path) { _, old ->
            if (old != null && old.size == size && old.mtime == mtime) old.copy(quick = quick) else Entry(size, mtime, quick, null)
        }
    }

    fun putFull(path: String, size: Long, mtime: Long, full: String) {
        touched.add(path)
        map.compute(path) { _, old ->
            if (old != null && old.size == size && old.mtime == mtime) old.copy(full = full) else Entry(size, mtime, null, full)
        }
    }

    /** Hash of a file, served from cache when size and mtime still match; computed (and cached) otherwise. */
    fun fullHash(path: String, size: Long, mtime: Long): String =
        full(path, size, mtime) ?: Hashing.sha256(path).also { putFull(path, size, mtime, it) }

    val size: Int get() = map.size

    /** Writes entries seen in this session, dropping stale ones for files that no longer exist. */
    fun save() {
        val f = file ?: return
        var tmp: File? = null
        try {
            f.parentFile?.mkdirs()
            // A temp file of its own, so two scans saving at the same moment can't write into each other's copy.
            tmp = File.createTempFile(f.name, ".tmp", f.absoluteFile.parentFile)
            tmp.bufferedWriter().use { out ->
                for ((path, e) in map) {
                    if (path !in touched && !File(path).exists()) continue
                    out.append(path).append('\t').append(e.size.toString()).append('\t').append(e.mtime.toString())
                        .append('\t').append(e.quick ?: "").append('\t').append(e.full ?: "").append('\n')
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: IOException) {
            // A cache is an optimisation only; failing to persist it is harmless.
        } finally {
            tmp?.takeIf { it.exists() }?.delete()
        }
    }
}
