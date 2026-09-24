package com.galaxy.steward.apps

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.net.toUri
import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.appdata.AppPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** One app's storage as Android accounts it (StorageStatsManager). */
data class AppStorageRow(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val appBytes: Long,
    /** App data that is not cache: databases, settings, downloads, offline content. */
    val dataBytes: Long,
    val cacheBytes: Long,
    val protected: Boolean,
    /** When the app was last used (epoch ms), or null when Android has no record of it. */
    val lastUsed: Long? = null,
    /** Why "Clear all data" is not offered for this app ([AppPolicy.clearDataBlock]), or null when it is. */
    val clearBlock: String? = null,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes

    /** What clearing all its data frees: the data and the cache (the app itself stays installed). */
    val clearableBytes: Long get() = dataBytes + cacheBytes
}

object AppStorage {
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION") // Its replacement needs API 36; this still works everywhere.
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri())

    fun usageAccessFallbackIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether the Shizuku helper (Android's shell user) may clear another app's cache. Android 17 (API 37) requires
     * INTERNAL_DELETE_CACHE_FILES for it, which the shell user doesn't hold: the package manager logs "silently
     * ignoring" and the command still reports success (every clear in the 1.2.5 log verified nothing).
     */
    val shellCanClearCaches: Boolean get() = Build.VERSION.SDK_INT < 37

    /** Android user id of this process (0 for the main user, 150 for Secure Folder, ...). */
    val userId: Int get() = Process.myUid() / 100_000

    /**
     * Every package Android still knows about: installed ones, ones removed with "keep data", and (Android 15+)
     * archived apps. A folder only counts as a leftover when its package is in none of these.
     */
    fun knownPackages(context: Context): Set<String> {
        val pm = context.packageManager
        return try {
            val packages = when {
                Build.VERSION.SDK_INT >= 35 -> pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() or PackageManager.MATCH_ARCHIVED_PACKAGES),
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
                else -> @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            packages.map { it.packageName }.toSet()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    fun isSystem(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Every installed app's storage. Needs usage access; throws [SecurityException] without it. Each app is its own
     * query to the system (and loading its label reads its resources), so they run a few at a time: one after another
     * took 42 s for 756 apps on a Galaxy S23 Ultra. Android measures an app with a huge number of files by walking
     * them, which can take a minute on its own, so [onTotal] and [onRow] report each app as soon as it is measured,
     * with how long its query took.
     */
    suspend fun query(
        context: Context,
        onTotal: (Int) -> Unit = {},
        onRow: (AppStorageRow, Long) -> Unit = { _, _ -> },
    ): List<AppStorageRow> = coroutineScope {
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        onTotal(apps.size)
        val lastUsed = lastUsed(context)
        val workers = Dispatchers.IO.limitedParallelism(PARALLEL_QUERIES)
        apps.map { info ->
            async(workers) {
                val started = SystemClock.uptimeMillis()
                row(context, pm, stats, user, info, lastUsed[info.packageName])?.also { onRow(it, SystemClock.uptimeMillis() - started) }
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.totalBytes }
    }

    private fun row(
        context: Context,
        pm: PackageManager,
        stats: StorageStatsManager,
        user: UserHandle,
        info: ApplicationInfo,
        lastUsed: Long?,
    ): AppStorageRow? {
        val s = try {
            stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, user)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: IOException) {
            return null
        }
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        return AppStorageRow(
            packageName = info.packageName,
            label = pm.getApplicationLabel(info).toString(),
            system = system,
            appBytes = s.appBytes,
            dataBytes = (s.dataBytes - s.cacheBytes).coerceAtLeast(0),
            cacheBytes = s.cacheBytes,
            protected = AppPolicy.isProtected(info.packageName, context.packageName),
            lastUsed = lastUsed,
            clearBlock = AppPolicy.clearDataBlock(info.packageName, context.packageName, system),
        )
    }

    /**
     * When each app was last in use over the past two years (Android keeps yearly usage buckets that long), from
     * usage statistics. Empty when they can't be read.
     */
    fun lastUsed(context: Context, now: Long = System.currentTimeMillis()): Map<String, Long> = try {
        context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now)
            .mapValues { (_, u) ->
                maxOf(u.lastTimeUsed, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) u.lastTimeVisible else 0L)
            }
            .filterValues { it > now - USAGE_WINDOW_MS }
    } catch (_: RuntimeException) {
        emptyMap()
    }

    private const val USAGE_WINDOW_MS = 2 * 365 * DAY_MS

    private const val PARALLEL_QUERIES = 8

    /** Live cache size of one app, or null when it cannot be read. */
    fun cacheBytes(context: Context, packageName: String): Long? = try {
        context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForPackage(StorageManager.UUID_DEFAULT, packageName, Process.myUserHandle()).cacheBytes
    } catch (_: Exception) {
        null
    }

    /** Live size of all of one app's data, its cache included, or null when it cannot be read. */
    fun dataBytes(context: Context, packageName: String): Long? = try {
        context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForPackage(StorageManager.UUID_DEFAULT, packageName, Process.myUserHandle()).dataBytes
    } catch (_: Exception) {
        null
    }
}
