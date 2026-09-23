package com.galaxy.steward.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.galaxy.steward.core.ApkInfo
import com.galaxy.steward.core.DeviceEnvironment

/** Device facts for the engine: a folder-safe model label and package-manager lookups. */
class AndroidEnvironment(context: Context) : DeviceEnvironment {
    private val pm: PackageManager = context.packageManager

    override val deviceLabel: String = run {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val label = if (model.startsWith(maker, ignoreCase = true)) model else "$maker-$model"
        label.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "This-Device" }
    }

    override val canQueryPackages: Boolean = true

    @Suppress("DEPRECATION")
    override fun installedVersionCode(packageName: String): Long? = try {
        PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(packageName, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    override fun apkInfo(path: String): ApkInfo? = try {
        pm.getPackageArchiveInfo(path, 0)?.let {
            ApkInfo(it.packageName, PackageInfoCompat.getLongVersionCode(it), it.versionName)
        }
    } catch (_: RuntimeException) {
        null
    }
}
