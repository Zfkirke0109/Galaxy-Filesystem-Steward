package com.galaxy.steward.core.device

import com.galaxy.steward.core.SafetyPolicy
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Space only Android's shell user can see, reached through the Shizuku helper: what adb pushed to /data/local/tmp and
 * the bug reports Android keeps. [lock] says why an entry can't be picked (a tool other apps start from there, or
 * something changed minutes ago); [kind] says what it is.
 */
data class ShellEntry(val path: String, val bytes: Long, val mtime: Long, val isDirectory: Boolean, val lock: String?, val kind: String) {
    val name: String get() = path.substringAfterLast('/')
}

data class ShellRemoval(val path: String, val status: String, val bytes: Long, val note: String) {
    val removed: Boolean get() = status == "REMOVED"
}

object ShellSpace {
    const val LOCAL_TMP = "/data/local/tmp"
    const val BUGREPORTS = "/data/user_de/0/com.android.shell/files/bugreports"
    val ROOTS = listOf(LOCAL_TMP, BUGREPORTS)
    const val END = "END"

    /** What Shizuku, rish, Brevent, Frida, Magisk and their kind start from /data/local/tmp: never offered. */
    private val TOOLS = Regex("""(?i)shizuku|rish|brevent|frida|magisk|lsposed|kernelsu|ksud|apatch|starter""")
    private const val RECENT_MS = 10 * 60_000L

    fun lockOf(name: String, mtime: Long, now: Long): String? = when {
        TOOLS.containsMatchIn(name) -> "A tool other apps start from here (Shizuku, rish, Frida...)"
        SafetyPolicy.isCredentialName(name) -> "A key or credential"
        now - mtime < RECENT_MS -> "Changed in the last 10 minutes: it may be in use"
        else -> null
    }

    fun kindOf(root: String, name: String, isDirectory: Boolean): String = when {
        root.endsWith("/bugreports") || name.startsWith("bugreport-") || name.startsWith("dumpstate") -> "Bug report"
        isDirectory -> "Folder pushed with adb"
        name.endsWith(".apk", ignoreCase = true) || name.endsWith(".apks", ignoreCase = true) -> "Installer pushed with adb"
        name.endsWith(".zip", ignoreCase = true) || name.endsWith(".tar", ignoreCase = true) || name.contains(".tar.") -> "Archive pushed with adb"
        name.endsWith(".log", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true) -> "Log or text"
        else -> "File pushed with adb"
    }

    /** Helper side: every entry directly inside each root, with its whole size. Unreadable roots are listed by path. */
    fun scan(roots: List<String> = ROOTS, now: Long = System.currentTimeMillis()): Pair<List<ShellEntry>, List<String>> {
        val entries = ArrayList<ShellEntry>()
        val unreadable = ArrayList<String>()
        for (root in roots) {
            val dir = Paths.get(root)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
            val children = try {
                Files.newDirectoryStream(dir).use { it.toList() }
            } catch (_: Exception) {
                unreadable += root
                continue
            }
            for (child in children) {
                val attrs = try {
                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    continue
                }
                if (attrs.isSymbolicLink || attrs.isOther) continue
                val name = child.fileName.toString()
                val (bytes, newest) = if (attrs.isDirectory) measure(child) else attrs.size() to attrs.lastModifiedTime().toMillis()
                val mtime = maxOf(newest, attrs.lastModifiedTime().toMillis())
                entries += ShellEntry(child.toString(), bytes, mtime, attrs.isDirectory, lockOf(name, mtime, now), kindOf(root, name, attrs.isDirectory))
            }
        }
        return entries.sortedByDescending { it.bytes } to unreadable
    }

