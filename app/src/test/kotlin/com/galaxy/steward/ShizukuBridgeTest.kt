package com.galaxy.steward

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.shizuku.ShizukuBridge
import com.galaxy.steward.shizuku.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Right after an update, Shizuku may not have attached the new process yet when the app starts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShizukuBridgeTest {
    @Test
    fun notReadyAtStartIsRetriedInsteadOfCrashing() {
        var calls = 0
        val bridge = ShizukuBridge(ApplicationProvider.getApplicationContext()) {
            // Shizuku's own words from the 1.2.5 log, then an answer.
            if (calls++ < 2) throw IllegalStateException("binder haven't been received")
            ShizukuStatus.READY
        }
        assertEquals(ShizukuStatus.NOT_RUNNING, bridge.status.value) // constructed without crashing
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(ShizukuStatus.READY, bridge.status.value)
        assertEquals(3, calls)
    }
}
