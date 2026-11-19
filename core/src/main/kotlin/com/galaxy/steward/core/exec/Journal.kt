package com.galaxy.steward.core.exec

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Journal actions. Every mutation is appended (and flushed) only after it succeeded and was verified, so a
 * journal is always a truthful, replayable record - the Android equivalent of the Termux rollback manifests.
 *
 * The changes undo depends on are also written ahead, as [PENDING] lines: Android can stop the app between a change and
 * its line (swiped away, low memory, an update), and an item moved into the quarantine without a line could not be
 * undone and would go when the quarantine is emptied. [settle] reads such a journal as it happened.
 */
object JournalAction {
    const val MKDIR = "MKDIR"
    const val MOVED = "MOVED"
    const val MOVED_DIR = "MOVED_DIR"
    const val DEDUPED = "DEDUPED"
    const val DEDUPED_QUARANTINED = "DEDUPED_QUARANTINED"
    const val QUARANTINED = "QUARANTINED"
    const val RMDIR = "RMDIR"

    /** Regenerable data (caches, logs, temp files) deleted for good; `b` holds `files=<count>`. Not undoable. */
    const val PURGED = "PURGED"

    /** A zip `a` made from folder `b` (size and SHA-256 of the zip): undo deletes the zip if it is unchanged. */
    const val PACKED = "PACKED"

    /** A folder `a` in shared storage moved into Termux as `b`: undone through Termux, not by [RollbackEngine]. */
    const val RELOCATED = "RELOCATED"

    /** Actions [RollbackEngine] can revert. */
    val RESTORABLE = setOf(MOVED, MOVED_DIR, DEDUPED, DEDUPED_QUARANTINED, QUARANTINED, RMDIR, PACKED, RELOCATED)

    /** Prefix of a line written just before the change it names ("PENDING:QUARANTINED"); see [settle]. */
    const val PENDING = "PENDING:"

    /** Changes written ahead of time. */
    val AHEAD = setOf(MOVED, MOVED_DIR, DEDUPED, DEDUPED_QUARANTINED, QUARANTINED, PACKED)

    fun pending(action: String): String = PENDING + action

    /** Prefix of a line saying a change written ahead was not made after all, the way it was announced. */
    const val ABANDONED = "ABANDONED:"

    fun abandoned(action: String): String = ABANDONED + action

    /**
     * [entries] as they happened. A line written ahead whose change was journaled too says nothing more and goes. One
     * whose own line never came (the app was stopped in between) becomes that change if [happened] finds it in the files
     * (see [RollbackEngine.happened]); otherwise the change was never made, and it goes too.
     */
    fun settle(entries: List<JournalEntry>, happened: (JournalEntry) -> Boolean): List<JournalEntry> {
        if (entries.none { it.action.startsWith(PENDING) }) return entries
        val out = ArrayList<JournalEntry?>(entries.size)
        val open = HashMap<Triple<String, String, String>, ArrayDeque<Int>>()
        for (e in entries) {
            if (e.action.startsWith(PENDING)) {
                val done = e.copy(action = e.action.removePrefix(PENDING))
                open.getOrPut(Triple(done.action, done.a, done.b)) { ArrayDeque() }.addLast(out.size)
                out += done
            } else if (e.action.startsWith(ABANDONED)) {
                // A folder rename that couldn't be done, its files moved one by one instead (journaled each).
                open[Triple(e.action.removePrefix(ABANDONED), e.a, e.b)]?.removeFirstOrNull()?.let { out[it] = null }
            } else {
                open[Triple(e.action, e.a, e.b)]?.removeFirstOrNull()?.let { out[it] = null }
                out += e
            }
        }
        // Lines still open are changes without their own line: kept where they stand if they happened. One whose new
        // place a later line put something inside never happened: that place was made for the later one (a folder whose
        // move failed in a journal from before abandoned lines were written, then a file of it quarantined).
        for (index in open.values.flatten()) {
            val e = out[index] ?: continue
            val madeForLater = e.b.isNotEmpty() && (index + 1 until out.size).any { out[it]?.b?.startsWith(e.b + "/") == true }
            if (e.action !in AHEAD || madeForLater || !happened(e)) out[index] = null
        }
        return out.filterNotNull()
    }
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

/** Where executors record what they did: a journal file, or a stream back to the app from the Shizuku helper. */
interface JournalSink {
    fun meta(key: String, value: String)
    fun entry(entry: JournalEntry)
}

/** A journal a run writes and then closes: a [JournalWriter], or in tests one that fails on purpose. */
interface JournalOutput : JournalSink, Closeable

class JournalWriter(val file: File) : JournalOutput {
    private val out: BufferedWriter

