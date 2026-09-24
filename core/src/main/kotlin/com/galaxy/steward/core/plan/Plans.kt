package com.galaxy.steward.core.plan

import com.galaxy.steward.core.dedupe.KeeperRanking
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone

/** Primitive, individually verified and journaled filesystem mutations. */
sealed interface Operation

/** Move one regular file. Collisions with identical content are deduped; others get a hash suffix. */
data class MoveFileOp(
    val src: String,
    val dst: String,
    val size: Long,
    /** Expected modification time, or -1 to skip the check. */
    val mtime: Long,
    val sha256: String? = null,
) : Operation

/**
 * Move a directory. Renamed as one unit when the destination does not exist and [allowRename] is set;
 * otherwise merged file by file, leaving protected content (credentials, nested projects, symlinks) in place.
 */
data class MoveDirOp(val src: String, val dst: String, val allowRename: Boolean) : Operation

/** Remove [path] after re-verifying, byte for byte, that it matches [canonical]. */
data class DeleteDuplicateOp(
    val path: String,
    val canonical: String,
    val size: Long,
    val sha256: String,
) : Operation

/**
 * Move a file or folder into the steward quarantine (reversible until the quarantine is emptied). With [extracted],
 * the file is a zip that is only quarantined if its unpacked copy still matches it, entry by entry.
 */
data class QuarantineOp(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val mtime: Long,
    val extracted: ExtractedCopy? = null,
) : Operation

/** Where a zip's files are unpacked: [folder] holds each entry's name, less [stripPrefix]. */
data class ExtractedCopy(val folder: String, val stripPrefix: String)

/** Remove a directory only if it is still empty at execution time. */
data class RemoveEmptyDirOp(val path: String) : Operation

/** Anything the user can tick in the UI. */
sealed interface PlanItem {
    val id: String
    val title: String

    /** Bytes this item frees once applied (and, for quarantined items, once the quarantine is emptied). */
    val reclaimBytes: Long
    val defaultSelected: Boolean
    val operations: List<Operation>
}

