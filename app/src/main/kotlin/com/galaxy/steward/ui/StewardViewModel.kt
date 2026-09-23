package com.galaxy.steward.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galaxy.steward.StewardApp
import com.galaxy.steward.core.ScanPhase
import com.galaxy.steward.core.ScanProgress
import com.galaxy.steward.core.Steward
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.exec.ActionExecutor
import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.ExecutorOptions
import com.galaxy.steward.core.exec.JournalInfo
import com.galaxy.steward.core.exec.PathGuard
import com.galaxy.steward.core.exec.QuarantineManager
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.exec.RollbackSummary
import com.galaxy.steward.core.optimize.VolumeSpace
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.data.AppPreferences
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.work.AuditWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ApplyProgress(val title: String, val done: Int, val total: Int, val current: String)

sealed interface Outcome {
    data class Applied(val title: String, val summary: ExecutionSummary) : Outcome
    data class RolledBack(val title: String, val summary: RollbackSummary) : Outcome
    data class Purged(val bytes: Long) : Outcome
    data class Failed(val message: String) : Outcome
}

data class UiState(
    val hasAccess: Boolean = false,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val report: ScanReport? = null,
    /** True when files changed (undo) or settings changed since the report was built. */
    val stale: Boolean = false,
    val selected: Set<String> = emptySet(),
    val keepers: Map<String, Int> = emptyMap(),
    val applying: ApplyProgress? = null,
    val outcome: Outcome? = null,
    val journals: List<JournalInfo> = emptyList(),
    val quarantineBytes: Long = 0,
    val quarantineByRun: Map<String, Long> = emptyMap(),
    val space: VolumeSpace? = null,
)

class StewardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StewardApp
    val settings: StateFlow<StewardSettings> = app.settings.settings
    val preferences: StateFlow<AppPreferences> = app.settings.app
    val deviceLabel: String = app.environment.deviceLabel
    val rootPath: String = StorageAccess.rootPath

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var applyJob: Job? = null

    init {
        refreshAccess()
        viewModelScope.launch(Dispatchers.IO) {
            if (StorageAccess.hasAccess(app)) quarantine().purgeExpired(settings.value.quarantineRetentionDays)
            refreshHistoryNow()
        }
    }

    private fun quarantine() = QuarantineManager(PathGuard(rootPath, settings.value.protectedFolders), app.journals)

    fun refreshAccess() {
        _state.update { it.copy(hasAccess = StorageAccess.hasAccess(app), space = StorageAccess.space()) }
    }

    // ------------------------------------------------------------------ scanning

    fun startScan() {
        if (_state.value.scanning || _state.value.applying != null) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, progress = ScanProgress(ScanPhase.MAPPING), outcome = null) }
            try {
                val space = StorageAccess.space()
                val report = withContext(Dispatchers.IO) {
                    var last = 0L
                    var lastPhase: ScanPhase? = null
                    Steward(rootPath, settings.value, app.environment, app.hashCacheFile).scan(space) { p ->
                        val now = SystemClock.uptimeMillis()
                        if (p.phase != lastPhase || now - last >= 120) {
                            last = now
                            lastPhase = p.phase
                            _state.update { it.copy(progress = p) }
                        }
                    }
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        progress = null,
                        report = report,
                        stale = false,
                        selected = report.allItems().filter { item -> item.defaultSelected }.map { item -> item.id }.toSet(),
                        keepers = emptyMap(),
                        space = space,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(scanning = false, progress = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanning = false, progress = null, outcome = Outcome.Failed(e.message ?: e.javaClass.simpleName)) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(scanning = false, progress = null, outcome = Outcome.Failed("Not enough memory to map this much storage.")) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    // ------------------------------------------------------------------ selection

    fun toggle(id: String) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun setSelected(ids: Collection<String>, selected: Boolean) = _state.update {
        it.copy(selected = if (selected) it.selected + ids else it.selected - ids.toSet())
    }

    fun setKeeper(groupId: String, index: Int) = _state.update { it.copy(keepers = it.keepers + (groupId to index)) }

    /** Plan item with the user's keeper choice applied. */
    fun effective(item: PlanItem, keepers: Map<String, Int> = _state.value.keepers): PlanItem = when (item) {
        is DuplicateGroup -> keepers[item.id]?.let(item::withKeeper) ?: item
        is FolderDuplicateGroup -> keepers[item.id]?.let(item::withKeeper) ?: item
        else -> item
    }

    fun selectedOf(items: List<PlanItem>): List<PlanItem> {
        val s = _state.value
        return items.filter { it.id in s.selected }.map { effective(it, s.keepers) }
    }

    /** Everything currently selected, in the safest order: dedupe, clean, restructure, then file. */
    fun autopilotItems(): List<PlanItem> {
        val r = _state.value.report ?: return emptyList()
        return selectedOf(r.duplicates) + selectedOf(r.folderDuplicates) + selectedOf(r.folderMerges) +
            selectedOf(r.junk) + selectedOf(r.optimize) + selectedOf(r.organize)
    }

    // ------------------------------------------------------------------ applying

    fun apply(title: String, kind: String, items: List<PlanItem>) {
        if (items.isEmpty() || _state.value.applying != null || _state.value.scanning) return
        applyJob = viewModelScope.launch {
            val total = items.sumOf { it.operations.size }
            _state.update { it.copy(applying = ApplyProgress(title, 0, total, ""), outcome = null) }
            try {
                val s = settings.value
                val summary = withContext(Dispatchers.IO) {
                    var last = 0L
                    ActionExecutor(rootPath, app.journals, ExecutorOptions(s.quarantineDuplicates, s.protectedFolders))
                        .execute(title, kind, items) { done, count, current ->
                            val now = SystemClock.uptimeMillis()
                            if (now - last >= 80 || done == count) {
                                last = now
                                _state.update { it.copy(applying = ApplyProgress(title, done, count, current)) }
                            }
                        }
                }
                StorageAccess.rescan(app, summary.changedPaths)
                _state.update {
                    it.copy(
                        applying = null,
                        outcome = Outcome.Applied(title, summary),
                        report = it.report?.without(summary.completedItemIds),
                        selected = it.selected - summary.completedItemIds,
                        space = StorageAccess.space(),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(applying = null, stale = true) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(applying = null, stale = true, outcome = Outcome.Failed(e.message ?: e.javaClass.simpleName)) }
            } finally {
                refreshHistory()
            }
        }
    }

    fun cancelApply() {
        applyJob?.cancel()
    }

    fun rollback(runId: String, title: String) {
        if (_state.value.applying != null || _state.value.scanning) return
        applyJob = viewModelScope.launch {
            _state.update { it.copy(applying = ApplyProgress("Undoing: $title", 0, 1, ""), outcome = null) }
            try {
                val summary = withContext(Dispatchers.IO) {
                    RollbackEngine(rootPath, app.journals).rollback(runId) { done, total, current ->
                        _state.update { it.copy(applying = ApplyProgress("Undoing: $title", done, total, current)) }
                    }
                }
                StorageAccess.rescan(app, summary.changedPaths)
                _state.update { it.copy(applying = null, stale = true, outcome = Outcome.RolledBack(title, summary), space = StorageAccess.space()) }
            } catch (e: CancellationException) {
                _state.update { it.copy(applying = null, stale = true) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(applying = null, stale = true, outcome = Outcome.Failed(e.message ?: "Undo failed")) }
            } finally {
                refreshHistory()
            }
        }
    }

    /** Empties one run's quarantine, or all of it when [runId] is null. */
    fun purgeQuarantine(runId: String?) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val q = quarantine()
                if (runId == null) q.purgeAll() else q.purge(runId)
            }
            _state.update { it.copy(outcome = Outcome.Purged(freed), space = StorageAccess.space()) }
            refreshHistory()
        }
    }

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) { refreshHistoryNow() }
    }

    private fun refreshHistoryNow() {
        val journals = app.journals.list()
        val byRun = if (StorageAccess.hasAccess(app)) {
            val q = quarantine()
            q.runsWithContent().associateWith { q.sizeOf(it) }.filterValues { it > 0 }
        } else {
            emptyMap()
        }
        _state.update { it.copy(journals = journals, quarantineByRun = byRun, quarantineBytes = byRun.values.sum()) }
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (StewardSettings) -> StewardSettings) {
        app.settings.update(transform)
        if (_state.value.report != null) _state.update { it.copy(stale = true) }
    }

    fun setWeeklyAudit(enabled: Boolean) {
        app.settings.updateApp { it.copy(weeklyAudit = enabled) }
        AuditWorker.schedule(app, enabled)
    }

    fun setDynamicColor(enabled: Boolean) = app.settings.updateApp { it.copy(dynamicColor = enabled) }
}