    init {
        file.parentFile?.mkdirs()
        // A journal whose last line was cut off (the app stopped mid-write) gets a line break first, so what is added now
        // isn't glued to it and lost with it.
        val torn = file.length() > 0 && try {
            RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
        } catch (_: IOException) {
            false
        }
        out = BufferedWriter(FileWriter(file, true))
        if (torn) {
            out.append('\n')
            out.flush()
        }
    }

    override fun meta(key: String, value: String) {
        out.append('#').append(key).append('\t').append(value.replace('\t', ' ').replace('\n', ' ')).append('\n')
        out.flush()
    }

    override fun entry(entry: JournalEntry) {
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
    /** Entries that undo can revert (everything except permanent cache and log clean-ups). */
    val restorableCount: Int = entryCount,
) {
    val canRollback: Boolean get() = rolledBackAt == null && restorableCount > 0
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

    fun entries(id: String): List<JournalEntry> = readEntries(id) ?: emptyList()

    /** The run's entries, or null when its journal is missing or can't be read: then nothing about it is known. */
    fun readEntries(id: String): List<JournalEntry>? = try {
        fileFor(id).readLines().filter { it.isNotEmpty() && !it.startsWith('#') }.mapNotNull(JournalEntry::decode)
    } catch (_: IOException) {
        null
    }

    fun appendMeta(id: String, key: String, value: String) {
        JournalWriter(fileFor(id)).use { it.meta(key, value) }
    }

    fun info(id: String): JournalInfo? = cached(fileFor(id))

    fun list(): List<JournalInfo> =
        (directory.listFiles { f -> f.isFile && f.name.endsWith(".tsv") } ?: emptyArray())
            .mapNotNull(::cached)
            .sortedByDescending { it.startedAt }

    /**
     * Journals only ever grow, so one whose size and time are unchanged reads as before. History refreshes on every
     * return to the app, and read every line of every journal each time (megabytes after a year of weekly upkeep).
     */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>, JournalInfo>>()

    private fun cached(file: File): JournalInfo? {
        if (!file.isFile) return null
        val stamp = file.length() to file.lastModified()
        parsed[file.name]?.takeIf { it.first == stamp }?.let { return it.second }
        return parse(file)?.also { parsed[file.name] = stamp to it }
    }

    private fun parse(file: File): JournalInfo? {
        if (!file.isFile) return null
        val meta = HashMap<String, String>()
        val lines = ArrayList<JournalEntry>()
        try {
            file.forEachLine { line ->
                if (line.startsWith('#')) {
                    val key = line.substring(1).substringBefore('\t')
                    meta[key] = line.substringAfter('\t', "")
                } else if (line.isNotEmpty()) {
                    JournalEntry.decode(line)?.let(lines::add)
                }
            }
        } catch (_: IOException) {
            return null
        }
        // Changes written ahead whose own line never came count as done here; undo checks the files.
        val settled = JournalAction.settle(lines) { true }
        val entries = settled.size
        val restorable = settled.count { it.action in JournalAction.RESTORABLE }
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
            restorableCount = restorable,
        )
    }
}
