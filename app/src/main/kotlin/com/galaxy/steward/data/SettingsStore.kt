package com.galaxy.steward.data

import android.content.Context
import androidx.core.content.edit
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.organize.KeywordRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-level preferences that are not engine settings. */
data class AppPreferences(
    val weeklyAudit: Boolean = false,
    val dynamicColor: Boolean = true,
)

/** SharedPreferences-backed store for [StewardSettings], exposed as flows for the UI. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("steward", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<StewardSettings> = _settings.asStateFlow()

    private val _app = MutableStateFlow(readApp())
    val app: StateFlow<AppPreferences> = _app.asStateFlow()

    fun update(transform: (StewardSettings) -> StewardSettings) {
        val next = transform(_settings.value)
        writeSettings(next)
        _settings.value = next
    }

    fun updateApp(transform: (AppPreferences) -> AppPreferences) {
        val next = transform(_app.value)
        prefs.edit {
            putBoolean("weeklyAudit", next.weeklyAudit)
            putBoolean("dynamicColor", next.dynamicColor)
        }
        _app.value = next
    }

    private fun readApp() = AppPreferences(
        weeklyAudit = prefs.getBoolean("weeklyAudit", false),
        dynamicColor = prefs.getBoolean("dynamicColor", true),
    )

    private fun readSettings(): StewardSettings {
        val d = StewardSettings()
        return StewardSettings(
            minDuplicateBytes = prefs.getLong("minDuplicateBytes", d.minDuplicateBytes),
            minDuplicateFolderBytes = prefs.getLong("minDuplicateFolderBytes", d.minDuplicateFolderBytes),
            quarantineDuplicates = prefs.getBoolean("quarantineDuplicates", d.quarantineDuplicates),
            quarantineRetentionDays = prefs.getInt("quarantineRetentionDays", d.quarantineRetentionDays),
            staleTempDays = prefs.getInt("staleTempDays", d.staleTempDays),
            oldLogDays = prefs.getInt("oldLogDays", d.oldLogDays),
            flatDirThreshold = prefs.getInt("flatDirThreshold", d.flatDirThreshold),
            mediaYearBuckets = prefs.getBoolean("mediaYearBuckets", d.mediaYearBuckets),
            recentFileGuardMinutes = prefs.getInt("recentFileGuardMinutes", d.recentFileGuardMinutes),
            includeHiddenInDuplicates = prefs.getBoolean("includeHiddenInDuplicates", d.includeHiddenInDuplicates),
            protectedFolders = prefs.getString("protectedFolders", "").orEmpty().lines().filter { it.isNotBlank() },
            customRules = prefs.getString("customRules", "").orEmpty().lines().mapNotNull(KeywordRule::decode),
            hashWorkers = prefs.getInt("hashWorkers", d.hashWorkers),
        )
    }

    private fun writeSettings(s: StewardSettings) {
        prefs.edit {
            putLong("minDuplicateBytes", s.minDuplicateBytes)
            putLong("minDuplicateFolderBytes", s.minDuplicateFolderBytes)
            putBoolean("quarantineDuplicates", s.quarantineDuplicates)
            putInt("quarantineRetentionDays", s.quarantineRetentionDays)
            putInt("staleTempDays", s.staleTempDays)
            putInt("oldLogDays", s.oldLogDays)
            putInt("flatDirThreshold", s.flatDirThreshold)
            putBoolean("mediaYearBuckets", s.mediaYearBuckets)
            putInt("recentFileGuardMinutes", s.recentFileGuardMinutes)
            putBoolean("includeHiddenInDuplicates", s.includeHiddenInDuplicates)
            putString("protectedFolders", s.protectedFolders.joinToString("\n"))
            putString("customRules", s.customRules.joinToString("\n") { it.encode() })
            putInt("hashWorkers", s.hashWorkers)
        }
    }

    companion object {
        val DUPLICATE_SIZE_CHOICES = listOf(64 * 1024L, 256 * 1024L, MIB, 10 * MIB)
    }
}
