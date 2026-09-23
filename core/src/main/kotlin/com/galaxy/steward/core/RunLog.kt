package com.galaxy.steward.core

import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.RollbackSummary
import com.galaxy.steward.core.plan.ScanReport
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
            "skipped ${count(s.skipped)}, failed ${count(s.failed)}"

    fun rolledBack(title: String, s: RollbackSummary, millis: Long): String =
        "undo \"$title\" done in ${seconds(millis)}: restored ${count(s.restored)}, skipped ${count(s.skipped)}, failed ${count(s.failed)}"
}
