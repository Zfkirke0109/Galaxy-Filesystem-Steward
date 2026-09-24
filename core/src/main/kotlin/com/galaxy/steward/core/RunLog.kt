package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.RollbackSummary
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.termux.TermuxReport
import java.util.Locale

/**
 * One-line summaries of what the steward did, for the device log: a logcat export then shows every run's outcome
 * and timing. Counts, sizes and durations only, never file names.
 */
object RunLog {
    fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

    private fun count(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    /** [phases] lists how long each scan phase took, in the order they ran. */
    fun scan(report: ScanReport, phases: List<Pair<ScanPhase, Long>> = emptyList()): String = buildString {
        append("scan done in ").append(seconds(report.finishedAt - report.startedAt)).append(": ")
        append(count(report.summary.totalFiles)).append(" files, ").append(report.summary.totalBytes.humanBytes())
        append("; duplicate files ").append(count(report.duplicates.size))
        append(" (").append(report.duplicates.sumOf { it.reclaimBytes }.humanBytes()).append(')')
        append(", duplicate folders ").append(count(report.folderDuplicates.size))
        append(" (").append(report.folderDuplicates.sumOf { it.reclaimBytes }.humanBytes()).append(')')
        append(", merges ").append(count(report.folderMerges.size))
        append(", clutter ").append(count(report.junk.size)).append(" (").append(report.junkBytes.humanBytes()).append(')')
        append(", to organize ").append(count(report.organize.size))
        append(", to optimize ").append(count(report.optimize.size))
        if (phases.isNotEmpty()) {
            append("; phases ")
            append(phases.joinToString(", ") { (phase, millis) -> "${phase.name.lowercase()} ${seconds(millis)}" })
        }
    }

    fun applied(title: String, kind: String, s: ExecutionSummary, millis: Long): String =
        "run \"$title\" ($kind) done in ${seconds(millis)}: moved ${count(s.moved)} (${s.bytesMoved.humanBytes()}), " +
            "deduplicated ${count(s.deduped)}, quarantined ${count(s.quarantined)} (${s.bytesQuarantined.humanBytes()}), " +
            "cleared ${count(s.cleared)}, removed ${count(s.removedDirs)} empty folders, freed ${s.bytesFreed.humanBytes()}; " +
            "skipped ${count(s.skipped)}, failed ${count(s.failed)}" + reasons(s.reasons)

    /** "; why: source is gone 1,685, protected (pinned folder) 3": the most common reasons first, at most five. */
    fun reasons(reasons: Map<String, Int>): String {
        if (reasons.isEmpty()) return ""
        val top = reasons.entries.sortedByDescending { it.value }.take(5)
        val rest = reasons.size - top.size
        return "; why: " + top.joinToString(", ") { "${it.key.replaceFirstChar(Char::lowercase)} ${count(it.value)}" } +
            if (rest > 0) " and $rest more" else ""
    }

    /**
     * Where Termux's space goes (home, packages, proot distributions) and what the audit can clean, by group. Folder
     * sizes only: the report itself, with paths, stays on the phone.
     */
    fun termux(report: TermuxReport, millis: Long): String = buildString {
        append("Termux audit done in ").append(seconds(millis)).append(": Termux uses ").append(report.totalBytes.humanBytes())
        val home = report.usage.firstOrNull { it.path == report.home }?.bytes
        val prefix = report.usage.firstOrNull { it.path == report.prefix }?.bytes
        val distros = report.rootfs.sumOf { it.bytes }
        val distrosInPrefix = report.rootfs.filter { it.path.startsWith(report.prefix + "/") }.sumOf { it.bytes }
        val parts = buildList {
            home?.let { add("home ${it.humanBytes()}") }
            prefix?.let { add("packages ${(it - distrosInPrefix).coerceAtLeast(0).humanBytes()}") }
            if (report.rootfs.isNotEmpty()) add("${report.rootfs.size} proot ${if (report.rootfs.size == 1) "distro" else "distros"} ${distros.humanBytes()}")
        }
        if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(')')
        append("; ").append(count(report.items.size)).append(" cleanable items, ").append(report.reclaimableBytes.humanBytes())
        val groups = report.items.groupBy { it.group }.toSortedMap()
        if (groups.isNotEmpty()) {
            append(" (").append(groups.entries.joinToString(", ") { (g, items) -> "${g.name.lowercase()} ${items.sumOf { it.bytes }.humanBytes()}" }).append(')')
        }
        append(", ").append(count(report.unsafe.size)).append(" unsafe paths skipped")
    }

    fun rolledBack(title: String, s: RollbackSummary, millis: Long): String =
        "undo \"$title\" done in ${seconds(millis)}: restored ${count(s.restored)}, skipped ${count(s.skipped)}, failed ${count(s.failed)}"
}