    /** Bytes of the regular files below [dir] and the newest change among them, never following a link. */
    private fun measure(dir: Path): Pair<Long, Long> {
        var bytes = 0L
        var newest = 0L
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        bytes += attrs.size()
                        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // What could be read counts.
        }
        return bytes to newest
    }

    /**
     * Helper side: removes each of [paths] after checking it again: directly inside one of [roots], not a link, and
     * not locked now. Nothing below a folder is followed through a link.
     */
    fun remove(paths: List<String>, roots: List<String> = ROOTS, now: Long = System.currentTimeMillis()): List<ShellRemoval> = paths.map { p ->
        val path = Paths.get(p).normalize()
        val root = roots.firstOrNull { path.parent == Paths.get(it) }
        when {
            root == null || path.toString() != p -> ShellRemoval(p, "SKIP_UNSAFE", 0, "not directly inside ${roots.joinToString(" or ")}")
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> ShellRemoval(p, "NO_CHANGE", 0, "already gone")
            Files.isSymbolicLink(path) -> ShellRemoval(p, "SKIP_UNSAFE", 0, "a link")
            else -> {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val (bytes, newest) = if (attrs.isDirectory) measure(path) else attrs.size() to attrs.lastModifiedTime().toMillis()
                val lock = lockOf(path.fileName.toString(), maxOf(newest, attrs.lastModifiedTime().toMillis()), now)
                if (lock != null) {
                    ShellRemoval(p, "SKIP_LOCKED", 0, lock)
                } else {
                    deleteTree(path)
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) ShellRemoval(p, "PARTIAL", 0, "some files could not be removed")
                    else ShellRemoval(p, "REMOVED", bytes, "")
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.delete(dir) }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            // Reported by the caller: the path is still there.
        }
    }

    // ------------------------------------------------------------------ wire (helper <-> app)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    /** The helper's answer to [request]: "list", or "remove" and one path per line. */
    fun handle(request: String, out: (String) -> Unit, roots: List<String> = ROOTS) {
        try {
            val lines = request.lines().filter { it.isNotEmpty() }
            when (lines.firstOrNull()) {
                "list" -> {
                    val (entries, unreadable) = scan(roots)
                    entries.forEach { e ->
                        out(listOf("S", e.bytes, e.mtime, if (e.isDirectory) "d" else "f", e.path, e.lock.orEmpty(), e.kind).joinToString("\t") { clean(it.toString()) })
                    }
                    unreadable.forEach { out("U\t" + clean(it)) }
                }
                "remove" -> remove(lines.drop(1), roots).forEach { r -> out(listOf("D", r.status, r.bytes, r.path, r.note).joinToString("\t") { clean(it.toString()) }) }
                else -> out("E\tunknown request")
            }
        } catch (e: Exception) {
            out("E\t" + clean(e.message ?: e.javaClass.simpleName))
        }
        out(END)
    }

    fun readList(lines: Sequence<String>): Pair<List<ShellEntry>, List<String>> {
        val entries = ArrayList<ShellEntry>()
        val unreadable = ArrayList<String>()
        read(lines) { p ->
            when (p[0]) {
                "S" -> if (p.size >= 7) entries += ShellEntry(p[4], p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3] == "d", p[5].ifEmpty { null }, p[6])
                "U" -> if (p.size >= 2) unreadable += p[1]
            }
        }
        return entries to unreadable
    }

    fun readRemoval(lines: Sequence<String>): List<ShellRemoval> {
        val results = ArrayList<ShellRemoval>()
        read(lines) { p -> if (p[0] == "D" && p.size >= 5) results += ShellRemoval(p[3], p[1], p[2].toLongOrNull() ?: 0, p[4]) }
        return results
    }

    private fun read(lines: Sequence<String>, each: (List<String>) -> Unit) {
        var ended = false
        for (line in lines) {
            if (line == END) {
                ended = true
                break
            }
            val p = line.split('\t')
            if (p[0] == "E") throw IOException(p.getOrElse(1) { "Helper error" })
            each(p)
        }
        if (!ended) throw IOException("The helper stopped before finishing")
    }
}

/**
 * Android's own account of the phone's storage (`dumpsys diskstats`): free space, and what apps, their data and caches,
 * photos, videos, audio, downloads and the system take. [other] is what none of those explain. Android refreshes the
 * category sizes about once a day, so they can lag behind; [total] and [free] are live.
 */
data class DiskStats(val total: Long, val free: Long, val parts: Map<String, Long>) {
    val measured: Boolean get() = parts.isNotEmpty()
    val used: Long get() = (total - free).coerceAtLeast(0)

    companion object {
        /** The categories in Android's order, with the names shown for them. */
        val LABELS = linkedMapOf(
            "App Size" to "Apps",
            "App Data Size" to "App data",
            "App Cache Size" to "App caches",
            "Photos Size" to "Photos",
            "Videos Size" to "Videos",
            "Audio Size" to "Audio",
            "Downloads Size" to "Downloads",
            "System Size" to "System",
            "Other Size" to "Other",
        )
        private val FREE = Regex("""Data-Free:\s*(\d+)K\s*/\s*(\d+)K total""")
        private val PART = Regex("""^(App Size|App Data Size|App Cache Size|Photos Size|Videos Size|Audio Size|Downloads Size|System Size|Other Size):\s*(\d+)\s*$""")

        fun parse(text: String): DiskStats? {
            val free = FREE.find(text) ?: return null
            val parts = LinkedHashMap<String, Long>()
            text.lineSequence().forEach { line -> PART.find(line.trim())?.let { parts[LABELS.getValue(it.groupValues[1])] = it.groupValues[2].toLong() } }
            return DiskStats(free.groupValues[2].toLong() * 1024, free.groupValues[1].toLong() * 1024, parts)
        }
    }
}

