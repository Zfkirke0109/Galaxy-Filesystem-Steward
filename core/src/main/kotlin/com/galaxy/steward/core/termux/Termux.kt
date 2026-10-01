package com.galaxy.steward.core.termux

import com.galaxy.steward.core.dedupe.DirSketch
import com.galaxy.steward.core.dedupe.FolderSketch
import com.galaxy.steward.core.dedupe.NearCopy
import com.galaxy.steward.core.plural

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
    SAFE(
        "Downloads and temp files",
        "Package downloads, downloaded distro images, APT's derived indexes, trash, crash logs and dumps, and temp files and logs older than a week.",
    ),
    DEV(
        "Developer caches",
        "Download caches of npm, npx, pip, uv, Poetry, Yarn, Go, Cargo, rustup, Bun, Gradle, Maven, CPAN and the Android SDK, old Gradle " +
            "versions, Chromium and VS Code caches, and Python bytecode. Tools re-download or rebuild what they need.",
    ),
    PROOT("Linux distributions", "Package caches and old temp files inside proot distributions that are not running."),
    BUILD("Build outputs", "Folders Git ignores in your projects (build, node_modules, target, .venv...). Rebuilt on the next build or install."),
    OTHER("Other caches", "Everything else in ~/.cache. Often model or download caches that are slow to fetch again - review first."),
    LEFTOVERS(
        "Leftovers",
        "Decompiled apps, APKs, what the phone can't run (x86-64 NDKs, emulator images, Kotlin/Native), folders no package " +
            "installed, a replaced Python's packages, and files proot no longer uses. Not caches: review each one.",
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
            "claude-versions-unsure", "Older Claude Code versions", TermuxGroup.DEV, false,
            "The launcher doesn't say which version runs, so only the newest stays: check claude --version first",
        ),
        TermuxTargetInfo(
            "koa-archives", "Termux steward archives", TermuxGroup.OTHER, false,
            "Backups the Koa Termux steward script made (~/.storage-autopilot-archives); only needed to undo its old runs",
        ),
        TermuxTargetInfo(
            "home-node-modules", "npm packages in your home", TermuxGroup.DEV, false,
            "~/node_modules: npm install run in the home folder itself; it puts them back",
        ),
        TermuxTargetInfo("cpan-build", "CPAN build folders", TermuxGroup.DEV, true, "Perl modules cpan unpacked to build; it unpacks them again"),
        TermuxTargetInfo("cpan-sources", "CPAN downloads", TermuxGroup.DEV, true, "Perl module archives cpan already installed"),
        TermuxTargetInfo("go-mod", "Go module sources", TermuxGroup.DEV, false, "go downloads them again on the next build"),
        TermuxTargetInfo("m2-repository", "Maven repository", TermuxGroup.DEV, false, "Maven and Gradle download them again: slow on a phone"),
        TermuxTargetInfo("gradle-wrapper-old", "Old Gradle versions", TermuxGroup.DEV, true, "Versions no project in your home asks for; the newest stays"),
        TermuxTargetInfo("code-server-vsix", "Downloaded VS Code extensions", TermuxGroup.DEV, true, "code-server's copies of extension installers"),
        TermuxTargetInfo("sdk-temp", "Android SDK downloads", TermuxGroup.SAFE, true, "sdkmanager's unfinished downloads"),
        TermuxTargetInfo(
            "app-cache", "App cache", TermuxGroup.DEV, true,
            "A Chromium or Electron app's cache (Chromium, VS Code, Code - OSS): rebuilt as the app is used",
        ),
        TermuxTargetInfo("app-logs", "App logs", TermuxGroup.SAFE, true, "Only logs older than 7 days"),
        TermuxTargetInfo("crash-log", "Crash log", TermuxGroup.SAFE, true, "What a crashed Java program wrote (hs_err_pid, replay_pid)"),
        TermuxTargetInfo("heap-dump", "Heap or core dump", TermuxGroup.SAFE, true, "A crashed program's memory, written for debugging"),
        TermuxTargetInfo("sdk-system-images", "Android emulator images", TermuxGroup.LEFTOVERS, false, "The Android emulator can't run on a phone"),
        TermuxTargetInfo("sdk-emulator", "Android emulator for PCs", TermuxGroup.LEFTOVERS, false, "Built for x86-64 PCs: it can't run on this phone"),
        TermuxTargetInfo(
            "konan", "Kotlin/Native toolchains", TermuxGroup.LEFTOVERS, false,
            "Built for PCs: Kotlin/Native can't compile on an ARM phone, so Gradle's downloads for it can't run here",
        ),
        TermuxTargetInfo(
            "old-snapshot", "Old report or snapshot", TermuxGroup.LEFTOVERS, false,
            "Named with the date it was made, and nothing in it changed for three days: a report, debug dump or backup",
        ),
        TermuxTargetInfo("file-copy", "Older copy", TermuxGroup.LEFTOVERS, false, "A copy kept before an edit (.bak, .orig, ~): the file it copies is still there"),
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
        TermuxTargetInfo(
            "prefix-unowned", "Not from any package", TermuxGroup.LEFTOVERS, false,
            "No Termux package installed anything in it: put there by hand, or left behind by an uninstall",
        ),
        TermuxTargetInfo(
            "old-python", "Packages of a Python that is gone", TermuxGroup.LEFTOVERS, false,
            "What pip installed for a Python version Termux has since replaced; the Python you have can't use them",
        ),
        TermuxTargetInfo("proot-cache", "Distro package cache", TermuxGroup.PROOT, true),
        TermuxTargetInfo("proot-tmp", "Distro temp files", TermuxGroup.PROOT, true, "Only files older than 7 days"),
        TermuxTargetInfo("build", "Build output", TermuxGroup.BUILD, false),
        TermuxTargetInfo("other-cache", "Cache folder", TermuxGroup.OTHER, false),
    ).associateBy { it.id }

    /**
     * Targets that only take files by age, or what the next run rebuilds: they can't break anything kept, so the keep
     * rule leaves them be (the script's clean_one says the same).
     */
    val KEEP_EXEMPT = setOf(
        "pycache", "proot-tmp", "termux-tmp", "var-tmp", "termux-var-log", "npm-logs", "gradle-daemon-logs", "apt-archives",
        "apt-pkgcache", "claude-versions", "claude-versions-unsure", "app-logs",
    )

    /** Targets whose records carry a path the script must re-validate (`id=path`). */
    val PATH_TARGETS = setOf(
        "other-cache", "proot-cache", "proot-tmp", "build", "decompiled", "home-apk", "foreign-ndk", "l2s-orphan", "prefix-unowned", "old-python",
        "app-cache", "app-logs", "crash-log", "heap-dump", "old-snapshot", "file-copy",
    )
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
    /** Commands in $PREFIX/bin that lead into it, and when you last ran one (ms, 0 when never or unknown). */
    val commands: List<String> = emptyList(),
    val lastUsed: Long = 0,
)

