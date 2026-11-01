package com.galaxy.steward.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.ui.components.noteRequest
import com.galaxy.steward.ui.components.openSettingsIfDeniedForGood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * App info opens only when a permission request came back denied without Android asking: not when you closed the
 * first dialog with Back, nor right after you said no in one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PermissionHelpTest {
    private val permission = "com.termux.permission.RUN_COMMAND"

    private fun ask(activity: ComponentActivity, rationaleBefore: Boolean, rationaleAfter: Boolean): String? {
        shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(permission, rationaleBefore)
        noteRequest(activity, listOf(permission))
        shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(permission, rationaleAfter)
        openSettingsIfDeniedForGood(activity, listOf(permission), granted = false)
        return shadowOf(activity).nextStartedActivity?.action
    }

    @Test
    fun aFirstRequestClosedWithBackDoesNotOpenSettings() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertNull(ask(activity, rationaleBefore = false, rationaleAfter = false))
    }

    @Test
    fun onlyARequestAndroidNoLongerShowsOpensSettings() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // You said no in the dialog: Android will show a reason before asking again.
        assertNull(ask(activity, rationaleBefore = false, rationaleAfter = true))
        // Asked again, and no again: now Android won't ask any more, but you just answered.
        assertNull(ask(activity, rationaleBefore = true, rationaleAfter = false))
        // The button now gets "denied" straight away: App info opens, where it can still be allowed.
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ask(activity, rationaleBefore = false, rationaleAfter = false))
    }
}
