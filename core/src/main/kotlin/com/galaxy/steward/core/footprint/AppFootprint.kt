package com.galaxy.steward.core.footprint

import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.termux.TermuxLocks
import com.galaxy.steward.core.termux.TermuxReport

/** Where an app left something, each removed its own way. */
enum class FootprintPlace(val title: String, val how: String) {
    SHARED("Shared storage", "Into the quarantine: History can undo it"),
    APP_FOLDERS("Android/data, obb and media", "Into the quarantine through Shizuku: History can undo it"),
    TERMUX("Termux", "Deleted for good inside Termux, each path checked again"),
    SHELL("Left by adb", "Deleted for good through Shizuku"),
}

/**
 * One folder or file named after the app. [lock] says why it can't be picked (a project, a pinned folder, keys, kept
 * in Termux, a package's files); [packageName] is set for an app's own Android/data|obb|media folder. [sure]: known to
 * be the app's by its package (its own folder, an installer with its package inside), not only by a name.
 */
data class FootprintHit(
    val place: FootprintPlace,
    val path: String,
    val bytes: Long,
    val files: Int,
    val isDirectory: Boolean,
    val mtime: Long,
    val lock: String? = null,
    val packageName: String? = null,
    val sure: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Everything an app left behind, found by its name: the words of its package and label ("com.wowee.client" and
 * "WoWee" give "wowee"), or a word you type. A folder or file is the app's when its name, with case, spaces and
 * punctuation ignored, contains one of them ("WoWee-Backup", "3Dwowee-v3.1.41-android-arm64.apk"). Only the topmost
 * match counts: what is inside a matched folder goes with it.
 */
object AppFootprint {
    /** Words too common to say whose a folder is. */
    private val GENERIC = setOf(
        "com", "org", "net", "io", "app", "apps", "android", "client", "mobile", "free", "pro", "lite", "plus", "beta",
        "debug", "release", "main", "the", "official", "studio", "launcher", "game", "games", "sdk", "lib", "service",
        "services", "google", "samsung", "sec", "data", "file", "files", "media", "video", "videos", "music", "photo",
        "photos", "image", "images", "camera", "chat", "notes", "note", "backup", "backups", "download", "downloads",
        "document", "documents", "player", "editor", "manager", "browser", "settings", "system", "update", "updates",
        "cloud", "drive", "mail", "phone", "message", "messages", "gallery", "assets", "cache", "temp", "logs", "config",
        "home", "work", "test", "tests", "demo", "sample", "example", "tool", "tools", "utils", "util", "core", "base",
        "common", "shared", "user", "users", "inc", "llc", "ltd", "corp", "team", "labs", "dev", "open", "source",
    )
    private const val MIN_LENGTH = 4

    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The words that name an app: package segments and label words of four letters or more, less [GENERIC] ones, and
     * the whole label run together ("Layla Lite" gives "layla" and "laylalite"). A [query] you type is used as it is.
     */
    fun tokens(packageName: String? = null, label: String? = null, query: String? = null): Set<String> {
        val out = LinkedHashSet<String>()
        query?.split(Regex("[\\s,]+"))?.map(::normalize)?.filter { it.length >= 3 }?.let { out += it }
        packageName?.split('.', '_', '-')?.map(::normalize)?.filter { it.length >= MIN_LENGTH && it !in GENERIC }?.let { out += it }
        label?.let { l ->
            val words = l.split(Regex("[^\\p{L}\\p{N}]+")).map(::normalize).filter { it.length >= MIN_LENGTH && it !in GENERIC }
            out += words
            normalize(l).takeIf { it.length >= MIN_LENGTH && it !in GENERIC && it !in out }?.let { out += it }
        }
        return out
    }

    /**
     * The word most particular to the app, the first of [tokens]: its package's own name ("com.wowee.client" gives
     * "wowee", "org.telegram.messenger" gives "telegram", not "messenger"). What only matches its other words may be
     * someone else's, so only this word's matches are picked for you.
     */
    fun brand(tokens: Set<String>): Set<String> = tokens.take(1).toSet()

    fun matches(name: String, tokens: Set<String>): Boolean {
        if (tokens.isEmpty()) return false
        val n = normalize(name)
        return tokens.any { it in n }
    }

    /** Shared storage: the topmost folders and files named after the app, outside Android/ and the steward's own. */
    fun inTree(tree: StorageTree, tokens: Set<String>): List<FootprintHit> {
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<FootprintHit>()
        fun visit(dir: DirNode) {
            for (d in dir.dirs) {
                if (d.zone == Zone.APP_OWNED || d.zone == Zone.STEWARD) continue
                if (matches(d.name, tokens)) {
                    hits += FootprintHit(FootprintPlace.SHARED, d.path, d.totalBytes, d.totalFiles, true, d.mtime, lockOf(d))
                } else {
                    visit(d)
                }
            }
            for (f in dir.files) {
                if (!matches(f.name, tokens)) continue
                val lock = when {
                    dir.zone == Zone.PATH_SENSITIVE || dir.insideFlagged(NodeFlags.PROJECT_ROOT or NodeFlags.GIT_DIR) -> "Inside a project"
                    dir.zone == Zone.USER_PROTECTED -> "In a folder you pinned"
                    SafetyPolicy.isCredentialName(f.name) -> "A key or credential"
                    else -> null
                }
                hits += FootprintHit(FootprintPlace.SHARED, f.path, f.size, 1, false, f.mtime, lock)
            }
        }
        visit(tree.root)
        return hits.sortedByDescending { it.bytes }
    }

    private fun lockOf(d: DirNode): String? = when {
        d.zone == Zone.USER_PROTECTED -> "A folder you pinned"
        d.zone == Zone.PATH_SENSITIVE || d.insideFlagged(NodeFlags.PROJECT_ROOT or NodeFlags.GIT_DIR) ||
            d.subtreeHas(NodeFlags.PROJECT_ROOT or NodeFlags.GIT_DIR) -> "A project or Git repository: delete it yourself if you mean to"
        d.subtreeHas(NodeFlags.HAS_CREDENTIAL) -> "Holds a key or credential"
        d.subtreeHas(NodeFlags.HAS_SYMLINK or NodeFlags.HAS_SPECIAL or NodeFlags.UNREADABLE or NodeFlags.HAS_UNSAFE_NAME) ->
            "Holds links or files that can't be moved safely"
        else -> null
    }

    /** Termux: the topmost entries of its size map named after the app; what is kept or a package's stays locked. */
    fun inTermux(report: TermuxReport, tokens: Set<String>): List<FootprintHit> {
        if (tokens.isEmpty()) return emptyList()
        val matched = report.entries.filter { matches(it.name, tokens) }.sortedBy { it.path.length }
        val top = ArrayList<com.galaxy.steward.core.termux.TermuxEntry>()
        for (e in matched) if (top.none { e.path.startsWith(it.path + "/") }) top += e
        return top.map { e ->
            FootprintHit(FootprintPlace.TERMUX, e.path, e.bytes, 0, e.isDirectory, e.mtime, TermuxLocks.reason(e.path, report))
        }.sortedByDescending { it.bytes }
    }

    /** The shared-storage hits as clutter items the executor quarantines, checking each path again. */
    fun toJunk(hits: List<FootprintHit>, appName: String): List<JunkItem> = hits.filter { it.place == FootprintPlace.SHARED && it.lock == null }.map { h ->
        JunkItem(
            "footprint:${h.path.hashCode().toString(16)}:${h.path.length}",
            JunkCategory.ORPHANED_APP_FOLDERS,
            h.path,
            h.isDirectory,
            h.bytes,
            h.mtime,
            "Named after $appName",
        )
    }
}
