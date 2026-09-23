package com.galaxy.steward.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.galaxy.steward.core.exec.MediaRescan
import com.galaxy.steward.core.optimize.VolumeSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

object StorageAccess {
    /** Primary shared storage, normally /storage/emulated/0. */
    val rootPath: String get() = Environment.getExternalStorageDirectory().absolutePath

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Settings screen for "All files access" on Android 11+. */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        }

    fun fallbackAccessIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) else null

    fun space(): VolumeSpace? = try {
        val stat = StatFs(rootPath)
        VolumeSpace(stat.availableBytes, stat.totalBytes)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Tells MediaStore about moved, removed and restored paths so galleries and music apps stay accurate, and
     * waits until it has finished. Android 10+ rescans a folder recursively and forgets files that are gone, so
     * each affected folder is scanned once instead of every file on its own.
     */
    suspend fun rescan(context: Context, paths: Collection<String>) {
        // One stat per changed path: never on the main thread (a 1,900-file run froze the UI for 5.8 s on a real phone).
        val targets = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaRescan.targets(rootPath, paths) else paths.distinct()
        }
        if (targets.isEmpty()) return
        withTimeoutOrNull(RESCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val remaining = AtomicInteger(targets.size)
                MediaScannerConnection.scanFile(context.applicationContext, targets.toTypedArray(), null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private const val RESCAN_TIMEOUT_MS = 20 * 60_000L
}
