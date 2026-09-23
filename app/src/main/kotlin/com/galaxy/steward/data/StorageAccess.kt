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
import com.galaxy.steward.core.optimize.VolumeSpace

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

    /** Tells MediaStore about moved, removed and restored paths so galleries and music apps stay accurate. */
    fun rescan(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        paths.distinct().chunked(250).forEach { chunk ->
            MediaScannerConnection.scanFile(context.applicationContext, chunk.toTypedArray(), null, null)
        }
    }
}
