package com.galaxy.steward

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.galaxy.steward.apps.AppsController
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.data.AndroidEnvironment
import com.galaxy.steward.data.SettingsStore
import com.galaxy.steward.diagnostics.LogcatExporter
import com.galaxy.steward.diagnostics.StorageReportExporter
import com.galaxy.steward.shizuku.ShizukuBridge
import com.galaxy.steward.termux.TermuxController
import com.galaxy.steward.ui.StewardSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.galaxy.steward.core.learn.DecisionLog
import com.galaxy.steward.diagnostics.StewardLog
import java.io.File

class StewardApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var environment: AndroidEnvironment
        private set
    lateinit var journals: JournalStore
        private set

    /** App data outside shared storage: per-app sizes, caches, and Android/data|obb|media through Shizuku. */
    lateinit var apps: AppsController
        private set

    /** Termux's private home, reached through Termux's RUN_COMMAND bridge. */
    lateinit var termux: TermuxController
        private set

    /** Saves the device log to Documents/Galaxy Steward LogCat (Settings > Diagnostics). */
    lateinit var logcat: LogcatExporter
    lateinit var storageReport: StorageReportExporter
        private set

    /**
     * Scans and runs belong to the process, not to a screen: they keep going (inside a foreground service) when
     * you switch apps or close the window, and the next screen picks up their state from [session].
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = StewardSession()

    val hashCacheFile: File get() = File(filesDir, "hash-cache.tsv")

    /** Which suggestions you ran and which you left, for learning your defaults (Settings → Learning). */
    val decisions: DecisionLog by lazy { DecisionLog(File(filesDir, "decisions.tsv")) }

    override fun onCreate() {
        super.onCreate()
        StewardLog.init(File(filesDir, "steward-history.log"))
        settings = SettingsStore(this)
        environment = AndroidEnvironment(this)
        journals = JournalStore(File(filesDir, "journals"))
        val shizuku = ShizukuBridge(this)
        apps = AppsController(this, journals, shizuku, appScope)
        termux = TermuxController(this, journals, appScope)
        logcat = LogcatExporter(this, shizuku, appScope)
        storageReport = StorageReportExporter(this, appScope)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(AUDIT_CHANNEL, getString(R.string.audit_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.audit_channel_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(WORK_CHANNEL, getString(R.string.work_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.work_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val AUDIT_CHANNEL = "weekly-audit"
        const val WORK_CHANNEL = "steward-work"
    }
}
