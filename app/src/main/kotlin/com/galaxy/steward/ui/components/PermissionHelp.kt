package com.galaxy.steward.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.edit

/** The Activity behind a Compose context, if there is one. */
fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** For each permission asked for now: whether Android could still show its dialog ([noteRequest]). */
private val couldAsk = HashMap<String, Boolean>()

private const val PREFS = "permissions"
private const val ASKED = "asked"

/**
 * Call right before asking for [permissions]. Android shows its dialog the first time, and again while it would show a
 * reason first (you denied once); after that it answers "denied" without asking. Only such a request means the button
 * does nothing any more ([openSettingsIfDeniedForGood]); a first request closed with Back also comes back denied.
 */
fun noteRequest(context: Context, permissions: Collection<String>) {
    val activity = context.findActivity() ?: return
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val asked = prefs.getStringSet(ASKED, emptySet()).orEmpty()
    for (p in permissions) couldAsk[p] = p !in asked || ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
    prefs.edit { putStringSet(ASKED, asked + permissions) }
}

/**
 * After a permission request came back denied: once Android won't ask again (you chose "Don't ask again", or denied it
 * twice), the button that asks does nothing at all from then on. Then this app's settings page opens instead, where the
 * permission can still be allowed. Not after a request Android did show ([noteRequest]): that was your answer.
 */
fun openSettingsIfDeniedForGood(context: Context, permissions: Collection<String>, granted: Boolean) {
    if (granted || permissions.isEmpty()) return
    val activity = context.findActivity() ?: return
    if (permissions.any { couldAsk[it] != false }) return
    if (permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) return
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
