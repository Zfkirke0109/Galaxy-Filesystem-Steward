package com.galaxy.steward.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.galaxy.steward.core.termux.TermuxScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class TermuxStatus(val label: String) {
    NOT_INSTALLED("Termux is not installed"),
    NO_PERMISSION("Allow Galaxy Steward to run commands in Termux"),
    READY("Termux is connected"),
}

/** What Termux reported back for one command (keys from Termux's plugin result bundle). */
data class TermuxResult(val stdout: String, val stderr: String, val exitCode: Int?, val err: Int, val errmsg: String) {
    /** Termux only runs commands from other apps after `allow-external-apps = true` is set. */
    val needsExternalApps: Boolean get() = errmsg.contains("allow-external-apps", ignoreCase = true)
}

class TermuxSetupException(message: String) : Exception(message)

/**
 * Runs commands inside Termux through its documented RUN_COMMAND service. Termux answers through a
 * PendingIntent to [TermuxResultReceiver]; each call waits for its own answer.
 */
class TermuxBridge(private val context: Context) {
    fun status(): TermuxStatus = when {
        !installed() -> TermuxStatus.NOT_INSTALLED
        ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED -> TermuxStatus.NO_PERMISSION
        else -> TermuxStatus.READY
    }

    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(TermuxScript.PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun openTermux(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TermuxScript.PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    suspend fun run(arguments: List<String>, timeoutMs: Long): TermuxResult {
        if (status() != TermuxStatus.READY) throw TermuxSetupException(status().label)
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Bundle>()
        pending[id] = answer
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                // Termux fills in the result extras, so the intent has to stay mutable.
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_CALL_ID, id),
                flags,
            )
            val intent = Intent(ACTION_RUN_COMMAND)
                .setClassName(TermuxScript.PACKAGE, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", TermuxScript.BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments.toTypedArray())
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", TermuxScript.HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Galaxy Steward")
                .putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Storage audit and clean-up requested by Galaxy Steward")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                ContextCompat.startForegroundService(context, intent)
            }
            val bundle = withTimeout(timeoutMs) { answer.await() }
            return TermuxResult(
                stdout = bundle.getString("stdout").orEmpty(),
                stderr = bundle.getString("stderr").orEmpty(),
                exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
                err = bundle.getInt("err", 0),
                errmsg = bundle.getString("errmsg").orEmpty(),
            )
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val EXTRA_CALL_ID = "call"

        /** One line to paste into Termux so it accepts commands from other apps (Termux's own setting). */
        const val ENABLE_COMMAND =
            "mkdir -p ~/.termux && sed -i '/^allow-external-apps/d' ~/.termux/termux.properties 2>/dev/null; " +
                "echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"

        private val ids = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        internal fun deliver(id: Int, result: Bundle) {
            pending.remove(id)?.complete(result)
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxBridge.EXTRA_CALL_ID, -1)
        val result = intent.getBundleExtra("result") ?: Bundle()
        TermuxBridge.deliver(id, result)
    }
}
