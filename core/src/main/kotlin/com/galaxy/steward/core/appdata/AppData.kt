package com.galaxy.steward.core.appdata

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.FileKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** The three per-app folders Android keeps in shared storage. */
enum class AppArea(val dir: String, val label: String) {
    DATA("data", "Android/data"),
    OBB("obb", "Android/obb"),
    MEDIA("media", "Android/media");

    companion object {
        fun ofDir(dir: String): AppArea? = entries.firstOrNull { it.dir == dir }
    }
}

enum class AppJunkKind(val title: String, val description: String, val defaultSelected: Boolean, val undoable: Boolean) {
    CACHE(
        "App caches",
        "The cache folder inside each app's Android/data folder. Apps rebuild it when they need it.",
        true, false,
    ),
    LOGS(
        "Logs and crash reports",
        "Log files, crash dumps, ANR traces and tombstones that apps leave in their folders.",
        true, false,
    ),
    TEMP("Temporary files", "tmp and temp folders, and partial downloads nobody has touched for a week.", true, false),
    THUMBNAILS("Thumbnail caches", "Preview images apps regenerate when you next open them.", false, false),
    OBSOLETE_OBB("Outdated game data", "Older OBB files that a newer version for the same app has replaced.", true, true),
    LEFTOVERS(
        "Leftovers of removed apps",
        "Folders in Android/data, obb and media belonging to apps that are no longer installed.",
        false, true,
    ),

    // Chosen by you in the app folder browser; never produced by a scan.
    PICKED("Picked by you", "Files and folders you chose in the app folder browser, moved to the quarantine.", false, true),
    PICKED_DELETE("Deleted by you", "Files and folders you chose in the app folder browser, deleted for good.", false, false),
}

/**
 * One file or folder the steward may clean. For a folder, only the regular files inside it that are older than the
 * item's cutoff are removed and the folder itself stays; a leftover folder is quarantined as a whole.
 */
data class AppTarget(val path: String, val isDirectory: Boolean, val size: Long, val mtime: Long)

data class AppJunkItem(
    val id: String,
    val kind: AppJunkKind,
    val packageName: String,
    val area: AppArea,
    val targets: List<AppTarget>,
    val bytes: Long,
    val fileCount: Int,
    /** Files modified after this moment (epoch ms) are left alone. */
    val cutoff: Long,
    val note: String,
) {
    val defaultSelected: Boolean get() = kind.defaultSelected
}

data class AppAreaUsage(
    val packageName: String,
    val area: AppArea,
    val bytes: Long,
    val files: Int,
    val installed: Boolean,
    val protected: Boolean,
)

data class AppLargeFile(val packageName: String, val path: String, val size: Long, val mtime: Long)

data class AppDataReport(
    val scannedAt: Long,
    val areas: Set<AppArea>,
    val usage: List<AppAreaUsage>,
    val items: List<AppJunkItem>,
    val largeFiles: List<AppLargeFile>,
    /** Package folders that could not be listed (permission or I/O errors). */
    val unreadable: List<String>,
) {
    val reclaimableBytes: Long get() = items.sumOf { it.bytes }

    fun without(ids: Set<String>): AppDataReport = copy(items = items.filterNot { it.id in ids })
}

/** App protections ported from the Termux steward's strict v18 policy. */
object AppPolicy {
    /** Offline libraries: never cleaned, stopped or cleared, whatever the mode (`is_protected_app_v18`). */
    val STRICT_NO_TOUCH = setOf("com.amazon.mp3", "com.audible.application")

    /** Apps that are never force-stopped before a cache clear (`should_force_stop_cache_target_v18`). */
    private val NEVER_FORCE_STOP = setOf(
        "com.google.android.apps.messaging", "com.android.providers.telephony", "com.google.android.apps.photos",
        "com.sec.android.app.shealth", "com.android.managedprovisioning",
        // Stopping these would end your terminal sessions or the Shizuku bridge the steward is talking through.
        "com.termux", "moe.shizuku.privileged.api",
        // A stopped app gets no push messages until you open it again, so messengers and mail are never stopped.
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.facebook.mlite", "com.discord", "com.Slack",
        "com.microsoft.teams", "com.google.android.gm", "com.microsoft.office.outlook", "com.google.android.apps.googlevoice",
        "com.viber.voip", "jp.naver.line.android", "com.tencent.mm", "com.snapchat.android", "com.instagram.android",
        "us.zoom.videomeetings", "com.skype.raider", "ch.protonmail.android", "com.google.android.apps.dynamite",
    )

    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    fun isPackageName(name: String): Boolean = PACKAGE.matches(name)

