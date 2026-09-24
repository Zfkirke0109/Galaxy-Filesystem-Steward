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
    SAFE("Downloads and temp files", "Package downloads, downloaded distro images, APT's derived indexes, trash, and temp files and logs older than a week."),
    DEV("Developer caches", "Download caches of npm, npx, pip, uv, Poetry, Yarn, Go, Cargo, rustup, Bun, Gradle and the Android SDK, and Python bytecode. Tools re-download or rebuild what they need."),
    PROOT("Linux distributions", "Package caches and old temp files inside proot distributions that are not running."),
    BUILD("Build outputs", "Folders Git ignores in your projects (build, node_modules, target, .venv...). Rebuilt on the next build or install."),
    OTHER("Other caches", "Everything else in ~/.cache. Often model or download caches that are slow to fetch again - review first."),
    LEFTOVERS(
        "Leftovers",
        "Decompiled apps, APKs, NDKs the phone can't run, and files proot no longer uses. Not caches: review each one.",
    ),
}

data class TermuxTargetInfo(val id: String, val title: String, val group: TermuxGroup, val defaultSelected: Boolean, val note: String = "")

object TermuxCatalog {
    val targets: Map<String, TermuxTargetInfo> = listOf(
        TermuxTargetInfo("apt-archives", "Downloaded packages (.deb)", TermuxGroup.SAFE, true),
        TermuxTargetInfo("apt-pkgcache", "APT package index cache", TermuxGroup.SAFE, true, "Rebuilt automatically"),
        TermuxTargetInfo("termux-tmp", "Termux temp files", TermuxGroup.SAFE, true, "Only files older than 7 days"),
        TermuxTargetInfo("var-tmp", "Termux var/tmp", TermuxGroup.SAFE, true, "Only files older than 7 days"),
        TermuxTargetInfo("termux-var-log", "Termux logs", TermuxGroup.SAFE, true, "Only files older than 7 days"),
        TermuxTargetInfo("proot-dlcache", "Downloaded distro images", TermuxGroup.SAFE, true, "Only needed to install a distribution again"),
        TermuxTargetInfo("trash", "Trash", TermuxGroup.SAFE, true, "Files you already deleted with a trash tool"),
        TermuxTargetInfo("npm-logs", "npm logs", TermuxGroup.SAFE, true, "Only logs older than 7 days"),
        TermuxTargetInfo("termux-app-cache", "Termux app cache", TermuxGroup.SAFE, true),
        TermuxTargetInfo("apt-lists", "APT package lists", TermuxGroup.SAFE, false, "Run pkg update before your next install"),
        TermuxTargetInfo("npm-cache", "npm cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("npx-cache", "npx packages", TermuxGroup.DEV, true, "npx downloads them again when you run them"),
        TermuxTargetInfo("pip-cache", "pip cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("uv-cache", "uv cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("poetry-cache", "Poetry cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("pycache", "Python bytecode caches", TermuxGroup.DEV, true, "Every __pycache__ in your home; Python rebuilds them"),
        TermuxTargetInfo("yarn-cache", "Yarn cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("yarn-berry-cache", "Yarn Berry cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("go-build", "Go build cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("go-mod-download", "Go module downloads", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-registry-cache", "Cargo crate downloads", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-registry-src", "Cargo unpacked crate sources", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-registry-index", "Cargo registry index", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-git-db", "Cargo Git dependencies", TermuxGroup.DEV, true),
        TermuxTargetInfo("cargo-git-checkouts", "Cargo Git checkouts", TermuxGroup.DEV, true),
        TermuxTargetInfo("rustup-downloads", "rustup downloads", TermuxGroup.DEV, true),
        TermuxTargetInfo("rustup-tmp", "rustup temp files", TermuxGroup.DEV, true),
        TermuxTargetInfo("bun-cache", "Bun install cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("android-cache", "Android SDK cache", TermuxGroup.DEV, true),
        TermuxTargetInfo("gradle-daemon-logs", "Gradle daemon logs", TermuxGroup.DEV, true, "Only logs older than 7 days"),
        TermuxTargetInfo("gradle-caches", "Gradle caches", TermuxGroup.DEV, false, "Large and slow to download again"),
        TermuxTargetInfo("claude-versions", "Old Claude Code versions", TermuxGroup.DEV, true, "The version in use and the newest stay"),
        TermuxTargetInfo(
            "koa-archives", "Termux steward archives", TermuxGroup.OTHER, false,
            "Backups the Koa Termux steward script made (~/.storage-autopilot-archives); only needed to undo its old runs",
        ),
        TermuxTargetInfo(
            "home-node-modules", "npm packages in your home", TermuxGroup.DEV, false,
            "~/node_modules: npm install run in the home folder itself; it puts them back",
        ),
        TermuxTargetInfo("decompiled", "Decompiled app", TermuxGroup.LEFTOVERS, false, "apktool or jadx output: decompile the APK again to get it back"),
        TermuxTargetInfo("home-apk", "APK file", TermuxGroup.LEFTOVERS, false, "An installer in your home"),
        TermuxTargetInfo(
            "foreign-ndk", "Android NDK for x86-64 PCs", TermuxGroup.LEFTOVERS, false,
            "Its compilers can't run on the phone's ARM CPU without an emulator such as box64",
        ),
        TermuxTargetInfo(
            "l2s-orphan", "Orphaned hard-link copy", TermuxGroup.LEFTOVERS, false,
            "A file proot kept for hard links that nothing in the distribution points at any more",
        ),
        TermuxTargetInfo("proot-cache", "Distro package cache", TermuxGroup.PROOT, true),
        TermuxTargetInfo("proot-tmp", "Distro temp files", TermuxGroup.PROOT, true, "Only files older than 7 days"),
        TermuxTargetInfo("build", "Build output", TermuxGroup.BUILD, false),
        TermuxTargetInfo("other-cache", "Cache folder", TermuxGroup.OTHER, false),
    ).associateBy { it.id }

    /** Targets whose records carry a path the script must re-validate (`id=path`). */
    val PATH_TARGETS = setOf("other-cache", "proot-cache", "proot-tmp", "build", "decompiled", "home-apk", "foreign-ndk", "l2s-orphan")
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

/** A file or folder of 1 MiB or more in Termux, from the audit's size map. [mtime]: newest change inside (ms), 0 if unknown. */
data class TermuxEntry(val path: String, val bytes: Long, val isDirectory: Boolean, val mtime: Long = 0) {
    val name: String get() = path.substringAfterLast('/')
}

/** Files of one size and SHA-256 in several places in Termux. */
data class TermuxDuplicateSet(val size: Long, val sha256: String, val paths: List<String>) {
    val extraBytes: Long get() = size * (paths.size - 1)
}

/** A bottom-k sketch of a big Termux folder ([com.galaxy.steward.core.dedupe.FolderSketch]). */
data class TermuxSketch(val path: String, val files: Int, val bytes: Long, val hashes: LongArray)

/**
 * A program another package manager installed: global npm packages, pip packages outside dpkg's, cargo installs.
 * Times are milliseconds, 0 when unknown; [lastUsed] and [uses] come from shell history.
 */
data class TermuxProgram(
    val manager: String,
    val name: String,
    val version: String,
    val bytes: Long,
    val installed: Long,
    val lastUsed: Long,
    val uses: Int,
    val protected: Boolean,
    val commands: List<String>,
    val path: String,
) {
    val key: String get() = "$manager:$name"
}

/**
 * A Git repository in Termux, a distribution or shared storage. [bytes] is -1 when not measured (shared storage: the
 * scan knows); [dirty] and [unpushed] are null when not known.
 */
data class TermuxRepo(
    val path: String,
    val bytes: Long,
    val gitBytes: Long,
    val looseBytes: Long,
    val garbageBytes: Long,
    val lastCommit: Long,
    val lastFetch: Long,
    val lastActive: Long,
    val shallow: Boolean,
    val dirty: Boolean?,
    val unpushed: Int?,
    val branch: String,
    val remote: String,
) {
    val name: String get() = path.substringAfterLast('/')

    /** When anything happened in it, as far as Git knows: a commit, a fetch, a checkout. */
    val lastTouched: Long get() = maxOf(lastCommit, lastFetch, lastActive)

    /** git gc has loose objects or garbage worth packing (at least 4 MiB). */
    val packable: Boolean get() = looseBytes + garbageBytes >= 4L * 1024 * 1024

    /** Everything is committed and pushed to a remote: cloning it again gives it back. */
    val onlyACopy: Boolean get() = remote.isNotEmpty() && dirty == false && unpushed == 0

    /** "github.com/owner/repo" from https or ssh remotes, for showing. */
    val remoteLabel: String get() = remote.removeSuffix(".git").replace(Regex("^[a-z+]+://"), "").replace(Regex("^[^@/]+@([^:/]+):"), "$1/")
}

/** An installed Termux package, as dpkg describes it. [manual] is false for packages pulled in as dependencies. */
data class TermuxPackage(
    val name: String,
    val bytes: Long,
    val manual: Boolean,
    /** Termux or the steward can't work without it: never offered for removal. */
    val protected: Boolean,
    val version: String,
    val depends: Set<String>,
    val summary: String,
    /** When it was installed or last updated (ms), 0 when unknown. */
    val installed: Long = 0,
    /** When you last ran one of its commands, from shell history (ms); 0 when not known. */
    val lastUsed: Long = 0,
    /** How often its commands appear in shell history. */
    val uses: Int = 0,
    /** Commands it puts in $PREFIX/bin. */
    val commands: List<String> = emptyList(),
)

/** One package apt would remove: [kind] is requested, dependent (it needs a requested one) or orphan (nothing needs it after). */
data class PlannedRemoval(val name: String, val bytes: Long, val kind: String, val protected: Boolean)

data class TermuxRemovalPlan(val packages: List<PlannedRemoval>, val warnings: List<String>) {
    /** Something Termux needs would go: nothing is removed. */
    val blocked: Boolean get() = packages.any { it.protected }
    fun withoutOrphans(): List<PlannedRemoval> = packages.filter { it.kind != "orphan" }
}

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
    /** Every file and folder of 1 MiB or more, for browsing (only when the report came through shared storage). */
    val entries: List<TermuxEntry> = emptyList(),
    /** Identical files of 8 MiB or more. */
    val duplicates: List<TermuxDuplicateSet> = emptyList(),
    /** Sketches of the biggest folders, for finding near-copies. */
    val sketches: List<TermuxSketch> = emptyList(),
) {
    private val byParent: Map<String, List<TermuxEntry>> by lazy {
        entries.groupBy { it.path.substringBeforeLast('/') }.mapValues { (_, list) -> list.sortedByDescending { it.bytes } }
    }
    private val byPath: Map<String, TermuxEntry> by lazy { entries.associateBy { it.path } }

    /** The files and folders of 1 MiB or more directly inside [path], largest first. */
    fun children(path: String): List<TermuxEntry> = byParent[path].orEmpty()

    fun entry(path: String): TermuxEntry? = byPath[path]

    /** The Termux files folder, the top of the size map. */
    val filesRoot: String get() = home.substringBeforeLast('/')

    /** This report after [freed] (path to bytes freed) went: entries below them drop out, their parents shrink. */
    fun afterDeleting(freed: Map<String, Long>): TermuxReport {
        if (freed.isEmpty()) return this
        val gone = freed.keys
        val kept = entries.filterNot { e -> gone.any { e.path == it || e.path.startsWith("$it/") } }.map { e ->
            val less = freed.entries.sumOf { (p, b) -> if (p.startsWith(e.path + "/")) b else 0L }
            if (less > 0) e.copy(bytes = (e.bytes - less).coerceAtLeast(0)) else e
        }
        return copy(
            entries = kept,
            rootfs = rootfs.filterNot { it.path in gone },
            largeFiles = largeFiles.filterNot { f -> gone.any { f.path == it || f.path.startsWith("$it/") } },
            items = items.filterNot { i -> gone.any { i.path == it || i.path.startsWith("$it/") } },
            duplicates = duplicates.mapNotNull { d ->
                d.copy(paths = d.paths.filterNot { p -> gone.any { p == it || p.startsWith("$it/") } }).takeIf { it.paths.size > 1 }
            },
            sketches = sketches.filterNot { k -> gone.any { k.path == it || k.path.startsWith("$it/") } },
        )
    }

    val reclaimableBytes: Long get() = items.sumOf { it.bytes }

    /** Total size of the Termux files folder (home + packages). */
    val totalBytes: Long get() = usage.firstOrNull()?.bytes ?: 0L

    fun without(specs: Set<String>): TermuxReport = copy(items = items.filterNot { it.spec in specs })
}

data class TermuxCleanResult(val targetId: String, val status: String, val before: Long, val after: Long, val path: String, val note: String) {
    /** REMOVED is an uninstalled package: its installed size, as dpkg reports it. */
    val freed: Long get() = if (status == "CLEARED" || status == "PARTIAL" || status == "REMOVED") (before - after).coerceAtLeast(0) else 0
    val ok: Boolean get() = status == "CLEARED" || status == "NO_CHANGE" || status == "REMOVED" || status == "MOVED"
}

data class TermuxCleanSummary(val results: List<TermuxCleanResult>) {
    val freed: Long get() = results.sumOf { it.freed }
    val cleared: Int get() = results.count { it.status == "CLEARED" || it.status == "PARTIAL" || it.status == "REMOVED" }
    val skipped: List<TermuxCleanResult> get() = results.filter { it.status.startsWith("SKIP") || it.status == "PARTIAL" }
}

class TermuxException(message: String) : Exception(message)

/**
 * Why a path in the Termux browser can't be picked, or null when it can. It mirrors the script's own checks so the
 * browser doesn't offer what the script would refuse; the script still checks everything again (packages that own a
 * folder are only known to dpkg, inside Termux).
 */
object TermuxLocks {
    /** Inside a distribution only your data and add-ons may go, not its system. */
    private val DISTRO_FREE = Regex("""^(opt|root|home|tmp|var/tmp|var/cache|srv|usr/local)/.+""")
    private val PACKAGE_DIRS = setOf("bin", "lib", "libexec", "include", "share", "etc", "var", "glibc", "tmp")

    fun reason(path: String, report: TermuxReport): String? {
        val home = report.home
        val prefix = report.prefix
        report.rootfs.firstOrNull { path == it.path }?.let { return "A Linux distribution: remove it on the Termux screen" }
        report.rootfs.firstOrNull { path.startsWith(it.path + "/") }?.let { r ->
            return if (DISTRO_FREE.matches(path.removePrefix(r.path + "/"))) null else "Part of the distribution's system"
        }
        if (path == home || path == prefix || path == report.filesRoot) return "Termux itself"
        if (path.startsWith("$home/")) {
            val top = path.removePrefix("$home/").substringBefore('/')
            if (top == "storage") return "Links to shared storage"
            if (top in setOf(".termux", ".ssh", ".gnupg")) return "Termux or your keys need it"
        }
        if (path.startsWith("$prefix/")) {
            val rel = path.removePrefix("$prefix/")
            if ('/' !in rel && rel in PACKAGE_DIRS) return "Package files: uninstall packages instead"
            if (rel == "var/lib/proot-distro" || rel.startsWith("var/lib/proot-distro/installed-rootfs") ||
                rel.startsWith("var/lib/proot-distro/containers")
            ) {
                return "Linux distributions: remove them on the Termux screen"
            }
        }
        return null
    }
}

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
        val entries = ArrayList<TermuxEntry>()
        val copies = LinkedHashMap<Pair<Long, String>, MutableList<String>>()
        val sketches = ArrayList<TermuxSketch>()
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
                "S" -> if (p.size >= 4) entries += TermuxEntry(p[3], p[1].toLongOrNull() ?: 0, p[2] == "d", (p.getOrNull(4)?.toLongOrNull() ?: 0) * 1000)
                "Q" -> if (p.size >= 4) {
                    val size = p[1].toLongOrNull() ?: continue
                    copies.getOrPut(size to p[2]) { ArrayList() } += p[3]
                }
                "H" -> if (p.size >= 5) {
                    val hashes = p[3].split(',').mapNotNull { it.toLongOrNull() }.toLongArray()
                    if (hashes.isNotEmpty()) sketches += TermuxSketch(p[4], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0, hashes)
                }
            }
        }
        val duplicates = copies.filterValues { it.size > 1 }
            .map { (key, paths) -> TermuxDuplicateSet(key.first, key.second, paths.sorted()) }
            .sortedByDescending { it.extraBytes }
        // "debian: var/cache/apt/archives" reads better than the full proot-distro path.
        val named = items.map { item ->
            if (item.group != TermuxGroup.PROOT) return@map item
            val r = rootfs.firstOrNull { item.path.startsWith(it.path + "/") } ?: return@map item
            val info = TermuxCatalog.targets.getValue(item.targetId)
            item.copy(title = "${info.title}: ${r.path.substringAfterLast('/')}/${item.path.removePrefix(r.path + "/")}")
        }
        return TermuxReport(
            home, prefix, active, usage, named.sortedByDescending { it.bytes }, rootfs, large, warnings, unsafe, entries, duplicates, sketches,
        )
    }

    fun parsePackages(output: String): List<TermuxPackage> {
        val parsed = parse(output)
        check(parsed)
        return parsed.records.filter { it[0] == "K" && it.size >= 8 }.map { p ->
            TermuxPackage(
                name = p[1],
                bytes = p[2].toLongOrNull() ?: 0,
                manual = p[3] == "1",
                protected = p[4] == "1",
                version = p[5],
                depends = dependencyNames(p[6]),
                summary = p[7],
                installed = seconds(p.getOrNull(8)),
                lastUsed = seconds(p.getOrNull(9)),
                uses = p.getOrNull(10)?.toIntOrNull() ?: 0,
                commands = p.getOrNull(11).orEmpty().split(',').filter { it.isNotEmpty() },
            )
        }.sortedByDescending { it.bytes }
    }

    private fun seconds(field: String?): Long = (field?.toLongOrNull() ?: 0L).coerceAtLeast(0) * 1000

    /** The M records of a packages run: programs npm, pip and cargo installed. */
    fun parsePrograms(output: String): List<TermuxProgram> {
        val parsed = parse(output)
        check(parsed)
        return parsed.records.filter { it[0] == "M" && it.size >= 11 }.map { p ->
            TermuxProgram(
                manager = p[1],
                name = p[2],
                version = p[3],
                bytes = p[4].toLongOrNull() ?: 0,
                installed = seconds(p[5]),
                lastUsed = seconds(p[6]),
                uses = p[7].toIntOrNull() ?: 0,
                protected = p[8] == "1",
                commands = p[9].split(',').filter { it.isNotEmpty() },
                path = p[10],
            )
        }.sortedByDescending { it.bytes }
    }

    fun parseRepos(output: String): List<TermuxRepo> {
        val parsed = parse(output)
        check(parsed)
        return parsed.records.filter { it[0] == "G" && it.size >= 14 }.map { p ->
            TermuxRepo(
                path = p[1],
                bytes = p[2].toLongOrNull() ?: -1,
                gitBytes = p[3].toLongOrNull() ?: 0,
                looseBytes = p[4].toLongOrNull() ?: 0,
                garbageBytes = p[5].toLongOrNull() ?: 0,
                lastCommit = seconds(p[6]),
                lastFetch = seconds(p[7]),
                lastActive = seconds(p[8]),
                shallow = p[9] == "1",
                dirty = when (p[10]) { "1" -> true; "0" -> false; else -> null },
                unpushed = p[11].toIntOrNull()?.takeIf { it >= 0 },
                branch = p[12],
                remote = p[13],
            )
        }.sortedByDescending { maxOf(it.bytes, it.gitBytes) }
    }

    /** "libc++, openssl (>= 3), zlib | libz" -> libc++, openssl, zlib, libz. */
    fun dependencyNames(field: String): Set<String> =
        field.split(',', '|').map { it.substringBefore('(').substringBefore(':').trim() }.filter { it.isNotEmpty() }.toSet()

    fun parsePlan(output: String): TermuxRemovalPlan {
        val parsed = parse(output)
        check(parsed)
        val packages = parsed.records.filter { it[0] == "P" && it.size >= 5 }.map { p ->
            PlannedRemoval(p[1], p[2].toLongOrNull() ?: 0, p[3], p[4] == "1")
        }
        val order = listOf("requested", "dependent", "orphan")
        return TermuxRemovalPlan(
            packages.sortedWith(compareBy<PlannedRemoval> { order.indexOf(it.kind) }.thenByDescending { it.bytes }),
            parsed.records.filter { it[0] == "W" }.map { it.getOrElse(1) { "" } },
        )
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