data class TermuxUsage(val path: String, val bytes: Long)

/**
 * Something in Termux that stays whatever you pick, and why: a relay Layla's Sidekick mini apps talk to, what Termux:Boot,
 * termux-services, a Widget shortcut, Tasker or cron starts, what runs right now, or what you chose to keep.
 */
data class TermuxKept(val path: String, val bytes: Long, val why: String)

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

/**
 * A big program (8 MiB or more) built for another processor: x86-64 or x86 Linux, or Windows, on an ARM phone with
 * no x86 emulator installed. It can't run here, whatever needs it.
 */
data class TermuxForeignFile(val path: String, val cpu: String, val bytes: Long) {
    val cpuLabel: String get() = when (cpu) {
        "x86-64" -> "x86-64 PC program"
        "x86" -> "32-bit PC program"
        "windows" -> "Windows program"
        else -> cpu
    }
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
    /** Why it stays: something kept runs with it (Layla's relay, a Boot script, what runs now). Empty when nothing does. */
    val keptBy: String = "",
)

/**
 * One package apt would remove: [kind] is requested, dependent (it needs a requested one) or orphan (nothing needs it
 * after). [keptBy]: something kept runs with it, so it stays.
 */
data class PlannedRemoval(val name: String, val bytes: Long, val kind: String, val protected: Boolean, val keptBy: String = "")

