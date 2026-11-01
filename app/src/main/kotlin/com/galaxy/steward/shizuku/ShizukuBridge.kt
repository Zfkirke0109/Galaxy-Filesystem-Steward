package com.galaxy.steward.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.galaxy.steward.BuildConfig
import com.galaxy.steward.diagnostics.StewardLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ShizukuStatus(val label: String) {
    NOT_INSTALLED("Shizuku is not installed"),
    NOT_RUNNING("Shizuku is installed but not running"),
    CONNECTING("Shizuku is still connecting to this app"),
    NO_PERMISSION("Shizuku is running - allow Galaxy Steward to use it"),
    READY("Connected through Shizuku"),
}

/**
 * Connects to the Shizuku service (ADB-level access without root) and binds [StewardHelperService] inside it.
 * All state changes arrive through Shizuku's listeners; [status] is what the UI shows.
 */
class ShizukuBridge(
    private val context: Context,
    /** Reads Shizuku's state; throws while Shizuku can't answer this process yet. Tests replace it. */
    private val probe: () -> ShizukuStatus = { probeShizuku(context) },
    /** Starts the helper and waits for it to answer; tests replace it. */
    private val connect: (suspend () -> IStewardHelper)? = null,
) {
    private val _status = MutableStateFlow(ShizukuStatus.NOT_INSTALLED)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var helper: IStewardHelper? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, StewardHelperService::class.java.name))
        .daemon(false)
        .processNameSuffix("helper")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var connection: ServiceConnection? = null

    /** One bind at a time; see [helper]. */
    private val binding = Mutex()

    // Declared before init, which calls refresh(): Kotlin initializes properties in order, and a retry posted from
    // there found this still null and crashed the app at start (seen in the 1.2.5 log).
    private val main = Handler(Looper.getMainLooper())
    private var attempts = 0
    private val retry = Runnable { probeNow() }

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            helper = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    /** Asks Shizuku for its state now, and again for a while if it is still attaching this app. */
    fun refresh() {
        attempts = 0
        probeNow()
    }

    private fun probeNow() {
        main.removeCallbacks(retry)
        _status.value = try {
            probe().also { attempts = 0 }
        } catch (e: RuntimeException) {
            // Shizuku hands a starting app its binder first and attaches it as a client a moment later. Asked in
            // between, it answers "Not an attached client" (most starts in the 10-27 to 10-30 logs) or, right after an
            // update, "binder haven't been received" (1.2.5, where it crashed the app). Both pass by themselves, and the
            // binder listener above asks again once Shizuku has attached the app: until then it is connecting, not
            // "not running". Asked again with growing pauses in case that listener never comes.
            if (attempts == 0) StewardLog.i("Shizuku is still attaching this app: ${e.message}")
            if (attempts < RETRY_MS.size) {
                main.postDelayed(retry, RETRY_MS[attempts])
            } else if (attempts == RETRY_MS.size) {
                StewardLog.w("Shizuku hasn't attached this app after ${RETRY_MS.sum() / 1000} s", e)
            }
            attempts++
            ShizukuStatus.CONNECTING
        }
    }

    val ready: Boolean get() = status.value == ShizukuStatus.READY

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                // Denied with "don't ask again": asking does nothing any more, but Shizuku's own app can still allow it.
                if (Shizuku.shouldShowRequestPermissionRationale()) launchManager() else Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: RuntimeException) {
            StewardLog.w("Shizuku could not ask for permission", e)
            refresh()
        }
    }

    fun launchManager(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * The helper, binding it first if needed (on the main thread, like any service binding). Throws when Shizuku is
     * not ready, and an [IOException] when the helper doesn't answer in time.
     */
    suspend fun helper(): IStewardHelper = withContext(Dispatchers.Main.immediate) {
        refresh()
        // Right at start Shizuku may still be attaching this app; that takes a moment.
        if (status.value == ShizukuStatus.CONNECTING) {
            withTimeoutOrNull(ATTACH_WAIT_MS) { status.first { it != ShizukuStatus.CONNECTING } }
        }
        check(ready) { status.value.label }
        // Callers at once share one bind. Two binds at once dropped the first one's connection (a new bind clears every
        // connection Shizuku holds for the helper), and its caller waited the whole timeout for nothing: the 10-27 log,
        // where the app leftovers screen scanned app folders and measured deep space together.
        alive() ?: binding.withLock { alive() ?: bind() }
    }

    private fun alive(): IStewardHelper? = helper?.takeIf { it.asBinder().pingBinder() }

    private suspend fun bind(): IStewardHelper {
        val started = SystemClock.uptimeMillis()
        return try {
            withTimeout(BIND_TIMEOUT_MS) { (connect ?: ::bindUserService)() }.also {
                helper = it
                StewardLog.i("Shizuku helper connected in ${SystemClock.uptimeMillis() - started} ms")
            }
        } catch (e: TimeoutCancellationException) {
            StewardLog.w("Shizuku helper did not connect after ${SystemClock.uptimeMillis() - started} ms", e)
            removeStuckHelper()
            // Not a CancellationException: the screens rethrow those as the user leaving, and showed nothing at all.
            throw IOException("The Shizuku helper didn't answer in ${BIND_TIMEOUT_MS / 1000} s. Stop and start Shizuku, then try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StewardLog.w("Shizuku helper did not connect after ${SystemClock.uptimeMillis() - started} ms", e)
            throw e
        }
    }

    private suspend fun bindUserService(): IStewardHelper =
        suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val h = binder?.takeIf { it.pingBinder() }?.let(IStewardHelper.Stub::asInterface)
                    helper = h
                    if (!cont.isActive) return
                    if (h != null) cont.resume(h) else cont.resumeWithException(IllegalStateException("The Shizuku helper did not start"))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    helper = null
                }
            }
            // A stale connection from a helper that died is replaced, not stacked.
            connection?.let { old -> runCatching { Shizuku.unbindUserService(args, old, false) } }
            connection = conn
            try {
                Shizuku.bindUserService(args, conn)
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /** A helper that never answered is stopped, so the next try starts a new one instead of waiting on it again. */
    private fun removeStuckHelper() {
        val conn = connection ?: return
        connection = null
        runCatching {
            Shizuku.unbindUserService(args, conn, false)
            Shizuku.unbindUserService(args, null, true)
        }.onFailure { StewardLog.w("Shizuku could not stop the helper", it) }
    }

    /**
     * Writes [request] into a pipe on a background thread and returns the read end for the helper. Binder
     * duplicates the descriptor, so callers close their copy right after the call (`pipeOf(x).use { ... }`).
     */
    fun pipeOf(request: String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            // The helper may be gone or have closed its end (EPIPE): the call itself reports that. Uncaught here, the
            // IOException ended the whole app.
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(request.toByteArray()) }
            } catch (_: IOException) {
            }
        }.apply { name = "steward-request" }.start()
        return read
    }

    /** Reads the helper's line stream to the end on the IO dispatcher. */
    suspend fun <T> readLines(fd: ParcelFileDescriptor, block: (Sequence<String>) -> T): T = withContext(Dispatchers.IO) {
        BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(fd), Charsets.UTF_8)).use { reader ->
            block(generateSequence { reader.readLine() })
        }
    }

    companion object {
        const val MANAGER = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 7302
        private const val BIND_TIMEOUT_MS = 20_000L
        private const val ATTACH_WAIT_MS = 5_000L

        /** About 30 s in all. */
        private val RETRY_MS = longArrayOf(250, 500, 1_000, 2_000, 4_000, 8_000, 15_000)
    }
}

private fun probeShizuku(context: Context): ShizukuStatus {
    val installed = try {
        context.packageManager.getPackageInfo(ShizukuBridge.MANAGER, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
    return when {
        !installed -> ShizukuStatus.NOT_INSTALLED
        !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
        Shizuku.isPreV11() -> ShizukuStatus.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }
}
