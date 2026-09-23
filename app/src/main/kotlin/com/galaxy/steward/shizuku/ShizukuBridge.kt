package com.galaxy.steward.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.galaxy.steward.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ShizukuStatus(val label: String) {
    NOT_INSTALLED("Shizuku is not installed"),
    NOT_RUNNING("Shizuku is installed but not running"),
    NO_PERMISSION("Shizuku is running - allow Galaxy Steward to use it"),
    READY("Connected through Shizuku"),
}

/**
 * Connects to the Shizuku service (ADB-level access without root) and binds [StewardHelperService] inside it.
 * All state changes arrive through Shizuku's listeners; [status] is what the UI shows.
 */
class ShizukuBridge(private val context: Context) {
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

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            helper = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _status.value = when {
            !installed() -> ShizukuStatus.NOT_INSTALLED
            !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuStatus.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.READY
        }
    }

    val ready: Boolean get() = status.value == ShizukuStatus.READY

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun launchManager(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The helper, binding it first if needed (on the main thread, like any service binding). Throws when Shizuku is not ready. */
    suspend fun helper(): IStewardHelper = withContext(Dispatchers.Main.immediate) {
        refresh()
        check(ready) { status.value.label }
        helper?.takeIf { it.asBinder().pingBinder() }?.let { return@withContext it }
        withTimeout(BIND_TIMEOUT_MS) {
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
        }
    }

    /**
     * Writes [request] into a pipe on a background thread and returns the read end for the helper. Binder
     * duplicates the descriptor, so callers close their copy right after the call (`pipeOf(x).use { ... }`).
     */
    fun pipeOf(request: String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(request.toByteArray()) }
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
    }
}
