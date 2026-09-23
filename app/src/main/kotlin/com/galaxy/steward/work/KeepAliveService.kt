package com.galaxy.steward.work

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.galaxy.steward.R
import com.galaxy.steward.StewardApp
import java.util.concurrent.atomic.AtomicInteger

/**
 * A foreground service that exists only while the steward is scanning, applying a run or refreshing the media
 * index. Without it Android treats the app as cached as soon as you switch away, freezes it mid-run and, on
 * Samsung One UI, kills it for "excessive binder traffic during cached" while the media index is still updating.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.work_default_title)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            // Always enter the foreground first: stopping a service started with startForegroundService() before
            // it calls startForeground() crashes the app.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, KeepAlive.notification(this, title, null, 0, 0), type)
        } catch (_: RuntimeException) {
            // Not allowed right now (for example the data-sync time budget is used up): run without the guard.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP && !KeepAlive.active) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15+ ends data-sync services after their daily budget; the run itself continues regardless. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 7301
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP = "com.galaxy.steward.action.STOP_KEEP_ALIVE"
    }
}

/** Reference-counted access to [KeepAliveService], so overlapping jobs share one notification. */
object KeepAlive {
    private val holders = AtomicInteger()

    @Volatile
    private var lastUpdate = 0L

    fun begin(context: Context, title: String) {
        holders.incrementAndGet()
        val intent = Intent(context, KeepAliveService::class.java).putExtra(KeepAliveService.EXTRA_TITLE, title)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: RuntimeException) {
            // Starting a foreground service from the background is restricted; the job still runs.
        }
    }

    /** Refreshes the notification text at most once a second. */
    fun update(context: Context, title: String, text: String?, done: Int, total: Int) {
        if (holders.get() <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastUpdate < 1000 && done < total) return
        lastUpdate = now
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(KeepAliveService.NOTIFICATION_ID, notification(context, title, text, done, total))
    }

    val active: Boolean get() = holders.get() > 0

    fun end(context: Context) {
        if (holders.decrementAndGet() > 0) return
        holders.set(0)
        // Routed through onStartCommand so the service is always in the foreground before it stops.
        val stop = Intent(context, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_STOP)
        try {
            context.startService(stop)
        } catch (_: RuntimeException) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    internal fun notification(context: Context, title: String, text: String?, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, StewardApp.WORK_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_steward)
            .setContentTitle(title)
            .setContentText(text ?: context.getString(R.string.work_keep_open))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(total, done, total <= 0)
            .setContentIntent(context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            })
            .build()
}
