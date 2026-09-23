package com.galaxy.steward.core.termux

/**
 * Termux keeps its home and packages in its own private folder, which no other app can read. The steward
 * therefore sends Termux a small audited script ([TermuxScript]) through Termux's RUN_COMMAND bridge and parses
 * the tab-separated records it prints back. Everything below is plain parsing and presentation.
 */
object TermuxScript {
    const val PACKAGE = "com.termux"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"

    val text: String by lazy {
        requireNotNull(TermuxScript::class.java.getResourceAsStream("termux-steward.sh")) { "termux-steward.sh missing" }
            .use { it.readBytes().decodeToString() }
    }

    /** Arguments for `bash`: the script is passed inline so nothing has to be installed inside Termux. */
    fun arguments(mode: String, outPath: String?, targets: List<String> = emptyList()): List<String> = buildList {
        add("-c")
        add(text)
        add("steward")
        add(mode)
        if (outPath != null) {
            add("--out")
            add(outPath)
        }
        addAll(targets)
    }
}

enum class TermuxGroup(val title: String, val description: String) {
    SAFE("Downloads and temp files", "Package downloads, APT's derived indexes, and temp files older than a week."),
    DEV("Developer caches", "Download caches of npm, pip, uv, Poetry, Go, Cargo, rustup, Bun and the Android SDK. Tools re-download what they need."),
    PROOT("Linux distributions", "Package caches and old temp files inside proot distributions that are not running."),
    BUILD("Build outputs", "Folders Git ignores in your projects (build, node_modules, target, __pycache__...). Rebuilt on the next build or install."),
    OTHER("Other caches", "Everything else in ~/.cache. Often model or download caches that are slow to fetch again - review first."),
}

data class TermuxTargetInfo(val id: String, val title: String, val group: TermuxGroup, val defaultSelected: Boolean, val note: String = "")

