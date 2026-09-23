package com.galaxy.steward.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.galaxy.steward.R
import com.galaxy.steward.StewardApp
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.Steward
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.QuarantineManager
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Weekly, read-only survey (the Termux steward's `audit` mode) run while charging. It never changes files;
 * it only empties quarantines past their retention period and posts a summary notification.
 */
class AuditWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as StewardApp
        if (!StorageAccess.hasAccess(app)) return Result.success()
        val settings = app.settings.settings.value
        val root = StorageAccess.rootPath
        QuarantineManager(PathGuard(root, settings.protectedFolders), app.journals).purgeExpired(settings.quarantineRetentionDays)

        val report = Steward(root, settings, app.environment, app.hashCacheFile).scan(StorageAccess.space())
        val reclaimable = report.duplicateBytes + report.junkBytes
        val toFile = report.organize.size
        if (reclaimable < 50 * MIB && toFile < 20) return Result.success()

        val text = buildList {
            if (report.duplicateBytes > 0) add("${report.duplicateBytes.humanBytes()} in duplicates")
            if (report.junkBytes > 0) add("${report.junkBytes.humanBytes()} of clutter")
            if (toFile > 0) add("$toFile items to organise")
        }.joinToString(" · ")
        notify(app, "Koa found ${reclaimable.humanBytes()} to reclaim", text)
        return Result.success()
    }

    private fun notify(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, StewardApp.AUDIT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_steward)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\nNothing was changed - open the app to review."))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val WORK_NAME = "weekly-audit"
        private const val NOTIFICATION_ID = 7

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AuditWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