data class TermuxRemovalPlan(val packages: List<PlannedRemoval>, val warnings: List<String>) {
    /** Something Termux needs, or something kept runs with, would go: nothing is removed. */
    val blocked: Boolean get() = packages.any { it.protected || it.keptBy.isNotEmpty() }
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
    /** Size-map entries under $PREFIX that packages installed or hold files in: path to (package count, some names). */
    val owners: Map<String, Pair<Int, String>> = emptyMap(),
    /** Big programs for another processor, largest first. */
    val foreign: List<TermuxForeignFile> = emptyList(),
    /** How long each part of the audit took (milliseconds), for the run log. */
    val timings: Map<String, Long> = emptyMap(),
    /** What stays whatever you pick (see [TermuxKept]). */
    val kept: List<TermuxKept> = emptyList(),
    /** Clean-up items the keep rule holds back: they would touch something kept. Never picked, shown so you know. */
    val held: List<TermuxItem> = emptyList(),
) {
    /**
     * [kept] less what sits inside another kept path (~/bin and ~/bin/start-layla-bridge read as one): what to show.
     * What you chose to keep always shows, so you can let it go again.
     */
    val keptTopmost: List<TermuxKept> by lazy {
        kept.filter { k -> k.why == CHOSEN || kept.none { o -> o !== k && k.path.startsWith(o.path + "/") } }
    }

    /**
     * Why [path] has to stay, or null: it is kept, inside something kept, or holds something kept (removing it would
     * take that too). The script checks the same again before anything goes.
     */
    fun keptWhy(path: String): String? {
        val k = kept.firstOrNull { path == it.path || path.startsWith(it.path + "/") || it.path.startsWith("$path/") } ?: return null
        return TermuxProtocol.relative(k.path, home, prefix) + ": " + k.why
    }

    /**
     * This report with [list] as what is kept: clean-up items that would touch any of it are held back from every pick
     * (the goal planner's too), and ones that no longer would come back.
     */
    fun withKept(list: List<TermuxKept>): TermuxReport {
        val next = copy(kept = list.sortedByDescending { it.bytes })
        val (hold, free) = (items + held).partition { it.targetId !in TermuxCatalog.KEEP_EXEMPT && next.keptWhy(it.path) != null }
        return next.copy(items = free.sortedByDescending { it.bytes }, held = hold.sortedByDescending { it.bytes })
    }

    /** After "Always keep" ([keep]) or "Stop keeping" on [paths], without a new scan. */
    fun keeping(paths: Collection<String>, keep: Boolean): TermuxReport = withKept(
        if (keep) kept.filterNot { it.path in paths } + paths.map { TermuxKept(it, entry(it)?.bytes ?: 0, CHOSEN) }
        else kept.filterNot { it.path in paths && it.why == CHOSEN },
    )

    private val byParent: Map<String, List<TermuxEntry>> by lazy {
        entries.groupBy { it.path.substringBeforeLast('/') }.mapValues { (_, list) -> list.sortedByDescending { it.bytes } }
    }
    private val byPath: Map<String, TermuxEntry> by lazy { entries.associateBy { it.path } }

    /** The files and folders of 1 MiB or more directly inside [path], largest first. */
    fun children(path: String): List<TermuxEntry> = byParent[path].orEmpty()

    fun entry(path: String): TermuxEntry? = byPath[path]

    /** The Termux files folder, the top of the size map. */
    val filesRoot: String get() = home.substringBeforeLast('/')

    /**
     * This report after [freed] (path to bytes freed) went: entries below them drop out, and their parents shrink, the
     * "where the space goes" totals too.
     */
    fun afterDeleting(freed: Map<String, Long>): TermuxReport {
        if (freed.isEmpty()) return this
        val gone = freed.keys
        fun lessBelow(path: String) = freed.entries.sumOf { (p, b) -> if (p.startsWith("$path/")) b else 0L }
        val kept = entries.filterNot { e -> gone.any { e.path == it || e.path.startsWith("$it/") } }.map { e ->
            val less = freed.entries.sumOf { (p, b) -> if (p.startsWith(e.path + "/")) b else 0L }
            if (less > 0) e.copy(bytes = (e.bytes - less).coerceAtLeast(0)) else e
        }
        return copy(
            usage = usage.filterNot { it.path in gone }.map { u -> lessBelow(u.path).let { if (it > 0) u.copy(bytes = (u.bytes - it).coerceAtLeast(0)) else u } },
            owners = owners.filterKeys { p -> gone.none { p == it || p.startsWith("$it/") } },
            entries = kept,
            rootfs = rootfs.filterNot { it.path in gone },
            largeFiles = largeFiles.filterNot { f -> gone.any { f.path == it || f.path.startsWith("$it/") } },
            items = items.filterNot { i -> gone.any { i.path == it || i.path.startsWith("$it/") } },
            duplicates = duplicates.mapNotNull { d ->
                d.copy(paths = d.paths.filterNot { p -> gone.any { p == it || p.startsWith("$it/") } }).takeIf { it.paths.size > 1 }
            },
            sketches = sketches.filterNot { k -> gone.any { k.path == it || k.path.startsWith("$it/") } },
            foreign = foreign.filterNot { f -> gone.any { f.path == it || f.path.startsWith("$it/") } },
        )
    }

    /**
     * This report after a clean-up or an uninstall ([results]): every total above what was freed shrinks by it, the
     * "where the space goes" lines too. What was removed as a whole (a build output, a decompiled app, an npm program)
     * leaves the size map; a cleaned cache keeps its entry, smaller. A package has no path of its own: what it freed
     * comes off the packages total. The 1.2.9 report still said 22.1 GiB after a clean-up had freed 2.6 GiB.
     */
    fun afterCleaning(results: List<TermuxCleanResult>): TermuxReport {
        val freed = results.filter { it.freed > 0 }.map { r -> (r.path.ifEmpty { prefix }) to r }
        if (freed.isEmpty()) return this
        fun lessAt(path: String) = freed.sumOf { (p, r) -> if (p == path || p.startsWith("$path/")) r.freed else 0L }
        // Programs come back as "npm:name" with their folder; packages without a path (their bytes are spread out).
        val whole = freed.filter { (p, r) -> r.targetId in WHOLE_TARGETS || (':' in r.targetId && p != prefix) }.map { it.first }.toSet()
        val gone = { path: String -> whole.any { path == it || path.startsWith("$it/") } }
        return copy(
            usage = usage.filterNot { gone(it.path) }.map { u -> lessAt(u.path).let { if (it > 0) u.copy(bytes = (u.bytes - it).coerceAtLeast(0)) else u } },
            entries = entries.filterNot { gone(it.path) }.map { e -> lessAt(e.path).let { if (it > 0) e.copy(bytes = (e.bytes - it).coerceAtLeast(0)) else e } },
            largeFiles = largeFiles.filterNot { gone(it.path) },
            duplicates = duplicates.mapNotNull { d -> d.copy(paths = d.paths.filterNot(gone)).takeIf { it.paths.size > 1 } },
            sketches = sketches.filterNot { gone(it.path) },
            foreign = foreign.filterNot { gone(it.path) },
        )
    }

    /**
     * Near-copies among Termux's biggest folders, and between them and shared storage's ([shared], from the last scan):
     * pairs whose files mostly match by name and size. At least one side of each pair is in Termux.
     */
    fun nearCopies(shared: List<DirSketch> = emptyList(), threshold: Double = 0.6): List<NearCopy> {
        val mine = sketches.map { DirSketch(it.path, it.files, it.bytes, entry(it.path)?.mtime ?: 0L, it.hashes) }
        if (mine.isEmpty()) return emptyList()
        return FolderSketch.nearCopies(mine + shared, threshold).filter { p -> mine.any { it.path == p.a || it.path == p.b } }
    }

    val reclaimableBytes: Long get() = items.sumOf { it.bytes }

    /** Total size of the Termux files folder (home + packages). */
    val totalBytes: Long get() = usage.firstOrNull()?.bytes ?: 0L

    fun without(specs: Set<String>): TermuxReport = copy(items = items.filterNot { it.spec in specs })

    companion object {
        /** Why something you kept yourself stays (the script's keep_listed says the same). */
        const val CHOSEN = "you chose to keep it"

        /** Clean-up targets that remove the folder or file itself, not what is in it. */
        val WHOLE_TARGETS = setOf("build", "decompiled", "home-apk", "foreign-ndk", "l2s-orphan", "path", "prefix-unowned", "old-python", "crash-log", "heap-dump", "old-snapshot", "file-copy")
    }
}

