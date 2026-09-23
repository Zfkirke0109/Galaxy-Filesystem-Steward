package com.galaxy.steward.core.exec

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Journal actions. Every mutation is appended (and flushed) only after it succeeded and was verified, so a
 * journal is always a truthful, replayable record - the Android equivalent of the Termux rollback manifests.
 */
object JournalAction {
    const val MKDIR = "MKDIR"
    const val MOVED = "MOVED"
    const val MOVED_DIR = "MOVED_DIR"
    const val DEDUPED = "DEDUPED"
    const val DEDUPED_QUARANTINED = "DEDUPED_QUARANTINED"
    const val QUARANTINED = "QUARANTINED"
    const val RMDIR = "RMDIR"
}

data class JournalEntry(
    val action: String,
    val a: String,
    val b: String,
    val size: Long,
    val mtime: Long,
    val sha256: String?,
) {
    fun encode(): String = listOf(action, a, b.ifEmpty { "-" }, size.toString(), mtime.toString(), sha256 ?: "-").joinToString("\t")

    companion object {
        fun decode(line: String): JournalEntry? {
            val p = line.split('\t')
            if (p.size != 6) return null
            return JournalEntry(
                p[0], p[1], if (p[2] == "-") "" else p[2], p[3].toLongOrNull() ?: -1, p[4].toLongOrNull() ?: -1,
                p[5].takeIf { it != "-" },
            )
        }
    }
}

class JournalWriter(val file: File) : Closeable {
    private val out: BufferedWriter

    init {
        file.parentFile?.mkdirs()
        out = BufferedWriter(FileWriter(file, true))
    }

    fun meta(key: String, value: String) {
        out.append('#').append(key).append('\t').append(value.replace('\t', ' ').replace('\n', ' ')).append('\n')
        out.flush()
    }

    fun entry(entry: JournalEntry) {
        out.append(entry.encode()).append('\n')
        out.flush()
    }

    override fun close() = out.close()
}

data class JournalInfo(
    val id: String,
    val file: File,
    val title: String,
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val stats: Map<String, Long>,
    val rolledBackAt: Long?,
    val purgedAt: Long?,
    val entryCount: Int,
) {
    val canRollback: Boolean get() = rolledBackAt == null && entryCount > 0
    fun stat(key: String): Long = stats[key] ?: 0L
}

/** Journals live in app-private storage; quarantined bytes live next to the data on shared storage. */
class JournalStore(val directory: File) {
    init {
        directory.mkdirs()
    }

    fun newId(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return "$stamp-" + Random.nextInt(0x1000, 0xffff).toString(16)
    }

    fun fileFor(id: String) = File(directory, "$id.tsv")

    fun entries(id: String): List<JournalEntry> = try {
        fileFor(id).readLines().filter { it.isNotEmpty() && !it.startsWith('#') }.mapNotNull(JournalEntry::decode)
    } catch (_: IOException) {
        emptyList()
    }

    fun appendMeta(id: String, key: String, value: String) {
        JournalWriter(fileFor(id)).use { it.meta(key, value) }
    }

    fun info(id: String): JournalInfo? = parse(fileFor(id))

    fun list(): List<JournalInfo> =
        (directory.listFiles { f -> f.isFile && f.name.endsWith(".tsv") } ?: emptyArray())
            .mapNotNull(::parse)
            .sortedByDescending { it.startedAt }

    private fun parse(file: File): JournalInfo? {
        if (!file.isFile) return null
        val meta = HashMap<String, String>()
        var entries = 0
        try {
            file.forEachLine { line ->
                if (line.startsWith('#')) {
                    val key = line.substring(1).substringBefore('\t')
                    meta[key] = line.substringAfter('\t', "")
                } else if (line.isNotEmpty()) {
                    entries++
                }
            }
        } catch (_: IOException) {
            return null
        }
        val stats = meta["stats"].orEmpty().split(' ').mapNotNull {
            val k = it.substringBefore('=', "")
            val v = it.substringAfter('=', "").toLongOrNull()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()
        return JournalInfo(
            id = file.name.removeSuffix(".tsv"),
            file = file,
            title = meta["title"] ?: "Steward run",
            kind = meta["kind"] ?: "",
            startedAt = meta["started"]?.toLongOrNull() ?: file.lastModified(),
            finishedAt = meta["finished"]?.toLongOrNull(),
            stats = stats,
            rolledBackAt = meta["rolledback"]?.toLongOrNull(),
            purgedAt = meta["purged"]?.toLongOrNull(),
            entryCount = entries,
        )
    }
}
