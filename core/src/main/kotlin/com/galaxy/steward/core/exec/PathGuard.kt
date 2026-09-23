package com.galaxy.steward.core.exec

import com.galaxy.steward.core.SafetyPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Run-time path checks, re-applied immediately before every mutation (the plan may be minutes old):
 * canonical absolute paths only, no symlinked components, never app-owned storage, the quarantine, pinned
 * folders or credential-like names. Ported from `safe_absolute_path_v18` / `path_resolves_beneath_v18`.
 */
class PathGuard(rootPath: String, protectedFolders: List<String> = emptyList()) {
    val root: Path = Paths.get(rootPath.trimEnd('/'))
    val rootString: String = root.toString()
    private val realRoot: Path? = try {
        root.toRealPath()
    } catch (_: IOException) {
        null
    }
    private val protectedPrefixes = protectedFolders.map { it.trim().trim('/') }.filter { it.isNotEmpty() }
    val stewardDir: Path = root.resolve(SafetyPolicy.STEWARD_DIR)
    val quarantineRoot: Path = stewardDir.resolve(SafetyPolicy.QUARANTINE_DIR)

    /** Syntactic check: absolute, strictly beneath the root, no `.`/`..`/`//` segments or control characters. */
    fun wellFormed(path: String): Boolean {
        if (!path.startsWith("$rootString/") || path.length <= rootString.length + 1) return false
        if (SafetyPolicy.isUnsafeName(path)) return false
        if (path.endsWith("/") || path.contains("//")) return false
        return path.substring(rootString.length + 1).split('/').none { it == "." || it == ".." || it.isEmpty() }
    }

    fun relative(path: String): String = path.removePrefix("$rootString/")

    /** Reason this path must never be mutated, or null when it may be. */
    fun protectionReason(path: String): String? {
        if (!wellFormed(path)) return "unsafe or out-of-root path"
        val rel = relative(path)
        val top = rel.substringBefore('/')
        if (top == "Android") return "app-owned storage"
        if (top == SafetyPolicy.STEWARD_DIR) return "steward quarantine"
        if (protectedPrefixes.any { rel == it || rel.startsWith("$it/") }) return "pinned folder"
        if (SafetyPolicy.isCredentialName(rel.substringAfterLast('/'))) return "credential-like name"
        return null
    }

    /** True when every existing component resolves to itself (no symlink anywhere between root and path). */
    fun resolvesBeneath(path: Path): Boolean {
        val base = realRoot ?: return false
        return try {
            val rel = root.relativize(path)
            path.toRealPath() == base.resolve(rel)
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun isRealDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    /**
     * Creates missing directories between the root and [dir], one component at a time, refusing to pass
     * through anything that is not a real directory. [onCreated] is called for each directory created.
     */
    fun ensureDirectories(dir: Path, onCreated: (Path) -> Unit): Boolean {
        if (!dir.startsWith(root)) return false
        var current = root
        for (part in root.relativize(dir)) {
            current = current.resolve(part.toString())
            if (exists(current)) {
                if (!isRealDirectory(current)) return false
            } else {
                try {
                    Files.createDirectory(current)
                } catch (_: IOException) {
                    if (!isRealDirectory(current)) return false
                    continue
                }
                onCreated(current)
            }
        }
        return resolvesBeneath(dir)
    }

    /** "name.ext" -> "name-<hash8>.ext", then "name-<hash8>-1.ext", ... until free (as in the Termux steward). */
    fun uniqueCollisionPath(dst: String, sha256: String): String {
        val dir = dst.substringBeforeLast('/')
        val base = dst.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) base.substring(0, dot) to base.substring(dot) else base to ""
        var candidate = "$dir/$stem-${sha256.take(8)}$ext"
        var n = 1
        while (exists(Paths.get(candidate))) {
            candidate = "$dir/$stem-${sha256.take(8)}-$n$ext"
            n++
        }
        return candidate
    }
}
