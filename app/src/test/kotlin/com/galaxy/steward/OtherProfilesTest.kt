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

    /** Android 17 prints the uid without a colon; 8,230 Secure Folder lines got through on the 10-01 phone. */
    @Test
    fun android17UidColumnAndTheSystemsMentionsOfThemAreLeftOut() {
        val f = OtherProfiles(0)
        val secure = listOf(
            "10-01 06:39:48.005 15010274   904   926 D WM-SystemJobScheduler: Cancelling work ID 2de6e9d5",
            "10-01 06:22:41.704 15010133  9880 10024 E ActivityThread: Failed to find provider info for com.sec.android.log.diagmonagent",
            "10-01 06:39:49.558  1000  3288  4837 D PackageManager: setEnabledSetting : userId = 150 packageName = com.facebook.appmanager",
            "10-01 06:22:17.354  1000  3288  3288 E ActivityManager: Unable to find com.google.android.youtube/u150",
            "10-01 06:22:38.942  1000  3288  3928 I am_uid_running: 15010309",
            "10-01 06:22:48.985  1000  3288  3288 I power_partial_wake_state: [DIS,73858637,1803,NotificationManagerService:post:com.google.android.as:android(enabled),1000,3288,WorkSource{15010267 com.google.android.as}]",
            "10-01 06:22:27.643  1000  3288  6691 E AppOpService: Blocked setUidMode call for runtime permission app op: uid = 15001000, code = CAMERA",
            "10-01 06:22:15.848  1000  3288  3839 I snet_event_log: [35028827,-1,account:Account {name=someone@example.com, type=com.google} provider:subscribedfeeds user:150]",
            "10-01 06:39:30.292  root  6373  6373 I libprocessgroup: Created cgroup /sys/fs/cgroup/apps/uid_15010273/pid_6373",
            "10-01 06:22:46.080  1017  1339  1371 I keystore2: In create_operation. AppUid(15010133), None, TEE",
            "10-01 06:39:57.469  1000  3288  4000 D InetDiagMessage: Destroyed live tcp sockets for uids={15020268} in 1ms",
        )
        val mine = listOf(
            "10-01 06:38:53.494 10833  4367  4367 I GalaxySteward: run \"App folder clean-up\" started",
            "10-01 06:22:08.890  root  1165  1165 D io_stats: !@ Write_top(KB): kworker/u16:8(220) 964 f2fs_ckpt-254:7(1394) 296 others(2147483647) 164",
            "10-01 06:39:38.607 10834  6156  7020 D CCodecConfig:   c2::u32 algo.secure-mode.value = 0",
            "10-01 06:39:30.574  root  2324 28243 D installd: Purging /data/data/com.instagram.android/cache/ExoPlayerCacheDir/videocache/15/3994652587736840217_258092426.null.1464146325562975a.-1.1128c2d5ace188a6",
            "10-01 06:22:53.849  1000  3288  3288 I power_partial_wake_state: [REL,15063583,632,NotificationManagerService:post:com.tribalfs.gmh:android,1000,3288,WorkSource{10480 com.tribalfs.gmh}]",
            "10-01 06:40:33.164  root  1165  1165 D io_stats: !@ Read_top(KB): artd(8182) 180380 id.app.launcher(5404) 15620",
            "10-01 06:40:32.834 10833  4367  4367 I wm_on_paused_called: [75715565,com.galaxy.steward.ui.MainActivity,performPause,1]",
        )
        secure.forEach { assertFalse(it, f.keep(it)) }
        mine.forEach { assertTrue(it, f.keep(it)) }
        assertEquals(secure.size, f.dropped)

        // A work profile isn't known up front: once one of its lines names it, its bare uids are recognised too.
        val g = OtherProfiles(0)
        assertTrue(g.keep("10-01 06:22:38.942  1000  3288  3928 I am_uid_running: 1110309"))
        assertFalse(g.keep("10-01 06:22:16.958  1000  3288  3288 I MultiUserInstallPolicy: Set package state for userId: 11"))
        assertFalse(g.keep("10-01 06:22:38.942  1000  3288  3928 I am_uid_running: 1110309"))
    }
}
