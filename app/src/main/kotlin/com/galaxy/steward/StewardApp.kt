package com.galaxy.steward

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.data.AndroidEnvironment
import com.galaxy.steward.data.SettingsStore
import java.io.File

class StewardApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var environment: AndroidEnvironment
        private set
    lateinit var journals: JournalStore
        private set

    val hashCacheFile: File get() = File(filesDir, "hash-cache.tsv")

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        environment = AndroidEnvironment(this)
        journals = JournalStore(File(filesDir, "journals"))
        val channel = NotificationChannel(AUDIT_CHANNEL, getString(R.string.audit_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.audit_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val AUDIT_CHANNEL = "weekly-audit"
    }
}