    fun isProtected(packageName: String, ownPackage: String?): Boolean =
        packageName in STRICT_NO_TOUCH || packageName == ownPackage

    fun mayForceStop(packageName: String): Boolean = when {
        packageName in STRICT_NO_TOUCH || packageName in NEVER_FORCE_STOP -> false
        packageName == "com.google.android.gms" || packageName.startsWith("com.google.android.gms.") -> false
        packageName.startsWith("com.samsung.") -> false
        else -> true
    }

    internal val LOG_DIRS = setOf(
        "log", "logs", "xlog", "xlogs", "applog", "applogs", "crash", "crashes", "crashlog", "crashlogs",
        "crash_reports", "crashreports", "crash-reports", "crash reports", "tombstones", "anr", "minidumps", "breakpad",
    )
    internal val TEMP_DIRS = setOf("tmp", "temp", ".tmp", ".temp")
    internal val THUMB_DIRS = setOf(".thumbnails", "thumbnails", ".thumbs", "thumbs", "thumbnail", ".thumbnail")
    private val LOG_FILE = Regex("""(?i)(\.(log|log\.\d+|log\.gz|hprof|dmp|mdmp|crash|stacktrace)$|^(tombstone|anr)_\d+)""")

    /** LevelDB/RocksDB name their write-ahead logs 000123.log; those hold live data, never logs. */
    private val DATABASE_LOG = Regex("""^\d+\.log$""")
    private val TEMP_EXT = setOf("tmp", "temp", "part", "partial", "crdownload")

    fun isLogFile(name: String): Boolean = LOG_FILE.containsMatchIn(name) && !DATABASE_LOG.matches(name)

    fun isTempFile(name: String): Boolean = FileKind.extensionOf(name) in TEMP_EXT

    /** A folder that looks like an embedded database (LevelDB, RocksDB): its files are never treated as junk. */
    fun isDatabaseDir(childNames: Collection<String>): Boolean =
        "CURRENT" in childNames || childNames.any { it.startsWith("MANIFEST-") || it.endsWith(".ldb") || it.endsWith(".sst") }

    internal val OBB_NAME = Regex("""^(main|patch)\.(\d+)\.(.+)\.obb$""")
}

data class AppScanOptions(
    val now: Long = System.currentTimeMillis(),
    val logAgeDays: Int = 3,
    val tempAgeDays: Int = 7,
    /** Files modified this recently are treated as in use, even in a cache folder. */
    val recentGuardMinutes: Int = 10,
    val largeFileBytes: Long = 50 * MIB,
    val maxDepth: Int = 14,
)

/**
 * Read-only survey of Android/data, Android/obb and Android/media. On Android 11+ only a privileged helper
 * (Shizuku) can list data and obb, so this runs either in-process (tests, media) or inside the Shizuku service.
 *
 * [installed] must list every package the device knows about, including ones uninstalled with "keep data" and
 * archived apps; when it is empty nothing is reported as a leftover.
 */
