package com.galaxy.steward.apps

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.net.toUri
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
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
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

    /** Storage of every installed app. Needs usage access; throws [SecurityException] without it. */
    /**
     * Every installed app's storage. Each app is its own query to the system (and loading its label reads its
     * resources), so they run a few at a time: one after another took 42 s for 756 apps on a Galaxy S23 Ultra.
     */
    suspend fun query(context: Context): List<AppStorageRow> = coroutineScope {
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        val workers = Dispatchers.IO.limitedParallelism(PARALLEL_QUERIES)
        apps.map { info -> async(workers) { row(context, pm, stats, user, info) } }.awaitAll().filterNotNull()
            .sortedByDescending { it.totalBytes }
    }

    private fun row(context: Context, pm: PackageManager, stats: StorageStatsManager, user: UserHandle, info: ApplicationInfo): AppStorageRow? {
        val s = try {
            stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, user)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: IOException) {
            return null
        }
        return AppStorageRow(
            packageName = info.packageName,
            label = pm.getApplicationLabel(info).toString(),
            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            appBytes = s.appBytes,
            dataBytes = (s.dataBytes - s.cacheBytes).coerceAtLeast(0),
            cacheBytes = s.cacheBytes,
            protected = AppPolicy.isProtected(info.packageName, context.packageName),
        )
    }

    private const val PARALLEL_QUERIES = 8

    /** Live cache size of one app, or null when it cannot be read. */
    fun cacheBytes(context: Context, packageName: String): Long? = try {
        context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForPackage(StorageManager.UUID_DEFAULT, packageName, Process.myUserHandle()).cacheBytes
    } catch (_: Exception) {
        null
    }
}
