package com.galaxy.steward.core.organize

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.DeviceEnvironment
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.Insight
import com.galaxy.steward.core.plan.MoveDirOp
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.Operation
import com.galaxy.steward.core.plan.OrganizeMove
import com.galaxy.steward.core.plan.Severity
import java.time.Instant
import java.time.ZoneId

class OrganizePlan(val moves: List<OrganizeMove>, val insights: List<Insight>)

/**
 * Semantic topology planner (v18.1): Download is an inbox, durable content gets a meaningful permanent home
 * under Documents or the native media folders. Folders move as units, keeping their names; project trees,
 * credentials, symlinks and anything touched in the last few minutes stay exactly where they are.
 */
class OrganizePlanner(
    private val settings: StewardSettings,
    private val environment: DeviceEnvironment,
) {
    private val rules: List<KeywordRule> = settings.customRules + BuiltInRules.rules
    private val deviceLabel = environment.deviceLabel
    private val now = environment.nowMillis()
    private val recentCutoff = now - settings.recentFileGuardMinutes * 60_000L

    private lateinit var rootPath: String
    private val moves = ArrayList<OrganizeMove>()
    private val insights = ArrayList<Insight>()
    private val plannedSources = HashSet<String>()

    fun plan(tree: StorageTree): OrganizePlan {
        rootPath = tree.rootPath
        moves.clear()
        insights.clear()
        plannedSources.clear()

        tree.find("Download")?.takeIf { it.zone == Zone.USER_MANAGED }?.let { download ->
            for (f in download.files) planFile(f)
            for (d in download.dirs) planFolder(d, allowReview = true)
        }
        tree.find("Documents")?.takeIf { it.zone == Zone.USER_MANAGED }?.let { documents ->
            for (f in documents.files) planFile(f)
            for (d in documents.dirs) planDocumentsChild(d)
            planHomeChildren(documents)
        }
        planTopLevel(tree.root)
        return OrganizePlan(moves.sortedWith(compareBy({ it.destinationFolder }, { it.source })), insights.toList())
    }

    // ------------------------------------------------------------------ files

    /** Where a loose file belongs, with a human reason. Null means "leave it". */
    fun destinationFor(file: FileNode): Pair<String, String>? {
        val name = file.name
        val kind = file.kind
        val media = kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO
        for (rule in rules) {
            // Media stays in the media library unless a rule explicitly claims its extension (e.g. receipts).
            if (media && rule.extensions.isEmpty()) continue
            if (rule.matchesFile(name)) return rule.resolvedDestination(deviceLabel) to rule.name
        }
        val lower = Text.normalize(name)
        when (kind) {
            FileKind.IMAGE -> return if (lower.contains(" screenshot")) {
                "Pictures/Screenshots" to "Screenshot"
            } else {
                mediaHome("Pictures/Imported", file) to "Image"
            }
            FileKind.VIDEO -> return if (lower.contains(" screen record") || lower.contains(" screenrecord")) {
                "Movies/Screen-Recordings" to "Screen recording"
            } else {
                mediaHome("Movies/Imported", file) to "Video"
            }
            FileKind.AUDIO -> return if (file.extension in setOf("amr", "3ga") || lower.contains(" voice") || lower.contains(" recording") || lower.contains(" call ")) {
                "Recordings/Imported" to "Voice recording"
            } else {
                "Music/Imported" to "Audio"
            }
            else -> Unit
        }
        BuiltInRules.extensionDestination(file.extension)?.let { return it }
        return "Documents/Inbox-Review" to "Unrecognised type"
    }

    private fun mediaHome(base: String, file: FileNode): String =
        if (settings.mediaYearBuckets) "$base/${yearOf(file.mtime)}" else base

    private fun planFile(file: FileNode) {
        if (!fileMovable(file)) return
        val (destDir, reason) = destinationFor(file) ?: return
        val destination = "$rootPath/$destDir/${file.name}"
        if (destination.substringBeforeLast('/') == file.dir.path) return
        addMove(
            source = file.path,
            destination = destination,
            isDirectory = false,
            bytes = file.size,
            fileCount = 1,
            reason = reason,
            group = destination.substringBeforeLast('/'),
            selected = reason != "Unrecognised type",
            op = MoveFileOp(file.path, destination, file.size, file.mtime),
        )
    }

    private fun fileMovable(file: FileNode): Boolean {
        if (file.hidden || SafetyPolicy.isMarkerFile(file.name) || SafetyPolicy.isInProgressDownload(file.name)) return false
        // Keys never move; the optimizer's storage-health advice says where they are, all in one place.
        if (SafetyPolicy.isCredentialName(file.name)) return false
        if (file.mtime > recentCutoff) return false
        if (file.zone != Zone.USER_MANAGED) return false
        return true
    }

    // ------------------------------------------------------------------ folders inside the homes

    private fun isHome(dir: DirNode) = BuiltInRules.isHome(dir.relPath, deviceLabel, settings.customRules)

    /**
     * Folders the organizer's own homes collected that belong elsewhere, seen on a real phone:
     * `Documents/Archives/ViPER4Android-Presets` is audio presets, `Documents/Audio-DSP/leakcanary-…` is a diagnostics
     * dump, and `Documents/Software/APKs/apk` is the home itself again. Only folders directly inside a home are looked
     * at; your own folders elsewhere in Documents stay yours. A folder named after its own home merges into it; the
     * rest are only suggested.
     */
    private fun planHomeChildren(documents: DirNode) {
        val stack = ArrayDeque(listOf(documents))
        while (stack.isNotEmpty()) {
            val home = stack.removeLast()
            for (child in home.dirs) {
                if (child.zone != Zone.USER_MANAGED || child.hidden || child.totalFiles == 0) continue
                if (isHome(child)) {
                    stack.addLast(child)
                    continue
                }
                if (home === documents) continue // Documents' own children: planDocumentsChild
                val category = BuiltInRules.categoryFolderDestination(child.name)
                val rule = rules.firstOrNull { it.matchesFolder(child.name) }?.takeIf { r ->
                    val dest = r.resolvedDestination(deviceLabel)
                    dest != home.relPath && !home.relPath.startsWith("$dest/")
                }
                when {
                    category == home.relPath ->
                        planFolderTo(child, home.path, "Category folder inside its own home (merged)", merge = true, selected = true)
                    category != null && !home.relPath.startsWith("$category/") ->
                        planFolderTo(child, "$rootPath/$category", "Category folder → its home (merged)", merge = true, selected = false)
                    rule != null ->
                        planFolderTo(child, "$rootPath/${rule.resolvedDestination(deviceLabel)}/${child.name}", "Filed under a better home: ${rule.name}", merge = false, selected = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ top-level folders

    private val appFolders by lazy { AppFolderIndex(environment.installedApps()) }

    /**
     * Folders at the top of shared storage besides Android's own. Those of installed apps stay: apps write to them by
     * path. The rest (your own folders, unpacked downloads, leftovers of removed apps) get the home their name or
     * content points to. They are only suggested: something may still write to them, and they are yours to decide on.
     */
    private fun planTopLevel(root: DirNode) {
        val owned = ArrayList<String>()
        for (d in root.dirs) {
            if (d.zone != Zone.OTHER_SHARED || d.hidden || d.totalFiles == 0 || SafetyPolicy.isPackageLikeName(d.name)) continue
            // Samsung writes its dumpstate logs to /log; the clutter check looks after old ones.
            if (SafetyPolicy.isKeptTopDir(d.name)) continue
            val owner = appFolders.owner(d.name)
            if (owner != null) {
                owned += if (owner.equals(d.name, ignoreCase = true)) d.name else "${d.name} ($owner)"
                continue
            }
            var newest = d.mtime
            d.walkFiles { if (it.mtime > newest) newest = it.mtime }
            if (newest > now - IN_USE_DAYS * DAY_MS) {
                insights.add(Insight(Severity.INFO, "Left in place: ${d.name}", "Something wrote to this folder in the last $IN_USE_DAYS days, so it may still be in use.", d.path))
                continue
            }
            val category = BuiltInRules.categoryFolderDestination(d.name)
            val rule = rules.firstOrNull { it.matchesFolder(d.name) }
            when {
                category != null -> planFolderTo(d, "$rootPath/$category", "Top-level folder → its category (merged)", merge = true, selected = false)
                rule != null -> planFolderTo(d, "$rootPath/${rule.resolvedDestination(deviceLabel)}/${d.name}", "Top-level folder: ${rule.name}", merge = false, selected = false)
                else -> inferFromContent(d)?.let { (home, reason) ->
                    planFolderTo(d, "$rootPath/$home/${d.name}", "Top-level folder: ${reason.lowercase()}", merge = false, selected = false)
                }
            }
        }
        if (owned.isNotEmpty()) {
            // First: the report keeps only the first few dozen of these notes.
            insights.add(
                0,
                Insight(
                    Severity.INFO,
                    "${owned.size} top-level ${if (owned.size == 1) "folder belongs" else "folders belong"} to installed apps",
                    "They stay where the apps expect them: ${owned.sorted().joinToString(", ")}.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------ folders

    private fun planDocumentsChild(dir: DirNode) {
        if (dir.zone != Zone.USER_MANAGED || dir.hidden || dir.totalFiles == 0) return
        when {
            // Only true wrappers dissolve inside Documents; a user's own "Misc" folder is left alone.
            SafetyPolicy.isDocumentsWrapperName(dir.name) -> walkWrapper(dir)
            dir.name == "Inbox-Review" -> dir.files.forEach(::planFile)
            else -> {
                // Existing Documents folders are already user-organized. Only a folder whose name *is* a category
                // with a different canonical home (Documents/APKs -> Documents/Software/APKs) is re-homed.
                val home = BuiltInRules.categoryFolderDestination(dir.name) ?: return
                if ("$rootPath/$home" != dir.path) planFolderTo(dir, "$rootPath/$home", "Category folder → canonical home", merge = true, selected = true)
            }
        }
    }

    private fun planFolder(dir: DirNode, allowReview: Boolean) {
        if (dir.hidden) return
        if (dir.zone == Zone.PATH_SENSITIVE) {
            leftInPlace(dir, if (dir.insideFlagged(NodeFlags.CODE_TREE)) "holds source code or a decompiled app" else "is a project or Git repository")
            return
        }
        // Empty folders belong to the clutter cleanup, not to filing.
        if (dir.zone != Zone.USER_MANAGED || dir.totalFiles == 0) return
        if (SafetyPolicy.isGenericWrapperName(dir.name)) {
            walkWrapper(dir)
            return
        }
        BuiltInRules.categoryFolderDestination(dir.name)?.let { home ->
            planFolderTo(dir, "$rootPath/$home", "Category folder → canonical home", merge = true, selected = true)
            return
        }
        for (rule in rules) {
            if (rule.matchesFolder(dir.name)) {
                planFolderTo(dir, "$rootPath/${rule.resolvedDestination(deviceLabel)}/${dir.name}", rule.name, merge = false, selected = true)
                return
            }
        }
        inferFromContent(dir)?.let { (home, reason) ->
            planFolderTo(dir, "$rootPath/$home/${dir.name}", reason, merge = false, selected = true)
            return
        }
        if (allowReview) {
            planFolderTo(dir, "$rootPath/Documents/Inbox-Review/${dir.name}", "Mixed content - review", merge = false, selected = false)
        }
    }

    /** Generic wrappers (Documents/Documents, Download/Quick Share, "New folder") dissolve: children are filed individually. */
    private fun walkWrapper(dir: DirNode) {
        for (f in dir.files) planFile(f)
        for (d in dir.dirs) planFolder(d, allowReview = true)
    }

    /** A folder is filed by what it holds when one kind dominates (80% of bytes). */
    private fun inferFromContent(dir: DirNode): Pair<String, String>? {
        if (dir.totalFiles == 0) return null
        val bytesByKind = HashMap<FileKind, Long>()
        dir.walkFiles { bytesByKind[it.kind] = (bytesByKind[it.kind] ?: 0L) + maxOf(it.size, 1L) }
        val total = bytesByKind.values.sum()
        val (kind, bytes) = bytesByKind.maxByOrNull { it.value } ?: return null
        if (bytes * 10 < total * 8) return null
        return when (kind) {
            FileKind.IMAGE -> "Pictures/Imported" to "Mostly images"
            FileKind.VIDEO -> "Movies/Imported" to "Mostly videos"
            FileKind.AUDIO -> "Music/Imported" to "Mostly audio"
            FileKind.APK -> "Documents/Software/APKs" to "Mostly app installers"
            FileKind.DOCUMENT -> "Documents" to "Mostly documents"
            FileKind.ARCHIVE -> "Documents/Archives" to "Mostly archives"
            FileKind.CODE -> "Documents/Development" to "Mostly code"
            FileKind.OTHER -> null
        }
    }

    private fun planFolderTo(dir: DirNode, destination: String, reason: String, merge: Boolean, selected: Boolean) {
        if (destination == dir.path || destination.startsWith(dir.path + "/")) return
        if (dir.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)) {
            val why = when {
                dir.subtreeHas(NodeFlags.PROJECT_ROOT or NodeFlags.GIT_DIR) -> "contains a project or Git repository"
                dir.subtreeHas(NodeFlags.CODE_TREE) -> "contains source code or a decompiled app"
                dir.subtreeHas(NodeFlags.HAS_CREDENTIAL) -> "contains keys or credentials"
                dir.subtreeHas(NodeFlags.HAS_SYMLINK) -> "contains symbolic links"
                else -> "contains unreadable or special entries"
            }
            leftInPlace(dir, why)
            return
        }
        var newest = dir.mtime
        dir.walkFiles { if (it.mtime > newest) newest = it.mtime }
        if (newest > recentCutoff) return
        addMove(
            source = dir.path,
            destination = destination,
            isDirectory = true,
            bytes = dir.totalBytes,
            fileCount = dir.totalFiles,
            reason = if (merge) "$reason (merged)" else reason,
            group = if (merge) destination else destination.substringBeforeLast('/'),
            selected = selected,
            op = MoveDirOp(dir.path, destination, allowRename = true),
        )
    }

    private fun leftInPlace(dir: DirNode, why: String) {
        insights.add(Insight(Severity.INFO, "Left in place: ${dir.name}", "This folder $why, so it keeps its exact path.", dir.path))
    }

    private fun addMove(
        source: String,
        destination: String,
        isDirectory: Boolean,
        bytes: Long,
        fileCount: Int,
        reason: String,
        group: String,
        selected: Boolean,
        op: Operation,
    ) {
        if (!plannedSources.add(source)) return
        moves.add(
            OrganizeMove(
                id = "org:" + source.hashCode().toString(16) + ":" + source.length,
                source = source,
                destination = destination,
                isDirectory = isDirectory,
                bytes = bytes,
                fileCount = fileCount,
                reason = reason,
                destinationFolder = group,
                defaultSelected = selected,
                operations = listOf(op),
            ),
        )
    }

    companion object {
        /** A top-level folder written to this recently may still be some app's working folder. */
        const val IN_USE_DAYS = 14

        fun yearOf(mtime: Long): Int = Instant.ofEpochMilli(mtime).atZone(ZoneId.systemDefault()).year
    }
}