data class TermuxCleanResult(val targetId: String, val status: String, val before: Long, val after: Long, val path: String, val note: String) {
    /** REMOVED is an uninstalled package: its installed size, as dpkg reports it. */
    val freed: Long get() = if (status == "CLEARED" || status == "PARTIAL" || status == "REMOVED") (before - after).coerceAtLeast(0) else 0
    val ok: Boolean get() = status in setOf("CLEARED", "NO_CHANGE", "REMOVED", "MOVED", "KEPT", "UNKEPT")
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
        report.keptWhy(path)?.let { return "Kept - $it" }
        if (path.startsWith("$home/")) {
            val top = path.removePrefix("$home/").substringBefore('/')
            if (top == "storage") return "Links to shared storage"
            if (top in setOf(".termux", ".ssh", ".gnupg")) return "Termux or your keys need it"
        }
        report.owners[path]?.let { (count, names) ->
            return if (count == 1) "Installed by $names: uninstall it under Packages" else "Files of ${count.plural("package")}: uninstall them under Packages"
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
        // The helper stopped on an error of its own (Z carries what bash said): nothing it printed is complete.
        parsed.records.firstOrNull { it[0] == "Z" }?.let {
            throw TermuxException("The Termux helper stopped early: " + it.getOrElse(1) { "no reason given" })
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
        val owners = HashMap<String, Pair<Int, String>>()
        val foreign = ArrayList<TermuxForeignFile>()
        val timings = LinkedHashMap<String, Long>()
        val kept = ArrayList<TermuxKept>()
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
                    items += TermuxItem(
                        spec, info.id, title, info.group, path, p[2].toLong(), p[3].toInt(), info.defaultSelected, info.note,
                        commands = p.getOrNull(6)?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                        lastUsed = (p.getOrNull(5)?.toLongOrNull() ?: 0) * 1000,
                    )
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
                "O" -> if (p.size >= 4) owners[p[3]] = (p[1].toIntOrNull() ?: 1) to p[2]
                "F" -> if (p.size >= 4) foreign += TermuxForeignFile(p[3], p[1], p[2].toLongOrNull() ?: 0)
                "Y" -> if (p.size >= 3) p[2].toLongOrNull()?.let { timings[p[1]] = it }
                "A" -> if (p.size >= 4) kept += TermuxKept(p[2], p[1].toLongOrNull() ?: 0, p[3])
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
            owners, foreign.sortedByDescending { it.bytes }, timings,
        ).withKept(kept)
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
                keptBy = p.getOrNull(12).orEmpty(),
            )
        }.sortedByDescending { it.bytes }
    }

    /** The J record of a packages run: how far back the shell history goes, which says how far "never run" holds. */
    fun parseHistorySpan(output: String): HistorySpan {
        val j = parse(output).records.lastOrNull { it[0] == "J" && it.size >= 3 } ?: return HistorySpan(0, 0)
        return HistorySpan(seconds(j[1]), j[2].toIntOrNull() ?: 0)
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
            PlannedRemoval(p[1], p[2].toLongOrNull() ?: 0, p[3], p[4] == "1", p.getOrNull(5).orEmpty())
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
