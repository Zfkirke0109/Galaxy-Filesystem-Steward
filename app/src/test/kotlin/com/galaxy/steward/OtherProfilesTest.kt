package com.galaxy.steward

import com.galaxy.steward.diagnostics.OtherProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device log export leaves Secure Folder (user 150) and other profiles out. */
class OtherProfilesTest {
    @Test
    fun linesFromOrAboutAnotherUserAreLeftOut() {
        val f = OtherProfiles(0)
        val secure = listOf(
            // Secure Folder's own apps, by uid.
            "09-24 09:43:55.311 15010127:19380 20227 V CpEventLog(u150): [P] cp_state: contacts = 283",
            "09-24 09:23:34.600 15010295:24959 24959 I GmailApp: sync",
            // The system about them.
            "09-24 09:23:34.544  1000: 3475  3583 I ActivityManager: Start proc 24959:com.google.android.gm/u150a295 for broadcast",
            "09-24 09:00:17.658  1000: 3475  3583 I am_proc_start: [150,12247,15001000,com.android.settings,broadcast,{…}]",
            "09-24 09:26:15.925  1000: 3475  3578 I am_pss  : [6269,15010235,com.samsung.android.sm.devicesecurity,0,0,0,80261120,0,5,3]",
            "09-24 09:29:31.047  1000: 3475  4002 I PackageManager: getInstalledPackages: callingUid=1000 flags=512 userId=150",
            "09-24 09:39:38.592  1000: 3475  4386 W JobServiceContext: JobStatus{aa900a5 #u150a127/659 com.samsung… u=150 s=15010127",
            "09-24 09:24:15.001  1000: 3475  5020 E ClipboardService: Denying clipboard access to com.google.android.as for user 150",
            "09-24 09:32:31.031  1000: 3475  3582 I ActivityManager: Waited long enough for: ServiceRecord{55e14a u150 com.google.android.gms/.X}",
        )
        val mine = listOf(
            "--------- beginning of main",
            "09-24 09:56:07.674 10833:11549 11549 I GalaxySteward: Shizuku helper connected in 1002 ms",
            "09-24 09:56:00.926  1000: 3475  3583 I am_proc_start: [0,11549,10833,com.galaxy.steward,activelaunch,{…}]",
            "09-24 09:17:52.538  1000: 3475  3542 I sysui_multi_action: [319,278,322,242,325,6024,757,761]",
            "09-24 09:17:52.423 10085:17513 17513 I wm_on_create_called: [72803704,com.android.packageinstaller.v2.ui.InstallLaunch,performCreate,18]",
            "09-24 09:17:09.682  1000: 3475  3598 I am_cpu  : [9572,10562,com.termux.api,116160,70,40]",
            "09-24 07:35:02.404  1000: 3475  3583 I ActivityManager: Start proc 25461:com.galaxy.steward/u0a833 for broadcast",
            "09-24 09:00:00.000  root:  612   612 I vold: user 0 storage mounted",
        )
        secure.forEach { assertFalse(it, f.keep(it)) }
        mine.forEach { assertTrue(it, f.keep(it)) }
        assertEquals(secure.size, f.dropped)

        // Run from Secure Folder itself, the main profile is the other one.
        val inside = OtherProfiles(150)
        assertTrue(inside.keep(secure[0]))
        assertFalse(inside.keep(mine[1]))
    }
}
