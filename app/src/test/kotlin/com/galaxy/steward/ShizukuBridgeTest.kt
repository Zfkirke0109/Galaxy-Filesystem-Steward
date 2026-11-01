package com.galaxy.steward

import android.os.Binder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.shizuku.IStewardHelper
import com.galaxy.steward.shizuku.ShizukuBridge
import com.galaxy.steward.shizuku.ShizukuStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Duration

/** Right after an update, Shizuku may not have attached the new process yet when the app starts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShizukuBridgeTest {
    private val scope = MainScope()

    @After
    fun stop() = scope.cancel()

    @Test
    fun notReadyAtStartIsRetriedInsteadOfCrashing() {
        var calls = 0
        val bridge = ShizukuBridge(ApplicationProvider.getApplicationContext(), probe = {
            // Shizuku's own words from the 1.2.5 log, then an answer.
            if (calls++ < 2) throw IllegalStateException("binder haven't been received")
            ShizukuStatus.READY
        })
        // Constructed without crashing, and saying what is going on: Shizuku runs, it just hasn't attached the app yet.
        assertEquals(ShizukuStatus.CONNECTING, bridge.status.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(ShizukuStatus.READY, bridge.status.value)
        assertEquals(3, calls)
    }

    /** A screen that asks for the helper while Shizuku is still attaching the app gets it a moment later, not an error. */
    @Test
    fun theHelperWaitsWhileShizukuAttachesTheApp() {
        var calls = 0
        val helper = fakeHelper()
        val bridge = ShizukuBridge(
            ApplicationProvider.getApplicationContext(),
            probe = {
                // The 10-30 log, on most starts.
                if (calls++ < 3) throw IllegalStateException("Not an attached client")
                ShizukuStatus.READY
            },
            connect = { helper },
        )
        var got: Result<IStewardHelper>? = null
        scope.launch { got = runCatching { bridge.helper() } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame(helper, got?.getOrThrow())
    }

    /** The 10-27 log: the app leftovers screen scanned app folders and measured deep space at once. */
    @Test
    fun callersAtOnceShareOneBind() {
        var binds = 0
        val answer = CompletableDeferred<IStewardHelper>()
        val bridge = ShizukuBridge(ApplicationProvider.getApplicationContext(), { ShizukuStatus.READY }) {
            binds++
            answer.await()
        }
        val got = mutableListOf<IStewardHelper>()
        repeat(2) { scope.launch { got += bridge.helper() } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, binds)

        val helper = fakeHelper()
        answer.complete(helper)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(helper, helper), got)
        // And later callers take the same helper without binding again.
        scope.launch { got += bridge.helper() }
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(helper, got.last())
        assertEquals(1, binds)
    }

    /** A helper that never answers is an error the screen can show, not a cancellation it quietly swallows. */
    @Test
    fun aHelperThatNeverAnswersFailsWithAnError() {
        val bridge = ShizukuBridge(ApplicationProvider.getApplicationContext(), { ShizukuStatus.READY }) { awaitCancellation() }
        var failure: Throwable? = null
        scope.launch { failure = runCatching { bridge.helper() }.exceptionOrNull() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertTrue("$failure", failure is IOException)
    }

    private fun fakeHelper(): IStewardHelper {
        val binder = Binder()
        return Proxy.newProxyInstance(IStewardHelper::class.java.classLoader, arrayOf(IStewardHelper::class.java)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fake helper"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as IStewardHelper
    }
}