object TermuxCatalog {
    val targets: Map<String, TermuxTargetInfo> = listOf(
        TermuxTargetInfo("apt-archives", "Downloaded packages (.deb)", TermuxGroup.SAFE, true),
        TermuxTargetInfo("apt-pkgcache", "APT package index cache", TermuxGroup.SAFE, true, "Rebuilt automatically"),
        TermuxTargetInfo("termux-tmp", "Termux temp files", TermuxGroup.SAFE, true, "Only files older than 7 days"),
        TermuxTargetInfo("var-tmp", "Termux var/tmp", TermuxGroup.SAFE, true, "Only files older than 7 days"),
        TermuxTargetInfo("npm-logs", "npm logs", TermuxGroup.SAFE, true, "Only logs older than 7 days"),
        TermuxTargetInfo("termux-app-cache", "Termux app cache", TermuxGroup.SAFE, true),
        TermuxTargetInfo("apt-lists", "APT package lists", TermuxGroup.SAFE, false, "Run pkg update before your next install"),
        TermuxTargetInfo("npm-cache", "npm cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("pip-cache", "pip cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("uv-cache", "uv cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("poetry-cache", "Poetry cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("go-build", "Go build cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("go-mod-download", "Go module downloads", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-registry-cache", "Cargo crate downloads", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-registry-src", "Cargo unpacked crate sources", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-registry-index", "Cargo registry index", TermuxGroup.DEV, true),
        TermuxTargetInfo("rustup-downloads", "rustup downloads", TermuxGroup.DEV, true),
        TermuxTargetInfo("rustup-tmp", "rustup temp files", TermuxGroup.DEV, true),
        TermuxTargetInfo("bun-cache", "Bun install cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("android-cache", "Android SDK cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("gradle-caches", "Gradle caches", TermuxGroup.DEV, false, "Large and slow to download again"),
        TermuxTargetInfo("proot-cache", "Distro package cache", TermuxGroup.PROOT, true),
        TermuxTargetInfo("proot-tmp", "Distro temp files", TermuxGroup.PROOT, true, "Only files older than 7 days"),
        TermuxTargetInfo("build", "Build output", TermuxGroup.BUILD, false),
        TermuxTargetInfo("other-cache", "Cache folder", TermuxGroup.OTHER, false),
    ).associateBy { it.id }

    /** Targets whose records carry a path the script must re-validate (`id=path`). */
    val PATH_TARGETS = setOf("other-cache", "proot-cache", "proot-tmp", "build")
}

data class TermuxItem(
    /** What to hand back to the script: `apt-archives`, or `build=/data/.../node_modules`. */
    val spec: String,
    val targetId: String,
    val title: String,
    val group: TermuxGroup,
    val path: String,
    val bytes: Long,
    val files: Int,
    val defaultSelected: Boolean,
    val note: String,
)

data class TermuxUsage(val path: String, val bytes: Long)

data class TermuxRootfs(val path: String, val bytes: Long, val active: Boolean)

data class TermuxLargeFile(val path: String, val size: Long, val mtime: Long)

data class TermuxReport(
    val home: String,
    val prefix: String,
    val prootActive: Boolean,
    val usage: List<TermuxUsage>,
    val items: List<TermuxItem>,
    val rootfs: List<TermuxRootfs>,
    val largeFiles: List<TermuxLargeFile>,
    val warnings: List<String>,
    /** Known cache paths that were skipped because they are symlinked or out of bounds. */
    val unsafe: List<String>,
) {
    val reclaimableBytes: Long get() = items.sumOf { it.bytes }

    /** Total size of the Termux files folder (home + packages). */
    val totalBytes: Long get() = usage.firstOrNull()?.bytes ?: 0L

    fun without(specs: Set<String>): TermuxReport = copy(items = items.filterNot { it.spec in specs })
}

data class TermuxCleanResult(val targetId: String, val status: String, val before: Long, val after: Long, val path: String, val note: String) {
    val freed: Long get() = if (status == "CLEARED" || status == "PARTIAL") (before - after).coerceAtLeast(0) else 0
    val ok: Boolean get() = status == "CLEARED" || status == "NO_CHANGE"
}

data class TermuxCleanSummary(val results: List<TermuxCleanResult>) {
    val freed: Long get() = results.sumOf { it.freed }
    val cleared: Int get() = results.count { it.status == "CLEARED" || it.status == "PARTIAL" }
    val skipped: List<TermuxCleanResult> get() = results.filter { it.status.startsWith("SKIP") || it.status == "PARTIAL" }
}

class TermuxException(message: String) : Exception(message)

/** Parses the records printed by termux-steward.sh. */
object TermuxProtocol {
    private data class Parsed(val records: List<List<String>>, val end: String?)

    private fun parse(output: String): Parsed {
        val records = ArrayList<List<String>>()
        var end: String? = null
        output.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val p = line.split('\t')
            if (p[0] == "E") end = p.getOrElse(1) { "" } else records += p
        }
        return Parsed(records, end)
    }

    private fun check(parsed: Parsed) {
        parsed.records.firstOrNull { it[0] == "X" && it.getOrNull(1) == "refused" }?.let {
            throw TermuxException(it.getOrElse(2) { "Termux refused to run the steward" })
        }
        if (parsed.end == null) throw TermuxException("The Termux output was cut short; nothing was assumed")
    }

    fun parseAudit(output: String): TermuxReport {
        val parsed = parse(output)
        check(parsed)
        var home = ""
        var prefix = ""
        var active = false
        val usage = ArrayList<TermuxUsage>()
        val items = ArrayList<TermuxItem>()
        val rootfs = ArrayList<TermuxRootfs>()
        val large = ArrayList<TermuxLargeFile>()
        val warnings = ArrayList<String>()
        val unsafe = ArrayList<String>()
        for (p in parsed.records) {
            when (p[0]) {
                "V" -> if (p.size >= 5) {
                    home = p[2]
                    prefix = p[3]
                    active = p[4] == "1"
                }
                "U" -> if (p.size >= 3) usage += TermuxUsage(p[2], p[1].toLongOrNull() ?: 0)
                "T" -> if (p.size >= 5) {
                    val info = TermuxCatalog.targets[p[1]] ?: continue
                    val path = p[4]
                    val spec = if (info.id in TermuxCatalog.PATH_TARGETS) "${info.id}=$path" else info.id
                    val title = if (info.id in TermuxCatalog.PATH_TARGETS) "${info.title}: ${relative(path, home, prefix)}" else info.title
                    items += TermuxItem(spec, info.id, title, info.group, path, p[2].toLong(), p[3].toInt(), info.defaultSelected, info.note)
                }
                "B" -> if (p.size >= 6) {
                    val artifacts = p[3] == "1"
                    val repo = p[4]
                    val path = p[5]
                    items += TermuxItem(
                        spec = "build=$path",
                        targetId = "build",
                        title = "${repo.substringAfterLast('/')}: ${path.removePrefix("$repo/")}",
                        group = TermuxGroup.BUILD,
                        path = path,
                        bytes = p[1].toLong(),
                        files = p[2].toInt(),
                        defaultSelected = false,
                        note = if (artifacts) "Contains built APKs - copy any you want to keep first" else "Ignored by Git",
                    )
                }
                "R" -> if (p.size >= 4) rootfs += TermuxRootfs(p[3], p[1].toLongOrNull() ?: 0, p[2] == "1")
                "L" -> if (p.size >= 4) large += TermuxLargeFile(p[3], p[1].toLongOrNull() ?: 0, (p[2].toLongOrNull() ?: 0) * 1000)
                "W" -> warnings += p.getOrElse(1) { "" }
                "X" -> if (p.size >= 3) unsafe += p[2]
            }
        }
        // "debian: var/cache/apt/archives" reads better than the full proot-distro path.
        val named = items.map { item ->
            if (item.group != TermuxGroup.PROOT) return@map item
            val r = rootfs.firstOrNull { item.path.startsWith(it.path + "/") } ?: return@map item
            val info = TermuxCatalog.targets.getValue(item.targetId)
            item.copy(title = "${info.title}: ${r.path.substringAfterLast('/')}/${item.path.removePrefix(r.path + "/")}")
        }
        return TermuxReport(home, prefix, active, usage, named.sortedByDescending { it.bytes }, rootfs, large, warnings, unsafe)
    }

    fun parseClean(output: String): TermuxCleanSummary {
        val parsed = parse(output)
        check(parsed)
        val results = parsed.records.filter { it[0] == "D" && it.size >= 6 }.map { p ->
            TermuxCleanResult(p[1], p[2], p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0, p[5], p.getOrElse(6) { "" })
        }
        return TermuxCleanSummary(results)
    }

    /** "~/.cache/huggingface" or "$PREFIX/var/..." style display paths. */
    fun relative(path: String, home: String, prefix: String): String = when {
        home.isNotEmpty() && path.startsWith("$home/") -> "~/" + path.removePrefix("$home/")
        prefix.isNotEmpty() && path.startsWith("$prefix/") -> "\$PREFIX/" + path.removePrefix("$prefix/")
        else -> path
    }
}