/** One file or folder in a debuggable app's private data, [rel] to its data folder, with what it likely is. */
data class PrivateEntry(val rel: String, val bytes: Long, val label: String?) {
    val depth: Int get() = rel.count { it == '/' }
    val name: String get() = rel.substringAfterLast('/')
}

/**
 * Private data (/data/data/<package>) of debuggable apps, through `run-as`: Android lets the shell user in only for
 * builds marked debuggable, such as your own debug builds (a UserLAnd fork, say). Relative paths only, checked here and
 * again in the helper.
 */
object PrivateData {
    const val MIN_BYTES = 1L shl 20

    /** A path the helper may hand to run-as: inside the data folder, no way out of it, not the folder itself or lib. */
    fun validRel(rel: String): Boolean {
        if (rel.isEmpty() || rel.startsWith("/") || rel.startsWith("-") || rel.any { it < ' ' }) return false
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return parts[0] != "lib"
    }

    /**
     * `du -a -k -d 3 .` output ("123\t./files/x") as entries: everything one level in, and deeper ones of 1 MiB or more.
     */
    fun parseDu(pkg: String, lines: Sequence<String>): List<PrivateEntry> = lines.mapNotNull { line ->
        val tab = line.indexOfFirst { it == '\t' || it == ' ' }
        if (tab <= 0) return@mapNotNull null
        val kib = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
        val rel = line.substring(tab + 1).trim().removePrefix("./")
        if (rel == "." || !validRel(rel)) return@mapNotNull null
        PrivateEntry(rel, kib * 1024, label(pkg, rel))
    }.filter { it.depth == 0 || it.bytes >= MIN_BYTES }.sortedByDescending { it.bytes }.toList()

    fun isUserLand(pkg: String): Boolean = pkg == "tech.ula" || pkg.startsWith("tech.ula.")

    private val ARCHIVE = Regex("""(?i).*\.(tar\.gz|tgz|tar\.xz|txz|tar)$""")
    private val MODEL = Regex("""(?i).*\.(gguf|ggml|safetensors|onnx|tflite|pte|mlmodel|bin)$""")

    fun label(pkg: String, rel: String): String? {
        val name = rel.substringAfterLast('/')
        val top = rel.substringBefore('/')
        if (isUserLand(pkg)) {
            when {
                Regex("""^files/\d+$""").matches(rel) -> return "A Linux filesystem: delete it in UserLAnd if you no longer use it"
                rel == "files/support" -> return "UserLAnd's own tools (proot, busybox): it needs them"
                rel.startsWith("files/") && ARCHIVE.matches(name) -> return "A downloaded filesystem archive: it was unpacked, so it can go"
            }
        }
        return when {
            top == "cache" || top == "code_cache" -> "Cache: the app rebuilds it"
            top == "databases" -> "Databases: what the app saved"
            top == "shared_prefs" -> "Settings"
            top == "no_backup" -> "Data the app keeps out of backups"
            MODEL.matches(name) && !name.endsWith(".bin") -> "An AI model"
            ARCHIVE.matches(name) -> "An archive"
            else -> null
        }
    }
}

/**
 * Apps that keep most of what they store where only they can reach it, with what to do about it: seen from outside,
 * it is one number.
 */
data class AppHint(val name: String, val text: String)

object KnownApps {
    fun hint(pkg: String): AppHint? = when {
        pkg == "com.layla" || pkg.startsWith("com.layla.") -> AppHint(
            "Layla",
            "Layla keeps the AI models you download, and your chats, in its private storage: that is most of its app data. " +
                "Delete models you no longer use from Layla's own model list; nothing else can reach them. The relays its " +
                "Sidekick mini apps use in Termux are always kept.",
        )
        PrivateData.isUserLand(pkg) -> AppHint(
            "UserLAnd",
            "Each UserLAnd filesystem is a whole Linux system in its private storage. Delete the filesystems and sessions you " +
                "no longer use inside the app (long-press one, then Delete). A debuggable build can be browsed and cleaned here.",
        )
        else -> null
    }
}