data class DuplicateCopy(
    val path: String,
    val size: Long,
    val mtime: Long,
    val zone: Zone,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class DuplicateGroup(
    override val id: String,
    val sha256: String,
    val size: Long,
    val copies: List<DuplicateCopy>,
    val keeperIndex: Int,
    val keepReason: String,
) : PlanItem {
    val keeper: DuplicateCopy get() = copies[keeperIndex]
    val kind: FileKind get() = FileKind.of(keeper.name)

    val removals: List<DuplicateCopy>
        get() = copies.filterIndexed { i, c -> i != keeperIndex && removableAgainst(c, keeper) }

    val retained: List<DuplicateCopy>
        get() = copies.filterIndexed { i, c -> i != keeperIndex && !removableAgainst(c, keeper) }

    override val title: String get() = keeper.name
    override val reclaimBytes: Long get() = removals.size * size

    /** Selected by default only when every removal sits in your files or the media library. */
    override val defaultSelected: Boolean
        get() = removals.isNotEmpty() && removals.all { it.zone == Zone.USER_MANAGED || it.zone == Zone.MEDIA_LIBRARY }

    val reviewNote: String?
        get() = when {
            removals.isEmpty() -> "Every copy is protected; nothing to remove."
            removals.any { it.zone == Zone.OTHER_SHARED } -> "Includes copies inside another app's folder - review before cleaning."
            else -> null
        }

    override val operations: List<Operation>
        get() = removals.map { DeleteDuplicateOp(it.path, keeper.path, size, sha256) }

    fun withKeeper(index: Int): DuplicateGroup =
        if (index in copies.indices && copies[index].zone.durable) copy(keeperIndex = index, keepReason = "Chosen by you") else this

    /** This group without the copies matching [drop]; null when fewer than two copies remain. */
    fun excluding(rootPath: String, drop: (String) -> Boolean): DuplicateGroup? {
        if (copies.none { drop(it.path) }) return this
        val kept = copies.filterNot { drop(it.path) }
        if (kept.size < 2) return null
        val ranked = kept.sortedWith(KeeperRanking.copyComparator(rootPath))
        return copy(copies = ranked, keeperIndex = 0)
    }

    companion object {
        /** A copy may go only if its zone allows removal, and media copies go only when media is kept. */
        fun removableAgainst(copy: DuplicateCopy, keeper: DuplicateCopy): Boolean =
            copy.path != keeper.path && copy.zone.removable &&
                (copy.zone != Zone.MEDIA_LIBRARY || keeper.zone == Zone.MEDIA_LIBRARY)
    }
}

data class FolderEntry(val rel: String, val size: Long, val sha256: String)

data class FolderCopy(val path: String, val zone: Zone) {
    val name: String get() = path.substringAfterLast('/')
}

data class FolderDuplicateGroup(
    override val id: String,
    val treeHash: String,
    val bytes: Long,
    val fileCount: Int,
    val copies: List<FolderCopy>,
    val keeperIndex: Int,
    val keepReason: String,
    /** Files relative to each copy, identical in every copy. */
    val entries: List<FolderEntry>,
    /** Sub-directories relative to each copy, deepest first. */
    val subdirs: List<String>,
) : PlanItem {
    val keeper: FolderCopy get() = copies[keeperIndex]

    val removals: List<FolderCopy>
        get() = copies.filterIndexed { i, c ->
            i != keeperIndex && c.zone.removable && (c.zone != Zone.MEDIA_LIBRARY || keeper.zone == Zone.MEDIA_LIBRARY)
        }

    override val title: String get() = keeper.name
    override val reclaimBytes: Long get() = removals.size * bytes
    override val defaultSelected: Boolean
        get() = removals.isNotEmpty() && removals.all { it.zone == Zone.USER_MANAGED || it.zone == Zone.MEDIA_LIBRARY }

    override val operations: List<Operation>
        get() = removals.flatMap { dup ->
            entries.map { DeleteDuplicateOp("${dup.path}/${it.rel}", "${keeper.path}/${it.rel}", it.size, it.sha256) } +
                subdirs.map { RemoveEmptyDirOp("${dup.path}/$it") } +
                RemoveEmptyDirOp(dup.path)
        }

    fun withKeeper(index: Int): FolderDuplicateGroup =
        if (index in copies.indices && copies[index].zone.durable) copy(keeperIndex = index, keepReason = "Chosen by you") else this
}

/** Two folders that share most of their content: merge [source] into [target]. */
data class FolderMerge(
    override val id: String,
    val target: String,
    val source: String,
    val sourceBytes: Long,
    val commonFiles: Int,
    val commonBytes: Long,
    val uniqueFiles: Int,
    val uniqueBytes: Long,
    override val operations: List<Operation>,
) : PlanItem {
    override val title: String get() = source.substringAfterLast('/') + " → " + target.substringAfterLast('/')
    override val reclaimBytes: Long get() = commonBytes
    override val defaultSelected: Boolean get() = false
    val overlapPercent: Int get() = if (sourceBytes <= 0) 0 else ((commonBytes * 100) / sourceBytes).toInt()
}

enum class JunkCategory(val title: String, val description: String, val defaultSelected: Boolean) {
    STALE_DOWNLOADS("Abandoned downloads", "Partial downloads (.crdownload, .part, .tmp) untouched for weeks.", true),
    OLD_LOGS("Old system logs", "Wi-Fi logs, dumps and bug reports that Samsung and other makers keep writing to /log.", true),
    EMPTY_FOLDERS("Empty folders", "Folders with nothing inside - often left by uninstalled apps.", true),
    HEAP_DUMPS(
        "Heap dumps",
        "Java heap dumps (.hprof) from LeakCanary and Android Studio, a few days old. A debug build writes new ones when it " +
            "runs again.",
        true,
    ),
    INSTALLED_APKS("Installed APKs", "Installer files for apps that are already installed at the same or newer version.", false),
    OLD_INSTALLERS("Older installers", "APKs of an app when a newer installer of the same app is also on the phone. The newest stays.", false),
    EXTRACTED_ARCHIVES(
        "Archives already extracted",
        "Zip, tar and tar.gz files whose every file is already unpacked in the folder next to them. Each unpacked file is " +
            "checked against its copy in the archive right before the archive moves to the quarantine. The folder stays.",
        true,
    ),
    OLD_RUNS(
        "Old run folders",
        "Folders named by date and time that a tool writes on every run. The newest run, and any from the last two weeks, " +
            "stay. If these are backups, older ones may hold something the newest doesn't, so review them.",
        false,
    ),
    THUMBNAIL_CACHES("Thumbnail caches", "Regenerable .thumbnails caches. Galleries rebuild them on demand.", false),
    TRASHED_MEDIA("Gallery trash", "Items already in the system trash (.trashed-*). Android deletes them after 30 days.", false),
    RECYCLE_BINS("Other apps' recycle bins", "Files you already deleted in MT Manager and similar file managers, still kept in their recycle bins.", true),
    ZERO_BYTE_FILES("Empty files", "Zero-byte files. Occasionally used as markers, so review first.", false),
    ORPHANED_APP_FOLDERS("Leftover app folders", "Top-level folders named after apps that are no longer installed.", false),
}

data class JunkItem(
    override val id: String,
    val category: JunkCategory,
    val path: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val mtime: Long,
    val note: String,
    /** For empty-folder trees: every directory to remove, deepest first (including [path]). */
    val emptyDirs: List<String> = emptyList(),
    /** For an extracted zip: where its files are unpacked. */
    val extracted: ExtractedCopy? = null,
) : PlanItem {
    override val title: String get() = path.substringAfterLast('/')
    override val reclaimBytes: Long get() = bytes
    override val defaultSelected: Boolean get() = category.defaultSelected
    override val operations: List<Operation>
        get() = if (category == JunkCategory.EMPTY_FOLDERS) {
            emptyDirs.ifEmpty { listOf(path) }.map { RemoveEmptyDirOp(it) }
        } else {
            listOf(QuarantineOp(path, isDirectory, bytes, mtime, extracted))
        }
}

data class OrganizeMove(
    override val id: String,
    val source: String,
    val destination: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val fileCount: Int,
    val reason: String,
    /** Folder the UI groups this move under. */
    val destinationFolder: String,
    override val defaultSelected: Boolean,
    override val operations: List<Operation>,
) : PlanItem {
    override val title: String get() = source.substringAfterLast('/')
    override val reclaimBytes: Long get() = 0
}

enum class OptimizeKind(val title: String) {
    FLATTEN_WRAPPER("Redundant nested folder"),
    BUCKET_FLAT_DIR("Oversized flat folder"),
    LIFT_BUILD_OUTPUTS("Installers buried in build folders"),
    COLLAPSE_CHAIN("Chain of empty folders"),
    REPAIR_DATE_FOLDERS("Date folders inside source code"),
}

data class OptimizeItem(
    override val id: String,
    val kind: OptimizeKind,
    val path: String,
    override val title: String,
    val detail: String,
    val fileCount: Int,
    override val defaultSelected: Boolean,
    override val operations: List<Operation>,
) : PlanItem {
    override val reclaimBytes: Long get() = 0
}

enum class Severity { INFO, ADVICE, WARNING }

/** Report-only findings: things worth knowing that the steward will not change automatically. */
data class Insight(val severity: Severity, val title: String, val detail: String, val path: String? = null)

data class KindStat(val files: Int, val bytes: Long)

data class LargeFile(val path: String, val size: Long, val mtime: Long, val zone: Zone)

data class StorageSummary(
    val totalFiles: Int,
    val totalBytes: Long,
    val byKind: Map<FileKind, KindStat>,
    val byZone: Map<Zone, Long>,
    val largestFiles: List<LargeFile>,
)

data class ScanReport(
    val tree: StorageTree,
    val startedAt: Long,
    val finishedAt: Long,
    val summary: StorageSummary,
    val duplicates: List<DuplicateGroup>,
    val folderDuplicates: List<FolderDuplicateGroup>,
    val folderMerges: List<FolderMerge>,
    val junk: List<JunkItem>,
    val organize: List<OrganizeMove>,
    val optimize: List<OptimizeItem>,
    val insights: List<Insight>,
) {
    val duplicateBytes: Long get() = duplicates.sumOf { it.reclaimBytes } + folderDuplicates.sumOf { it.reclaimBytes }
    val junkBytes: Long get() = junk.sumOf { it.reclaimBytes }

    fun allItems(): List<PlanItem> = duplicates + folderDuplicates + folderMerges + junk + organize + optimize

    /** Copy without the items that were applied successfully. */
    fun without(appliedIds: Set<String>): ScanReport = copy(
        duplicates = duplicates.filterNot { it.id in appliedIds },
        folderDuplicates = folderDuplicates.filterNot { it.id in appliedIds },
        folderMerges = folderMerges.filterNot { it.id in appliedIds },
        junk = junk.filterNot { it.id in appliedIds },
        organize = organize.filterNot { it.id in appliedIds },
        optimize = optimize.filterNot { it.id in appliedIds },
    )
}
