package com.galaxy.steward.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat

/** The Activity behind a Compose context, if there is one. */
fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * After a permission request came back denied: once Android won't ask again (you chose "Don't ask again", or denied it
 * twice), the button that asks does nothing at all from then on. Then this app's settings page opens instead, where the
 * permission can still be allowed.
 */
fun openSettingsIfDeniedForGood(context: Context, permissions: Collection<String>, granted: Boolean) {
    if (granted || permissions.isEmpty()) return
    val activity = context.findActivity() ?: return
    if (permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) return
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