class AppDataScanner(
    rootPath: String,
    private val installed: Set<String>,
    private val options: AppScanOptions = AppScanOptions(),
    private val ownPackage: String? = null,
) {
    private val root: Path = Paths.get(rootPath.trimEnd('/'))
    private val canJudgeLeftovers = installed.size >= MIN_KNOWN_PACKAGES

    fun scan(areas: Set<AppArea> = AppArea.entries.toSet(), onPackage: ((String) -> Unit)? = null): AppDataReport {
        val usage = ArrayList<AppAreaUsage>()
        val items = ArrayList<AppJunkItem>()
        val large = ArrayList<AppLargeFile>()
        val unreadable = ArrayList<String>()
        for (area in areas) {
            val areaDir = root.resolve("Android").resolve(area.dir)
            val packages = listDir(areaDir) ?: run {
                if (Files.exists(areaDir, LinkOption.NOFOLLOW_LINKS)) unreadable += areaDir.toString()
                emptyList()
            }
            for ((pkgDir, attrs) in packages) {
                val pkg = pkgDir.fileName.toString()
                if (!attrs.isDirectory || !AppPolicy.isPackageName(pkg)) continue
                onPackage?.invoke(pkg)
                val isInstalled = pkg in installed || !canJudgeLeftovers
                val protected = AppPolicy.isProtected(pkg, ownPackage)
                val survey = PackageSurvey(pkg, area, pkgDir)
                if (!survey.walk()) unreadable += pkgDir.toString()
                usage += AppAreaUsage(pkg, area, survey.totalBytes, survey.totalFiles, isInstalled, protected)
                large += survey.large
                if (protected) continue
                if (!isInstalled) {
                    items += AppJunkItem(
                        id = "app:${AppJunkKind.LEFTOVERS.name}:${area.dir}:$pkg",
                        kind = AppJunkKind.LEFTOVERS,
                        packageName = pkg,
                        area = area,
                        targets = listOf(AppTarget(pkgDir.toString(), true, survey.totalBytes, attrs.lastModifiedTime().toMillis())),
                        bytes = survey.totalBytes,
                        fileCount = survey.totalFiles,
                        cutoff = options.now,
                        note = "${area.label}/$pkg - not installed. Quarantined, so it can be restored.",
                    )
                    continue
                }
                items += survey.items()
            }
        }
        return AppDataReport(
            scannedAt = options.now,
            areas = areas,
            usage = usage.sortedByDescending { it.bytes },
            items = items.sortedByDescending { it.bytes },
            largeFiles = large.sortedByDescending { it.size }.take(40),
            unreadable = unreadable,
        )
    }

    private fun cutoffFor(kind: AppJunkKind): Long = when (kind) {
        AppJunkKind.LOGS -> options.now - options.logAgeDays * DAY_MS
        AppJunkKind.TEMP -> options.now - options.tempAgeDays * DAY_MS
        else -> options.now - options.recentGuardMinutes * 60_000L
    }

    /** Walks one package folder once, collecting usage, large files and cleanable targets. */
    private inner class PackageSurvey(val pkg: String, val area: AppArea, val pkgDir: Path) {
        var totalBytes = 0L
        var totalFiles = 0
        val large = ArrayList<AppLargeFile>()
        private val targets = HashMap<AppJunkKind, MutableList<AppTarget>>()
        private val eligibleBytes = HashMap<AppJunkKind, Long>()
        private val eligibleFiles = HashMap<AppJunkKind, Int>()
        private val obbs = ArrayList<Triple<Path, BasicFileAttributes, MatchResult>>()

        private fun add(kind: AppJunkKind, target: AppTarget, bytes: Long, files: Int) {
            targets.getOrPut(kind) { ArrayList() } += target
            eligibleBytes[kind] = (eligibleBytes[kind] ?: 0L) + bytes
            eligibleFiles[kind] = (eligibleFiles[kind] ?: 0) + files
        }

        /** Returns false when part of the tree could not be read. */
        fun walk(): Boolean {
            var complete = true
            // (dir, depth, kind of the special folder we are inside, or null)
            val stack = ArrayDeque<Triple<Path, Int, AppJunkKind?>>()
            stack.addLast(Triple(pkgDir, 0, null))
            // Special folders collect their eligible bytes here until the walk leaves them.
            val specialTotals = HashMap<Path, LongArray>()
            val specialOf = HashMap<Path, Pair<Path, AppJunkKind>>()
            while (stack.isNotEmpty()) {
                val (dir, depth, inside) = stack.removeLast()
                val entries = listDir(dir)
                if (entries == null) {
                    complete = false
                    continue
                }
                val names = entries.map { it.first.fileName.toString() }
                val database = AppPolicy.isDatabaseDir(names)
                val owner = specialOf[dir]
                for ((path, attrs) in entries) {
                    val name = path.fileName.toString()
                    if (attrs.isSymbolicLink || attrs.isOther || SafetyPolicy.isUnsafeName(name)) continue
                    if (attrs.isDirectory) {
                        if (depth + 1 > options.maxDepth) continue
                        val lower = name.lowercase()
                        val kind = inside ?: when {
                            area == AppArea.DATA && depth == 0 && name == "cache" -> AppJunkKind.CACHE
                            lower in AppPolicy.LOG_DIRS -> AppJunkKind.LOGS
                            lower in AppPolicy.TEMP_DIRS -> AppJunkKind.TEMP
                            lower in AppPolicy.THUMB_DIRS -> AppJunkKind.THUMBNAILS
                            else -> null
                        }
                        if (inside == null && kind != null) {
                            specialOf[path] = path to kind
                            specialTotals[path] = LongArray(3).also { it[2] = attrs.lastModifiedTime().toMillis() }
                        } else if (owner != null) {
                            specialOf[path] = owner
                        }
                        stack.addLast(Triple(path, depth + 1, kind))
                        continue
                    }
                    if (!attrs.isRegularFile) continue
                    val size = attrs.size()
                    val mtime = attrs.lastModifiedTime().toMillis()
                    totalBytes += size
                    totalFiles++
                    if (size >= options.largeFileBytes) large += AppLargeFile(pkg, path.toString(), size, mtime)
                    if (SafetyPolicy.isCredentialName(name)) continue
                    if (inside != null && owner != null) {
                        if (mtime <= cutoffFor(inside)) {
                            val t = specialTotals.getValue(owner.first)
                            t[0] += size
                            t[1]++
                        }
                        continue
                    }
                    if (area == AppArea.OBB && depth == 0) {
                        AppPolicy.OBB_NAME.matchEntire(name)?.takeIf { it.groupValues[3] == pkg }?.let {
                            obbs += Triple(path, attrs, it)
                            continue
                        }
                    }
                    if (database) continue
                    val kind = when {
                        AppPolicy.isLogFile(name) -> AppJunkKind.LOGS
                        AppPolicy.isTempFile(name) -> AppJunkKind.TEMP
                        else -> null
                    } ?: continue
                    if (mtime <= cutoffFor(kind)) add(kind, AppTarget(path.toString(), false, size, mtime), size, 1)
                }
            }
            for ((dir, totals) in specialTotals) {
                val kind = specialOf.getValue(dir).second
                if (totals[1] > 0) add(kind, AppTarget(dir.toString(), true, totals[0], totals[2]), totals[0], totals[1].toInt())
            }
            planObbs()
            large.sortByDescending { it.size }
            if (large.size > 5) large.subList(5, large.size).clear()
            return complete
        }

        /** Keeps the newest main and patch OBB; older versions are superseded. */
        private fun planObbs() {
            obbs.groupBy { it.third.groupValues[1] }.forEach { (_, versions) ->
                val newest = versions.maxOf { it.third.groupValues[2].toLongOrNull() ?: -1L }
                for ((path, attrs, match) in versions) {
                    if ((match.groupValues[2].toLongOrNull() ?: -1L) >= newest) continue
                    add(AppJunkKind.OBSOLETE_OBB, AppTarget(path.toString(), false, attrs.size(), attrs.lastModifiedTime().toMillis()), attrs.size(), 1)
                }
            }
        }

        fun items(): List<AppJunkItem> = targets.map { (kind, list) ->
            val bytes = eligibleBytes[kind] ?: 0L
            val files = eligibleFiles[kind] ?: 0
            AppJunkItem(
                id = "app:${kind.name}:${area.dir}:$pkg",
                kind = kind,
                packageName = pkg,
                area = area,
                targets = list.sortedBy { it.path },
                bytes = bytes,
                fileCount = files,
                cutoff = cutoffFor(kind),
                note = describe(kind, list, files, bytes),
            )
        }.filter { it.fileCount > 0 }

        private fun describe(kind: AppJunkKind, list: List<AppTarget>, files: Int, bytes: Long): String {
            val where = list.map { it.path.removePrefix(pkgDir.toString()).trimStart('/').ifEmpty { "." } }
                .let { if (it.size <= 2) it.joinToString(", ") else "${it.take(2).joinToString(", ")} and ${it.size - 2} more" }
            val age = when (kind) {
                AppJunkKind.LOGS -> "older than ${options.logAgeDays} days"
                AppJunkKind.TEMP -> "older than ${options.tempAgeDays} days"
                AppJunkKind.OBSOLETE_OBB -> "superseded by a newer version"
                else -> "not used in the last ${options.recentGuardMinutes} minutes"
            }
            return "$files file${if (files == 1) "" else "s"} (${bytes.humanBytes()}) $age · $where"
        }
    }

    private fun listDir(dir: Path): List<Pair<Path, BasicFileAttributes>>? = try {
        Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { p ->
                try {
                    p to Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    null
                }
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        /** Fewer known packages than this means the package list is unreliable, so nothing counts as a leftover. */
        const val MIN_KNOWN_PACKAGES = 10
    }
}
