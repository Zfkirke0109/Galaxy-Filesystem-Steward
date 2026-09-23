package com.galaxy.steward.core.exec

import com.galaxy.steward.core.DAY_MS
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The quarantine lives at `<storage>/.StorageSteward/Quarantine/<run id>/<original relative path>`, on the
 * same volume as the data so quarantining is an instant rename. Space is only released when a run's
 * quarantine is emptied - manually or after the retention period.
 */
class QuarantineManager(private val guard: PathGuard, private val journals: JournalStore?) {
    fun dirFor(runId: String): Path = guard.quarantineRoot.resolve(runId)

    /** Creates the steward folder with a `.nomedia` marker so galleries never index quarantined media. */
    fun prepare(): Boolean {
        return try {
            Files.createDirectories(guard.quarantineRoot)
            val marker = guard.stewardDir.resolve(".nomedia")
            if (!Files.exists(marker)) Files.createFile(marker)
            guard.isRealDirectory(guard.quarantineRoot)
        } catch (_: IOException) {
            false
        }
    }

    fun sizeOf(runId: String): Long = treeSize(dirFor(runId))

    fun totalSize(): Long = treeSize(guard.quarantineRoot)

    fun runsWithContent(): List<String> = try {
        Files.newDirectoryStream(guard.quarantineRoot).use { stream ->
            stream.filter { guard.isRealDirectory(it) }.map { it.fileName.toString() }
        }
    } catch (_: IOException) {
        emptyList()
    }

    /** Permanently deletes one run's quarantine and marks its journal as purged. Returns bytes freed. */
    fun purge(runId: String, now: Long = System.currentTimeMillis()): Long {
        val dir = dirFor(runId)
        if (!dir.startsWith(guard.quarantineRoot) || dir == guard.quarantineRoot || !guard.isRealDirectory(dir)) return 0
        val bytes = treeSize(dir)
        deleteTree(dir)
        if (journals != null && journals.fileFor(runId).exists()) journals.appendMeta(runId, "purged", now.toString())
        return bytes
    }

    fun purgeAll(now: Long = System.currentTimeMillis()): Long = runsWithContent().sumOf { purge(it, now) }

    /** Empties quarantines of runs that finished more than [retentionDays] ago. */
    fun purgeExpired(retentionDays: Int, now: Long = System.currentTimeMillis()): Long {
        if (retentionDays <= 0) return 0
        val cutoff = now - retentionDays * DAY_MS
        return runsWithContent().sumOf { id ->
            val info = journals?.info(id)
            val finished = info?.finishedAt ?: info?.startedAt ?: 0L
            if (finished in 1 until cutoff) purge(id, now) else 0L
        }
    }

    private fun treeSize(dir: Path): Long {
        if (!guard.isRealDirectory(dir)) return 0
        var total = 0L
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
        }
        return total
    }

    /** Deletes a tree without following symlinks (links themselves are removed, never their targets). */
    private fun deleteTree(dir: Path) {
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.deleteIfExists(d)
                    } catch (_: IOException) {
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
    }
}
