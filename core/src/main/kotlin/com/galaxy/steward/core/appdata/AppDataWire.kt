package com.galaxy.steward.core.appdata

import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.RollbackSummary

/**
 * Line-based TSV messages between the app and its Shizuku helper process. Requests go in as one string; results
 * stream back line by line so progress and journal entries arrive while the helper is still working. Paths with
 * tabs or newlines are never produced by the scanner, and free text has them stripped.
 */
object AppDataWire {
    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    // ------------------------------------------------------------------ requests

    data class ScanRequest(
        val rootPath: String,
        val installed: Set<String>,
        val options: AppScanOptions,
        val ownPackage: String?,
        val areas: Set<AppArea>,
    )

    fun encodeScanRequest(r: ScanRequest): String = buildString {
        append("root\t").append(r.rootPath).append('\n')
        append("own\t").append(r.ownPackage ?: "-").append('\n')
        append("areas\t").append(r.areas.joinToString(",") { it.dir }).append('\n')
        with(r.options) {
            append("opt\t$now\t$logAgeDays\t$tempAgeDays\t$recentGuardMinutes\t$largeFileBytes\t$maxDepth\n")
        }
        r.installed.forEach { append("pkg\t").append(it).append('\n') }
    }

    fun decodeScanRequest(text: String): ScanRequest {
        var root = ""
        var own: String? = null
        var areas = AppArea.entries.toSet()
        var options = AppScanOptions()
        val installed = HashSet<String>()
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            when (p[0]) {
                "root" -> root = p.getOrElse(1) { "" }
                "own" -> own = p.getOrNull(1)?.takeIf { it != "-" }
                "areas" -> areas = p.getOrElse(1) { "" }.split(',').mapNotNull(AppArea::ofDir).toSet()
                "opt" -> if (p.size >= 7) {
                    options = AppScanOptions(p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toLong(), p[6].toInt())
                }
                "pkg" -> p.getOrNull(1)?.takeIf(AppPolicy::isPackageName)?.let(installed::add)
            }
        }
        require(root.startsWith("/")) { "Missing storage root" }
        return ScanRequest(root, installed, options, own, areas)
    }

    data class ApplyRequest(
        val rootPath: String,
        val runId: String,
        val title: String,
        val ownPackage: String?,
        val installedNow: Set<String>?,
        val items: List<AppJunkItem>,
    )

    fun encodeApplyRequest(r: ApplyRequest): String = buildString {
        append("root\t").append(r.rootPath).append('\n')
        append("run\t").append(r.runId).append('\n')
        append("title\t").append(clean(r.title)).append('\n')
        append("own\t").append(r.ownPackage ?: "-").append('\n')
        if (r.installedNow != null) {
            append("installed-known\n")
            r.installedNow.forEach { append("pkg\t").append(it).append('\n') }
        }
        r.items.forEach { append(encodeItem(it)) }
    }

    fun decodeApplyRequest(text: String): ApplyRequest {
        var root = ""
        var runId = ""
        var title = "App folder clean-up"
        var own: String? = null
        var known = false
        val installed = HashSet<String>()
        val items = ItemCollector()
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            when (p[0]) {
                "root" -> root = p.getOrElse(1) { "" }
                "run" -> runId = p.getOrElse(1) { "" }
                "title" -> title = p.getOrElse(1) { title }
                "own" -> own = p.getOrNull(1)?.takeIf { it != "-" }
                "installed-known" -> known = true
                "pkg" -> p.getOrNull(1)?.takeIf(AppPolicy::isPackageName)?.let(installed::add)
                else -> items.accept(p)
            }
        }
        require(root.startsWith("/") && runId.matches(Regex("[A-Za-z0-9-]+"))) { "Malformed request" }
        return ApplyRequest(root, runId, title, own, if (known) installed else null, items.items())
    }

    // ------------------------------------------------------------------ folder listings (the browser)

    data class ListRequest(val rootPath: String, val ownPackage: String?, val path: String)

    fun encodeListRequest(r: ListRequest): String =
        "root\t${r.rootPath}\nown\t${r.ownPackage ?: "-"}\npath\t${r.path}\n"

    fun decodeListRequest(text: String): ListRequest {
        var root = ""
        var own: String? = null
        var path = ""
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            when (p[0]) {
                "root" -> root = p.getOrElse(1) { "" }
                "own" -> own = p.getOrNull(1)?.takeIf { it != "-" }
                "path" -> path = p.getOrElse(1) { "" }
            }
        }
        require(root.startsWith("/") && path.startsWith("$root/")) { "Malformed request" }
        return ListRequest(root, own, path)
    }

    fun encodeListing(l: AppFolderListing): Sequence<String> = sequence {
        yield("B\t${l.path}\t${l.area.dir}\t${l.packageName}\t${if (l.protected) 1 else 0}\t${l.listedAt}\t${l.hidden}")
        l.entries.forEach { e ->
            yield("F\t${e.name}\t${if (e.isDirectory) "d" else "f"}\t${e.bytes}\t${e.files}\t${e.mtime}\t${if (e.partial) 1 else 0}\t${e.locked?.let(::clean) ?: "-"}")
        }
    }

    /** Parses a streamed listing; unknown lines (errors, the end marker) are handed to [other]. */
    fun decodeListing(lines: Sequence<String>, other: (List<String>) -> Unit = {}): AppFolderListing? {
        var head: List<String>? = null
        val entries = ArrayList<AppFolderEntry>()
        lines.forEach { line ->
            val p = line.split('\t')
            when {
                p[0] == "B" && p.size >= 7 -> head = p
                p[0] == "F" && p.size >= 8 && head != null -> entries += AppFolderEntry(
                    name = p[1],
                    path = head!![1] + "/" + p[1],
                    isDirectory = p[2] == "d",
                    bytes = p[3].toLong(),
                    files = p[4].toInt(),
                    mtime = p[5].toLong(),
                    locked = p[7].takeIf { it != "-" },
                    partial = p[6] == "1",
                )
                else -> other(p)
            }
        }
        val h = head ?: return null
        val area = AppArea.ofDir(h[2]) ?: return null
        return AppFolderListing(h[1], area, h[3], h[4] == "1", h[5].toLong(), entries, h[6].toInt())
    }

    // ------------------------------------------------------------------ items and reports

    fun encodeItem(item: AppJunkItem): String = buildString {
        append("I\t${item.id}\t${item.kind.name}\t${item.packageName}\t${item.area.dir}\t${item.bytes}\t${item.fileCount}\t${item.cutoff}\t${clean(item.note)}\n")
        item.targets.forEach { t -> append("T\t${item.id}\t${t.path}\t${if (t.isDirectory) 1 else 0}\t${t.size}\t${t.mtime}\n") }
    }

    private class ItemCollector {
        private val heads = LinkedHashMap<String, List<String>>()
        private val targets = HashMap<String, MutableList<AppTarget>>()

        /** Returns true when [p] was an item or target line. */
        fun accept(p: List<String>): Boolean = when {
            p[0] == "I" && p.size >= 9 -> {
                heads[p[1]] = p
                true
            }
            p[0] == "T" && p.size >= 6 -> {
                targets.getOrPut(p[1]) { ArrayList() } += AppTarget(p[2], p[3] == "1", p[4].toLong(), p[5].toLong())
                true
            }
            else -> false
        }

        fun items(): List<AppJunkItem> = heads.values.mapNotNull { p ->
            val kind = runCatching { AppJunkKind.valueOf(p[2]) }.getOrNull() ?: return@mapNotNull null
            val area = AppArea.ofDir(p[4]) ?: return@mapNotNull null
            AppJunkItem(p[1], kind, p[3], area, targets[p[1]].orEmpty(), p[5].toLong(), p[6].toInt(), p[7].toLong(), p[8])
        }
    }

    fun encodeReport(r: AppDataReport): Sequence<String> = sequence {
        yield("H\t${r.scannedAt}\t${r.areas.joinToString(",") { it.dir }}")
        r.usage.forEach { yield("U\t${it.packageName}\t${it.area.dir}\t${it.bytes}\t${it.files}\t${if (it.installed) 1 else 0}\t${if (it.protected) 1 else 0}") }
        r.items.forEach { yieldAll(encodeItem(it).trimEnd('\n').split('\n')) }
        r.largeFiles.forEach { yield("L\t${it.packageName}\t${it.path}\t${it.size}\t${it.mtime}") }
        r.unreadable.forEach { yield("X\t${clean(it)}") }
    }

    /** Parses a streamed report; unknown lines (progress, errors) are handed to [other]. */
    fun decodeReport(lines: Sequence<String>, other: (List<String>) -> Unit = {}): AppDataReport {
        var scannedAt = 0L
        var areas = emptySet<AppArea>()
        val usage = ArrayList<AppAreaUsage>()
        val large = ArrayList<AppLargeFile>()
        val unreadable = ArrayList<String>()
        val items = ItemCollector()
        lines.forEach { line ->
            val p = line.split('\t')
            when {
                p[0] == "H" && p.size >= 3 -> {
                    scannedAt = p[1].toLong()
                    areas = p[2].split(',').mapNotNull(AppArea::ofDir).toSet()
                }
                p[0] == "U" && p.size >= 7 -> AppArea.ofDir(p[2])?.let {
                    usage += AppAreaUsage(p[1], it, p[3].toLong(), p[4].toInt(), p[5] == "1", p[6] == "1")
                }
                p[0] == "L" && p.size >= 5 -> large += AppLargeFile(p[1], p[2], p[3].toLong(), p[4].toLong())
                p[0] == "X" && p.size >= 2 -> unreadable += p[1]
                items.accept(p) -> Unit
                else -> other(p)
            }
        }
        return AppDataReport(scannedAt, areas, usage, items.items(), large, unreadable)
    }

    // ------------------------------------------------------------------ progress, journal, results

    fun progress(done: Int, total: Int, detail: String) = "P\t$done\t$total\t${clean(detail)}"

    fun journalMeta(key: String, value: String) = "M\t$key\t${clean(value)}"

    fun journalEntry(e: JournalEntry) = "J\t" + e.encode()

    fun error(message: String) = "E\t${clean(message)}"

    const val END = "Z"

    fun encodeSummary(s: ExecutionSummary): String = listOf(
        "S", s.runId, s.quarantined, s.removedDirs, s.skipped, s.failed, s.bytesFreed, s.bytesQuarantined, s.cleared,
        s.completedItemIds.joinToString(","), s.partialItemIds.joinToString(","),
    ).joinToString("\t")

    fun decodeSummary(p: List<String>, messages: List<String>, changed: List<String>): ExecutionSummary = ExecutionSummary(
        runId = p[1],
        completedItemIds = p.getOrElse(9) { "" }.split(',').filter { it.isNotEmpty() }.toSet(),
        partialItemIds = p.getOrElse(10) { "" }.split(',').filter { it.isNotEmpty() }.toSet(),
        moved = 0,
        deduped = 0,
        quarantined = p[2].toInt(),
        removedDirs = p[3].toInt(),
        skipped = p[4].toInt(),
        failed = p[5].toInt(),
        bytesFreed = p[6].toLong(),
        bytesQuarantined = p[7].toLong(),
        bytesMoved = 0,
        messages = messages,
        changedPaths = changed,
        cleared = p[8].toInt(),
    )

    fun encodeRollback(s: RollbackSummary): String = "R\t${s.restored}\t${s.skipped}\t${s.failed}"

    fun message(text: String) = "N\t${clean(text)}"

    fun changedPath(path: String) = "C\t$path"

    fun encodeEntries(entries: List<JournalEntry>): String = entries.joinToString("\n") { it.encode() }

    fun decodeEntries(text: String): List<JournalEntry> = text.lineSequence().mapNotNull(JournalEntry::decode).toList()
}
