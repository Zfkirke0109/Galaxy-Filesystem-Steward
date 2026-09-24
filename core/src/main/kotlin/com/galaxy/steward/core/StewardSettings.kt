package com.galaxy.steward.core

import com.galaxy.steward.core.organize.KeywordRule

/** User-tunable knobs. Defaults follow the Termux steward's balanced profile. */
data class StewardSettings(
    /** Files smaller than this are ignored by duplicate detection (the Termux steward used 1 MiB). */
    val minDuplicateBytes: Long = 256 * KIB,
    /** Exact-duplicate folders smaller than this are ignored. */
    val minDuplicateFolderBytes: Long = 1 * MIB,
    /** Send removed duplicates to quarantine instead of deleting them after verification. */
    val quarantineDuplicates: Boolean = false,
    val quarantineRetentionDays: Int = 7,
    val staleTempDays: Int = 14,
    val oldLogDays: Int = 14,
    /** Directories with more direct files than this are suggested for year bucketing. */
    val flatDirThreshold: Int = 1000,
    /** File imported media under Pictures/Imported/<year>/ rather than one flat folder. */
    val mediaYearBuckets: Boolean = true,
    /** Files modified more recently than this are treated as still in use and never moved. */
    val recentFileGuardMinutes: Int = 10,
    /** Include hidden (dot) files and folders in duplicate detection. */
    val includeHiddenInDuplicates: Boolean = false,
    /** Folders (relative to the storage root) that must never be moved or cleaned. */
    val protectedFolders: List<String> = emptyList(),
    /** User rules evaluated before the built-in semantic rules. */
    val customRules: List<KeywordRule> = emptyList(),
    /** Parallel hashing workers; 0 picks automatically from the CPU count. */
    val hashWorkers: Int = 0,
) {
    fun effectiveHashWorkers(): Int =
        if (hashWorkers > 0) hashWorkers else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
}

/** Facts only the Android layer can provide. The JVM tests use a fake. */
interface DeviceEnvironment {
    /** Folder-safe device name used for device-specific destinations, for example "Samsung-SM-S918B". */
    val deviceLabel: String

    fun nowMillis(): Long = System.currentTimeMillis()

    /** True when [installedVersionCode] can see every installed package (needed for leftover-folder checks). */
    val canQueryPackages: Boolean get() = false

    /** Installed version code of [packageName], or null when the package is not installed. */
    fun installedVersionCode(packageName: String): Long? = null

    /** Package metadata parsed from an APK file, or null when it cannot be parsed. */
    fun apkInfo(path: String): ApkInfo? = null

    /** Every installed app, package name to label, for telling an app's own folder from yours. Empty when unknown. */
    fun installedApps(): Map<String, String> = emptyMap()
}

data class ApkInfo(val packageName: String, val versionCode: Long, val versionName: String?)

class SimpleEnvironment(override val deviceLabel: String = "This-Device") : DeviceEnvironment
