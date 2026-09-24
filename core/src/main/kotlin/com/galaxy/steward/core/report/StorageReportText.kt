package com.galaxy.steward.core.report

import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.RunLog
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.termux.TermuxReport
import java.util.Locale

/** Why a folder is treated the way it is, in a few words: "project", "source code", "pinned by you". Null for plain folders. */
fun DirNode.stewardNote(): String? = buildList {
    if (hasFlag(NodeFlags.PROJECT_ROOT) || hasFlag(NodeFlags.GIT_DIR)) add("project")
    if (hasFlag(NodeFlags.CODE_TREE)) add("source code")
    when (zone) {
        Zone.USER_PROTECTED -> add("pinned by you")
        Zone.APP_OWNED -> add("app data")
        Zone.STEWARD -> add("steward")
        else -> Unit
    }
    if (hasFlag(NodeFlags.UNREADABLE)) add("unreadable")
    if (hidden) add("hidden")
    if (subtreeHas(NodeFlags.HAS_CREDENTIAL)) add("holds keys")
    if (totalFiles == 0 && !hasFlag(NodeFlags.UNREADABLE)) add("empty")
}.takeIf { it.isNotEmpty() }?.joinToString(", ")

/** Top-level folders Android creates and recreates when they are missing; empty ones are just noise in a listing. */
fun DirNode.isEmptyStandardFolder(): Boolean = depth == 1 && totalFiles == 0 && name in SafetyPolicy.STANDARD_TOP_DIRS

/**
 * A plain-text map of shared storage from the last scan and of Termux from its last audit, for a person asking why
 * storage looks the way it does: every top-level folder, then the tree down to [depth] levels for folders of at least
 * [minBytes] or [minFiles] files, each with its size and how the steward treats it, followed by what the scan suggests
 * and what it left alone. It names folders, and the largest files inside Termux; nothing is read from inside files.
 */
object StorageReportText {
    fun render(
        scan: ScanReport?,
        termux: TermuxReport?,
        header: List<String>,
        depth: Int = 4,
        minBytes: Long = 50 * MIB,
        minFiles: Int = 1_000,
        perFolder: Int = 25,
    ): String = buildString {
        header.forEach(::appendLine)
        appendLine()
        if (scan == null) {
            appendLine("Shared storage: not scanned yet. Run a scan, then export again.")
        } else {
            folders(scan, depth, minBytes, minFiles, perFolder)
            suggestions(scan)
        }
        if (termux != null) termux(termux)
    }

    private fun StringBuilder.folders(scan: ScanReport, depth: Int, minBytes: Long, minFiles: Int, perFolder: Int) {
        val root = scan.tree.root
        appendLine("SHARED STORAGE: ${files(root.totalFiles)}, ${root.totalBytes.humanBytes()} (${root.path})")
        appendLine("Folders by size. Below the top level only folders of ${minBytes.humanBytes()} or ${files(minFiles)} and more, $depth levels deep.")
        appendLine()
        val emptyStandard = root.dirs.filter { it.isEmptyStandardFolder() }.map { it.name }
        fun visit(dir: DirNode, level: Int) {
            val children = dir.dirs.filter { !it.isEmptyStandardFolder() }.sortedByDescending { it.totalBytes }
            val shown = if (level == 0) children else children.filter { it.totalBytes >= minBytes || it.totalFiles >= minFiles }.take(perFolder)
            for (child in shown) {
                val note = child.stewardNote()?.let { "  [$it]" } ?: ""
                appendLine("${"  ".repeat(level)}${child.name}/  ${child.totalBytes.humanBytes()}, ${files(child.totalFiles)}$note")
                if (level + 1 < depth && !child.hasFlag(NodeFlags.CODE_TREE) && child.zone != Zone.STEWARD) visit(child, level + 1)
            }
            val rest = children.size - shown.size
            if (rest > 0 && level > 0) {
                val restBytes = children.drop(shown.size).sumOf { it.totalBytes }
                appendLine("${"  ".repeat(level)}… and ${count(rest)} smaller ${if (rest == 1) "folder" else "folders"}, ${restBytes.humanBytes()}")
            }
        }
        visit(root, 0)
        if (root.files.isNotEmpty()) appendLine("${files(root.files.size)} loose at the top, ${root.directBytes.humanBytes()}")
        if (emptyStandard.isNotEmpty()) {
            appendLine("Empty Android folders (Android recreates them): ${emptyStandard.sorted().joinToString(", ")}")
        }
        appendLine()
    }

    private fun StringBuilder.suggestions(scan: ScanReport) {
        appendLine("WHAT THE LAST SCAN SUGGESTS")
        appendLine(RunLog.scan(scan))
        scan.junk.groupBy { it.category }.forEach { (category, items) ->
            appendLine("  ${category.title}: ${count(items.size)}, ${items.sumOf { it.bytes }.humanBytes()}${if (category.defaultSelected) "" else " (review)"}")
        }
        if (scan.organize.isNotEmpty()) {
            val (selected, review) = scan.organize.partition { it.defaultSelected }
            appendLine("  To organize: ${count(selected.size)} selected, ${count(review.size)} to review")
            scan.organize.groupBy { it.destinationFolder.removePrefix(scan.tree.rootPath + "/") }.entries
                .sortedByDescending { it.value.size }.take(15)
                .forEach { (dest, moves) -> appendLine("    → $dest: ${count(moves.size)} (${moves.sumOf { it.bytes }.humanBytes()})") }
        }
        scan.optimize.groupBy { it.kind }.forEach { (kind, items) -> appendLine("  ${kind.title}: ${count(items.size)}") }
        if (scan.insights.isNotEmpty()) {
            appendLine()
            appendLine("LEFT ALONE, AND WHY")
            scan.insights.forEach { appendLine("  ${it.title}: ${it.detail}") }
        }
        appendLine()
    }

    private fun StringBuilder.termux(report: TermuxReport) {
        val files = report.home.substringBeforeLast('/')
        fun short(path: String) = when {
            path == report.home || path.startsWith(report.home + "/") -> "~" + path.removePrefix(report.home)
            path.startsWith("$files/") -> path.removePrefix("$files/")
            else -> path
        }
        appendLine("TERMUX (last audit)")
        appendLine(RunLog.termux(report, 0).substringAfter(": "))
        if (report.usage.isNotEmpty()) {
            appendLine("  Where the space goes:")
            report.usage.drop(1).forEach { appendLine("    ${short(it.path)}  ${it.bytes.humanBytes()}") }
        }
        if (report.rootfs.isNotEmpty()) {
            appendLine("  proot distributions:")
            report.rootfs.forEach { appendLine("    ${short(it.path)}  ${it.bytes.humanBytes()}${if (it.active) " (running)" else ""}") }
        }
        if (report.items.isNotEmpty()) {
            appendLine("  Cleanable:")
            report.items.sortedByDescending { it.bytes }.forEach {
                appendLine("    ${it.title}  ${it.bytes.humanBytes()}${if (it.defaultSelected) "" else " (review)"}  ${short(it.path)}")
            }
        }
        if (report.largeFiles.isNotEmpty()) {
            appendLine("  Largest files:")
            report.largeFiles.take(15).forEach { appendLine("    ${short(it.path)}  ${it.size.humanBytes()}") }
        }
        report.warnings.forEach { appendLine("  Note: $it") }
    }

    private fun count(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    private fun files(n: Int): String = "${count(n)} ${if (n == 1) "file" else "files"}"
}
