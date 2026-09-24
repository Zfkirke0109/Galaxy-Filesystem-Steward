package com.galaxy.steward.core.optimize

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.DeviceEnvironment
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.organize.BuiltInRules
import com.galaxy.steward.core.organize.Text
import com.galaxy.steward.core.plan.Insight
import com.galaxy.steward.core.plan.MoveDirOp
import com.galaxy.steward.core.plan.MoveFileOp
import com.galaxy.steward.core.plan.Operation
import com.galaxy.steward.core.plan.QuarantineOp
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.RemoveEmptyDirOp
import com.galaxy.steward.core.plan.Severity
import java.time.Instant
import java.time.ZoneId

class OptimizePlan(val items: List<OptimizeItem>, val insights: List<Insight>)

/** Free/total bytes of the storage volume, when the platform can tell us. */
data class VolumeSpace(val freeBytes: Long, val totalBytes: Long)

/**
 * Layout optimisations that make storage faster to browse and index: collapsing redundant nested folders
 * (typical of extracted archives) and splitting huge flat folders into year buckets, plus report-only
 * health insights such as low free space, which slows flash storage down.
 */
class OptimizePlanner(
    private val settings: StewardSettings,
    private val environment: DeviceEnvironment,
) {
    private val recentCutoff = environment.nowMillis() - settings.recentFileGuardMinutes * 60_000L

    fun plan(tree: StorageTree, space: VolumeSpace?): OptimizePlan {
        val items = ArrayList<OptimizeItem>()
        val insights = ArrayList<Insight>()
        val flatReportOnly = ArrayList<DirNode>()
        var deepest: FileNode? = null
        var deepCount = 0
        val keys = ArrayList<FileNode>()
        var codeFiles = 0
        val codeByFolder = HashMap<String, Int>()

        // Downloaded build outputs first: an empty-chain collapse inside one would fight over the same folders.
        val artifacts = ArrayList<OptimizeItem>()
        tree.root.walkDirs { dir ->
            if (dir.zone == Zone.USER_MANAGED && dir.name == "outputs" && dir.parent?.name == "build") buriedBuildOutputs(dir)?.let(artifacts::add)
        }
        items += artifacts
        val artifactRoots = artifacts.map { it.path + "/" }

        tree.root.walkDirs { dir ->
            if (!dir.zone.durable) return@walkDirs
            datedSourceFolder(dir)?.let(items::add)
            flattenCandidate(dir)?.let(items::add)
            if (artifactRoots.none { (dir.path + "/").startsWith(it) || it.startsWith(dir.path + "/") }) emptyChain(dir)?.let(items::add)
            // Source code is flat and deep by nature (packages); saying so about every decompiled app is only noise.
            val code = insideCodeTree(dir) || dir.insideFlagged(NodeFlags.CODE_TREE or NodeFlags.PROJECT_ROOT)
            if (code && dir.files.isNotEmpty()) {
                codeFiles += dir.files.size
                val top = dir.relPath.split('/').take(2).joinToString("/")
                codeByFolder[top] = (codeByFolder[top] ?: 0) + dir.files.size
            }
            // Certificates inside decompiled apps and projects are part of that code, not your keys.
            if (!code && dir.hasFlag(NodeFlags.HAS_CREDENTIAL)) dir.files.filterTo(keys) { SafetyPolicy.isCredentialName(it.name) }
            if (dir.files.size > settings.flatDirThreshold) {
                val bucket = bucketCandidate(dir)
                if (bucket != null) items.add(bucket) else if (!dir.hidden && !code) flatReportOnly.add(dir)
            }
            if (!code) for (f in dir.files) {
                if (f.depth > 12 || f.path.length > 240) {
                    deepCount++
                    if (deepest == null || f.depth > deepest!!.depth) deepest = f
                }
            }
        }

        space?.let { insights += spaceInsight(it) }
        if (keys.isNotEmpty()) {
            val names = keys.groupBy { it.name }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (name, same) -> if (same.size > 1) "$name (${same.size})" else name }
            insights += Insight(
                Severity.ADVICE,
                "${keys.size} key or credential ${if (keys.size == 1) "file sits" else "files sit"} in shared storage",
                "Any app with All files access can read ${if (keys.size == 1) "it" else "them"}: ${names.take(8).joinToString(", ")}" +
                    (if (names.size > 8) " and ${names.size - 8} more" else "") + ". The steward never moves keys, because build " +
                    "scripts point at them by path. Keeping them in one folder you pin in Settings, or in Termux's private home, " +
                    "makes them easier to find and harder to leak.",
                keys.first().path,
            )
        }
        // A big /log with nothing old enough in it: Samsung keeps a rolling set there, so say why none of it is clutter.
        tree.find("log")?.takeIf { it.totalBytes >= 500 * MIB }?.let { log ->
            var oldest = Long.MAX_VALUE
            log.walkFiles { if (it.mtime < oldest) oldest = it.mtime }
            val days = (environment.nowMillis() - oldest) / DAY_MS
            if (days < settings.oldLogDays) {
                insights += Insight(
                    Severity.INFO,
                    "log holds ${log.totalBytes.humanBytes()}, none of it ${settings.oldLogDays} days old",
                    "Samsung keeps a rolling set of Wi-Fi, ewlogd and dump logs in /log and writes new ones as old ones go, so it " +
                        "fills up again after a clean-up. Logs count as clutter once they are ${settings.oldLogDays} days old " +
                        "(Settings → System logs are old after).",
                    log.path,
                )
            }
        }
        val totalFiles = tree.root.totalFiles
        if (codeFiles >= 20_000 && codeFiles * 2 >= totalFiles) {
            val (folder, inFolder) = codeByFolder.maxByOrNull { it.value }!!.toPair()
            insights += Insight(
                Severity.ADVICE,
                "${codeFiles * 100 / totalFiles}% of your files are source code",
                "${"%,d".format(codeFiles)} of ${"%,d".format(totalFiles)} files are projects and decompiled apps " +
                    "(${"%,d".format(inFolder)} in $folder). Shared storage goes through Android's storage layer (FUSE), which is " +
                    "slow with many small files: jadx, apktool, Git and every scan run several times faster on these trees inside " +
                    "Termux's own home. The steward never moves them itself, because your tools know them by path.",
                tree.find(folder)?.path,
            )
        }
        for (dir in flatReportOnly.sortedByDescending { it.files.size }.take(5)) {
            insights += Insight(
                Severity.INFO,
                "${dir.files.size} files in one folder",
                "${dir.relPath} is very flat. Apps that own it expect this layout, so the steward leaves it alone, " +
                    "but galleries and file managers list it more slowly.",
                dir.path,
            )
        }
        deepest?.let {
            insights += Insight(
                Severity.ADVICE,
                "$deepCount deeply nested file" + if (deepCount == 1) "" else "s",
                "Very deep or long paths are slow to browse and break some apps. Deepest: ${it.relPath}",
                it.path,
            )
        }
        tree.find("Download")?.let { download ->
            val loose = download.files.count { !it.hidden }
            if (loose >= 200) {
                insights += Insight(Severity.ADVICE, "$loose loose files in Download", "Smart Organize can file them into meaningful folders.", download.path)
            }
        }
        tree.root.dirs.filter { it.hidden && it.zone == Zone.OTHER_SHARED && it.totalBytes >= 100 * MIB }
            .sortedByDescending { it.totalBytes }.take(3)
            .forEach {
                insights += Insight(Severity.INFO, "Hidden folder ${it.name} uses ${it.totalBytes.humanBytes()}", "Hidden app data in shared storage. Review it in the storage map.", it.path)
            }
        return OptimizePlan(items, insights)
    }

    private fun spaceInsight(space: VolumeSpace): List<Insight> {
        if (space.totalBytes <= 0) return emptyList()
        val freePct = space.freeBytes * 100 / space.totalBytes
        return when {
            freePct < 10 -> listOf(
                Insight(
                    Severity.WARNING,
                    "Only $freePct% free (${space.freeBytes.humanBytes()})",
                    "Flash storage writes slow down and wear faster when nearly full. Aim to keep 10-15% free.",
                ),
            )
            freePct < 20 -> listOf(
                Insight(Severity.ADVICE, "$freePct% free (${space.freeBytes.humanBytes()})", "Keeping at least 10-15% free keeps writes and app installs fast."),
            )
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ redundant wrappers

    private val extractNames = setOf("files", "new folder", "untitled folder", "folder", "extracted", "contents", "content", "output", "unzipped")
    private val suffix = Regex("""(?i)(\s?\(\d+\)|[-_ ](main|master|extracted|unzipped|copy))$""")

    private fun baseName(name: String) = name.replace(suffix, "").trim().lowercase()

    private fun flattenCandidate(wrapper: DirNode): OptimizeItem? {
        if (wrapper.depth < 2 || wrapper.zone != Zone.USER_MANAGED || wrapper.hidden) return null
        if (wrapper.files.isNotEmpty() || wrapper.dirs.size != 1) return null
        val inner = wrapper.dirs[0]
        if (inner.zone != Zone.USER_MANAGED || inner.hidden) return null
        if (wrapper.subtreeHas(NodeFlags.SUBTREE_BLOCKERS)) return null
        // "ViPER4Android-Presets-v2.2.0-Full/Full": the inner folder repeats the end of the outer one's name.
        val innerWords = Text.normalize(inner.name)
        val sameName = baseName(inner.name) == baseName(wrapper.name) ||
            (innerWords.trim().length >= 3 && Text.normalize(wrapper.name).endsWith(innerWords))
        if (!sameName && inner.name.trim().lowercase() !in extractNames) return null
        if (inner.dirs.any { it.name == inner.name } || inner.files.any { it.name == inner.name }) return null
        if (inner.dirs.isEmpty() && inner.files.isEmpty()) return null
        // Inside source and decompiled trees a repeated name is a package path (smali/com/acme/userConfig/userConfig),
        // not an archive wrapper: flattening it would change what the code means.
        if (insideCodeTree(wrapper)) return null
        var newest = inner.mtime
        var hasCode = false
        inner.walkFiles {
            if (it.mtime > newest) newest = it.mtime
            if (it.kind == FileKind.CODE) hasCode = true
        }
        if (newest > recentCutoff || hasCode) return null

        val ops = ArrayList<Operation>()
        for (d in inner.dirs) ops += MoveDirOp(d.path, "${wrapper.path}/${d.name}", allowRename = true)
        for (f in inner.files) ops += MoveFileOp(f.path, "${wrapper.path}/${f.name}", f.size, f.mtime)
        ops += RemoveEmptyDirOp(inner.path)
        return OptimizeItem(
            id = "flatten:${wrapper.path.hashCode().toString(16)}:${wrapper.path.length}",
            kind = OptimizeKind.FLATTEN_WRAPPER,
            path = wrapper.path,
            title = "${wrapper.name}/${inner.name}",
            detail = "Move ${inner.files.size + inner.dirs.size} item(s) up one level and remove the redundant \"${inner.name}\" folder.",
            fileCount = inner.totalFiles,
            defaultSelected = true,
            operations = ops,
        )
    }

    private fun isHome(dir: DirNode) = BuiltInRules.isHome(dir.relPath, environment.deviceLabel, settings.customRules)

    // ------------------------------------------------------------------ buried build outputs

    private val installerExt = setOf("apk", "aab", "apks", "xapk", "apkm")

    /** Files Gradle writes next to its outputs to describe them; they mean nothing once the installers move out. */
    private fun isOutputMetadata(f: FileNode) = f.name == "output-metadata.json" || f.name == "output.json" || f.extension == "idsig"

    /**
     * A downloaded CI artifact keeps Gradle's layout, `Name/app/build/outputs/apk/<flavor>/<type>/Name.apk`: eight folders
     * for one or two installers. The installers move up to the top folder of the download, Gradle's metadata files go to
     * the quarantine, and the emptied folders are removed. [outputs] is a `build/outputs` folder outside any project.
     */
    private fun buriedBuildOutputs(outputs: DirNode): OptimizeItem? {
        val build = outputs.parent ?: return null
        // The top of the download: climb while each parent holds nothing but this one folder.
        var top = build
        while (true) {
            val up = top.parent ?: break
            if (up.depth < 2 || up.files.isNotEmpty() || up.dirs.size != 1 || isHome(up)) break
            top = up
        }
        val root = if (top === build) build.parent ?: return null else top
        if (root.depth < 2 || root.zone != Zone.USER_MANAGED || root.hidden || isHome(root)) return null
        if (build.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) || root.insideFlagged(NodeFlags.PROJECT_ROOT or NodeFlags.CODE_TREE) || insideCodeTree(root)) return null
        if (root.hasFlag(NodeFlags.PROJECT_ROOT) || root.subtreeHas(NodeFlags.PROJECT_ROOT or NodeFlags.GIT_DIR)) return null

        val files = ArrayList<FileNode>()
        outputs.walkFiles { files += it }
        if (files.any { it.kind == FileKind.CODE || it.mtime > recentCutoff || it.hidden }) return null
        val installers = files.filter { it.extension in installerExt }
        if (installers.isEmpty()) return null

        // Same-named installers (app-debug.apk in two flavors) are told apart by the folders they came from.
        val taken = root.files.mapTo(HashSet()) { it.name.lowercase() } + root.dirs.map { it.name.lowercase() }
        val clash = installers.groupingBy { it.name.lowercase() }.eachCount().filterValues { it > 1 }.keys
        val ops = ArrayList<Operation>()
        val names = HashSet<String>()
        for (f in installers) {
            val name = if (f.name.lowercase() in clash) {
                f.dir.relPath.removePrefix(outputs.relPath + "/").split('/').drop(1).plus(f.name).joinToString("-")
            } else {
                f.name
            }
            if (name.lowercase() in taken || !names.add(name.lowercase())) continue
            ops += MoveFileOp(f.path, "${root.path}/$name", f.size, f.mtime)
        }
        if (ops.isEmpty()) return null
        files.filter(::isOutputMetadata).forEach { ops += QuarantineOp(it.path, false, it.size, it.mtime) }
        val moved = ops.count { it is MoveFileOp }
        return OptimizeItem(
            id = "lift:${root.path.hashCode().toString(16)}:${root.path.length}",
            kind = OptimizeKind.LIFT_BUILD_OUTPUTS,
            path = root.path,
            title = root.relPath,
            detail = "Move $moved installer${if (moved == 1) "" else "s"} up from ${outputs.relPath.removePrefix(root.relPath + "/")} " +
                "and remove the emptied build folders.",
            fileCount = moved,
            defaultSelected = true,
            operations = ops,
        )
    }

    // ------------------------------------------------------------------ chains of empty folders

    /**
     * `Folder/a/b/c/Content`: three or more folders that hold nothing but the next one, typical of archives unpacked
     * into archives. The content moves up to `Folder/Content` and the chain goes. People do build single-branch trees on
     * purpose (`Travel/2024/Japan/Tokyo`), so this is only suggested, never selected by default.
     */
    private fun emptyChain(dir: DirNode): OptimizeItem? {
        if (dir.depth < 2 || dir.zone != Zone.USER_MANAGED || dir.hidden || dir.files.isNotEmpty() || dir.dirs.size != 1) return null
        if (isHome(dir) || dir.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) || insideCodeTree(dir) || dir.insideFlagged(NodeFlags.CODE_TREE)) return null
        // Only the top of a chain: a folder that is itself a link in one belongs to its parent's suggestion.
        dir.parent?.let { p -> if (p.depth >= 2 && p.files.isEmpty() && p.dirs.size == 1 && !isHome(p) && p.zone == Zone.USER_MANAGED) return null }
        val links = ArrayList<DirNode>()
        var end = dir.dirs[0]
        while (end.files.isEmpty() && end.dirs.size == 1) {
            links += end
            end = end.dirs[0]
        }
        if (links.size < 3 || (end.files.isEmpty() && end.dirs.isEmpty()) || end.hidden || links.any { it.hidden }) return null
        if (links.any { it.name.equals(end.name, ignoreCase = true) }) return null
        var newest = end.mtime
        var hasCode = false
        end.walkFiles {
            if (it.mtime > newest) newest = it.mtime
            if (it.kind == FileKind.CODE) hasCode = true
        }
        if (newest > recentCutoff || hasCode) return null
        val chain = (links + end).joinToString("/") { it.name }
        return OptimizeItem(
            id = "chain:${dir.path.hashCode().toString(16)}:${dir.path.length}",
            kind = OptimizeKind.COLLAPSE_CHAIN,
            path = dir.path,
            title = "${dir.relPath}/$chain",
            detail = "Move ${end.name} up to ${dir.name} and remove the ${links.size} empty folders in between.",
            fileCount = end.totalFiles,
            defaultSelected = false,
            operations = listOf(MoveDirOp(end.path, "${dir.path}/${end.name}", allowRename = true)) +
                links.reversed().map { RemoveEmptyDirOp(it.path) },
        )
    }

    private fun insideCodeTree(dir: DirNode): Boolean {
        var d: DirNode? = dir
        while (d != null && d.parent != null) {
            if (SafetyPolicy.isCodeTreeDir(d.name)) return true
            d = d.parent
        }
        return false
    }

    // ------------------------------------------------------------------ date folders inside source code

    /** A year or year-month folder, as the flat-folder sorting names them. */
    private val dateFolder = Regex("""^\d{4}(-\d{2})?$""")

    /**
     * Galaxy Steward 1.2.0 also sorted big source folders into date folders, and the next scan sorted those again:
     * `jadx/sources/defpackage/2026-08/2026-08/A.java`. Java, Kotlin and smali package folders can't start with a digit,
     * so a date-named folder inside a source tree is always that mistake. Its files go back to the package folder they
     * came from, except any whose name is already taken there.
     */
    private fun datedSourceFolder(dir: DirNode): OptimizeItem? {
        val home = dir.parent ?: return null
        if (!dateFolder.matches(dir.name) || dateFolder.matches(home.name) || dir.hidden) return null
        val code = insideCodeTree(home) || home.insideFlagged(NodeFlags.CODE_TREE)
        if (!code) return datedLibraryFolder(dir, home)
        if (dir.subtreeHas(NodeFlags.HAS_SYMLINK or NodeFlags.HAS_SPECIAL or NodeFlags.UNREADABLE or NodeFlags.HAS_UNSAFE_NAME)) return null
        // Everything the sorting made: this folder and date folders nested in it, holding files only.
        val files = ArrayList<FileNode>()
        val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            files += d.files
            for (c in d.dirs) if (dateFolder.matches(c.name)) stack.addLast(c) else return null
        }
        if (files.isEmpty() || files.count { it.kind == FileKind.CODE } * 10 < files.size * 8) return null
        if (files.any { it.mtime > recentCutoff }) return null
        val taken = home.files.mapTo(HashSet()) { it.name } + home.dirs.map { it.name }
        val movable = files.groupBy { it.name }.filter { (name, same) -> same.size == 1 && name !in taken }.values.map { it.single() }
        if (movable.isEmpty()) return null
        val kept = files.size - movable.size
        return OptimizeItem(
            id = "undate:${dir.path.hashCode().toString(16)}:${dir.path.length}",
            kind = OptimizeKind.REPAIR_DATE_FOLDERS,
            path = dir.path,
            title = dir.relPath,
            detail = "Move ${movable.size} source files back into ${home.name}: an earlier version sorted them into date folders by mistake." +
                if (kept > 0) " $kept stay because the name is already taken." else "",
            fileCount = movable.size,
            defaultSelected = true,
            operations = movable.map { MoveFileOp(it.path, "${home.path}/${it.name}", it.size, it.mtime) },
        )
    }

    /**
     * The same mistake outside source code: 1.2.0 also sorted preset and document libraries by date (a phone showed
     * `ViPER4Android-Presets/Kernel/2020-10` with 1,047 presets). Today only photos and videos are sorted by date, so a
     * date folder of other files, every one of them dated inside that period, was most likely made by it. People do
     * make date folders themselves, so this is only a suggestion.
     */
    private fun datedLibraryFolder(dir: DirNode, home: DirNode): OptimizeItem? {
        if (dir.zone != Zone.USER_MANAGED || dir.dirs.isNotEmpty() || dir.files.size < 20) return null
        if (dir.subtreeHas(NodeFlags.SUBTREE_BLOCKERS) || isHome(dir)) return null
        val files = dir.files
        if (files.count { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO } * 5 > files.size) return null
        if (files.any { it.mtime > recentCutoff }) return null
        val zone = ZoneId.systemDefault()
        val inPeriod = files.all { f ->
            val date = Instant.ofEpochMilli(f.mtime).atZone(zone)
            dir.name == date.year.toString() || dir.name == "%04d-%02d".format(date.year, date.monthValue)
        }
        if (!inPeriod) return null
        val taken = home.files.mapTo(HashSet()) { it.name } + home.dirs.map { it.name }
        val movable = files.filter { it.name !in taken }
        if (movable.isEmpty()) return null
        return OptimizeItem(
            id = "undate:${dir.path.hashCode().toString(16)}:${dir.path.length}",
            kind = OptimizeKind.REPAIR_DATE_FOLDERS,
            path = dir.path,
            title = dir.relPath,
            detail = "Move ${movable.size} files back into ${home.name}? An earlier version sorted libraries like this by date; " +
                "leave it if you made this folder yourself.",
            fileCount = movable.size,
            defaultSelected = false,
            operations = movable.map { MoveFileOp(it.path, "${home.path}/${it.name}", it.size, it.mtime) },
        )
    }

    // ------------------------------------------------------------------ oversized flat folders

    private fun bucketCandidate(dir: DirNode): OptimizeItem? {
        if (!dir.managed || dir.depth < 2 || dir.hidden) return null
        if (dir.zone != Zone.USER_MANAGED && dir.zone != Zone.MEDIA_LIBRARY) return null
        if (insideCodeTree(dir) || dir.insideFlagged(NodeFlags.CODE_TREE)) return null
        val movable = dir.files.filter {
            !it.hidden && it.mtime <= recentCutoff && !SafetyPolicy.isCredentialName(it.name) &&
                !SafetyPolicy.isInProgressDownload(it.name) && !SafetyPolicy.isMarkerFile(it.name)
        }
        if (movable.size <= settings.flatDirThreshold / 2) return null
        // Only photo and video dumps (camera rolls, screenshots, chat media) are sorted by date. Libraries of presets,
        // datasets, music or documents are found by name, and date folders would scatter them.
        val photosAndVideos = movable.count { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO }
        if (photosAndVideos * 10 < movable.size * 8) return null
        val zone = ZoneId.systemDefault()
        val years = movable.groupingBy { Instant.ofEpochMilli(it.mtime).atZone(zone).year }.eachCount()
        val monthly = years.values.any { it > settings.flatDirThreshold }
        fun bucketOf(f: FileNode): String {
            val date = Instant.ofEpochMilli(f.mtime).atZone(zone)
            return if (monthly) "%04d-%02d".format(date.year, date.monthValue) else date.year.toString()
        }
        val bucketNames = movable.map(::bucketOf).distinct()
        // A "2026-08" folder whose files all fall in 2026-08 would only gain a "2026-08/2026-08" (seen on a real phone).
        if (bucketNames == listOf(dir.name)) return null
        val ops = movable.map { MoveFileOp(it.path, "${dir.path}/${bucketOf(it)}/${it.name}", it.size, it.mtime) }
        val buckets = bucketNames.size
        return OptimizeItem(
            id = "bucket:${dir.path.hashCode().toString(16)}:${dir.path.length}",
            kind = OptimizeKind.BUCKET_FLAT_DIR,
            path = dir.path,
            title = dir.relPath,
            detail = "Sort ${movable.size} files into $buckets ${if (monthly) "month" else "year"} folders for faster browsing and indexing.",
            fileCount = movable.size,
            defaultSelected = dir.zone == Zone.MEDIA_LIBRARY,
            operations = ops,
        )
    }
}
