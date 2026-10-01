package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Which apps may have all their data cleared, and how last use is described. */
class AppStoragePolicyTest {
    private val own = "com.galaxy.steward"

    @Test
    fun clearDataIsOfferedForOrdinaryAppsOnly() {
        listOf("com.dolphinemu.dolphinemu", "com.square_enix.ffvii", "com.spotify.music", "com.google.android.apps.photos")
            .forEach { assertNull(it, AppPolicy.clearDataBlock(it, own)) }

        assertEquals("Protected", AppPolicy.clearDataBlock(own, own))
        assertEquals("Protected", AppPolicy.clearDataBlock(AppPolicy.SHIZUKU, own))
        assertEquals("Protected", AppPolicy.clearDataBlock("com.amazon.mp3", own))
        assertEquals("Protected", AppPolicy.clearDataBlock("com.audible.application", own))
        assertEquals("Termux", AppPolicy.clearDataBlock("com.termux", own))
        assertEquals("Termux", AppPolicy.clearDataBlock("com.termux.api", own))
        assertEquals("Messages", AppPolicy.clearDataBlock("com.whatsapp", own))
        assertEquals("Messages", AppPolicy.clearDataBlock("org.thoughtcrime.securesms", own))
        assertEquals("Messages", AppPolicy.clearDataBlock("com.google.android.gm", own))
        assertEquals("Holds keys", AppPolicy.clearDataBlock("com.google.android.apps.authenticator2", own))
        assertEquals("Holds keys", AppPolicy.clearDataBlock("io.metamask", own))
        assertEquals("Holds keys", AppPolicy.clearDataBlock("com.x8bit.bitwarden", own))
        // Platform apps by name (the helper can't see the system flag) and by flag (a preinstalled app).
        assertEquals("System app", AppPolicy.clearDataBlock("android", own))
        assertEquals("System app", AppPolicy.clearDataBlock("com.android.providers.media.module", own))
        assertEquals("System app", AppPolicy.clearDataBlock("com.google.android.gms", own))
        assertEquals("System app", AppPolicy.clearDataBlock("com.samsung.android.app.notes", own))
        assertEquals("System app", AppPolicy.clearDataBlock("com.sec.android.app.camera", own))
        assertEquals("System app", AppPolicy.clearDataBlock("com.netflix.mediaclient", own, system = true))
        // The helper checks without knowing the app's own package; everything else still holds.
        assertEquals("Protected", AppPolicy.clearDataBlock(AppPolicy.SHIZUKU, null))
    }

    @Test
    fun messengersAreStillNeverStopped() {
        listOf("com.whatsapp", "com.google.android.apps.messaging", "com.termux", AppPolicy.SHIZUKU, "com.android.providers.telephony")
            .forEach { assertFalse(it, AppPolicy.mayForceStop(it)) }
    }

    @Test
    fun lastUseReadsLikeARoughAge() {
        val now = 1_800_000_000_000L
        assertEquals("in the last day", ageText(now - 3_600_000, now))
        assertEquals("in the last day", ageText(now + 60_000, now)) // a clock that moved back
        assertEquals("yesterday", ageText(now - DAY_MS - 1, now))
        assertEquals("9 days ago", ageText(now - 9 * DAY_MS, now))
        assertEquals("3 weeks ago", ageText(now - 21 * DAY_MS, now))
        assertEquals("4 months ago", ageText(now - 125 * DAY_MS, now))
        assertEquals("23 months ago", ageText(now - 700 * DAY_MS, now))
        assertEquals("2 years ago", ageText(now - 800 * DAY_MS, now))
    }
}
