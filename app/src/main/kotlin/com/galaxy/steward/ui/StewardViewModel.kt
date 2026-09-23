package com.galaxy.steward.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.galaxy.steward.StewardApp
import com.galaxy.steward.apps.AppsController
import com.galaxy.steward.core.RunLog
import com.galaxy.steward.core.ScanPhase
import com.galaxy.steward.core.ScanProgress
import com.galaxy.steward.core.Steward
import com.galaxy.steward.core.StewardSettings
import com.galaxy.steward.core.appdata.AppJunkItem
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
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.termux.TermuxItem
import com.galaxy.steward.data.AppPreferences
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.diagnostics.LogcatExporter
import com.galaxy.steward.diagnostics.StewardLog
import com.galaxy.steward.termux.TermuxController
import com.galaxy.steward.work.AuditWorker
import com.galaxy.steward.work.KeepAlive
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

    /** A permanent clean-up (app caches, Termux) summarised as plain lines, with the reasons for skips. */
    data class Report(val title: String, val lines: List<String>, val details: List<String>) : Outcome
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
    /** The media index is being refreshed after a run (in the background; nothing waits on it). */
    val indexing: Boolean = false,
)

/** Process-wide state shared by every [StewardViewModel] instance; runs outlive the screen that started them. */
class StewardSession {
    val state = MutableStateFlow(UiState())
    var scanJob: Job? = null
    var applyJob: Job? = null
    var started = false

    /** A scan or a run (clean-up, undo, app data, Termux) is in progress. */
    fun busy(): Boolean = state.value.let { it.scanning || it.applying != null }
}

class StewardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StewardApp
    val settings: StateFlow<StewardSettings> = app.settings.settings
    val preferences: StateFlow<AppPreferences> = app.settings.app
    val deviceLabel: String = app.environment.deviceLabel
    val rootPath: String = StorageAccess.rootPath

    /** App data (per-app storage, caches, Android/data|obb|media) and Termux. */
    val apps: AppsController = app.apps
    val termux: TermuxController = app.termux
    val logcat: LogcatExporter = app.logcat

    private val session = app.session
    private val scope = app.appScope
    private val _state = session.state
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshAccess()
        if (!session.started) {
            session.started = true
            scope.launch(Dispatchers.IO) {
                if (StorageAccess.hasAccess(app)) quarantine().purgeExpired(settings.value.quarantineRetentionDays)
                refreshHistoryNow()
            }
        }
    }

    /** Holds a foreground service for the duration of [block] so Android does not freeze or kill the run. */
    private suspend fun <T> keepAlive(title: String, block: suspend () -> T): T {
        KeepAlive.begin(app, title)
        try {
            return block()
        } finally {
            KeepAlive.end(app)
        }
    }

    /** Refreshes the media index for [paths] in the background of an already kept-alive job. */
    private suspend fun reindex(paths: Collection<String>) {
        if (paths.isEmpty()) return
        _state.update { it.copy(indexing = true) }
        try {
            KeepAlive.update(app, "Updating the media index", "Galleries and file pickers will show the new layout", 0, 0)
            StorageAccess.rescan(app, paths)
        } finally {
            _state.update { it.copy(indexing = false) }
        }
    }

    private fun quarantine() = QuarantineManager(PathGuard(rootPath, settings.value.protectedFolders), app.journals)

    fun refreshAccess() {
        _state.update { it.copy(hasAccess = StorageAccess.hasAccess(app), space = StorageAccess.space()) }
    }

    // ------------------------------------------------------------------ scanning

    fun startScan() {
        if (_state.value.scanning || _state.value.applying != null) return
        session.scanJob = scope.launch {
            _state.update { it.copy(scanning = true, progress = ScanProgress(ScanPhase.MAPPING), outcome = null) }
            val started = SystemClock.uptimeMillis()
            StewardLog.i("scan started")
            try {
                val space = StorageAccess.space()
                // How long each phase took, for the log line at the end.
                val phases = ArrayList<Pair<ScanPhase, Long>>()
                val report = keepAlive("Scanning storage") { withContext(Dispatchers.IO) {
                    var last = 0L
                    var lastPhase: ScanPhase? = null
                    var phaseStarted = started
                    val result = Steward(rootPath, settings.value, app.environment, app.hashCacheFile).scan(space) { p ->
                        val now = SystemClock.uptimeMillis()
                        if (p.phase != lastPhase || now - last >= 120) {
                            if (p.phase != lastPhase) {
                                lastPhase?.let { phases += it to now - phaseStarted }
                                phaseStarted = now
                            }
                            last = now
                            lastPhase = p.phase
                            _state.update { it.copy(progress = p) }
                            KeepAlive.update(app, "Scanning storage", p.phase.label, (p.overall * 100).toInt(), 100)
                        }
                    }
                    lastPhase?.takeIf { it != ScanPhase.DONE }?.let { phases += it to SystemClock.uptimeMillis() - phaseStarted }
                    result
                } }
                app.settings.lastScanAt = System.currentTimeMillis()
                StewardLog.i(RunLog.scan(report, phases))
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
                StewardLog.i("scan cancelled after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}")
                _state.update { it.copy(scanning = false, progress = null) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("scan failed after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
                _state.update { it.copy(scanning = false, progress = null, outcome = Outcome.Failed(e.message ?: e.javaClass.simpleName)) }
            } catch (e: OutOfMemoryError) {
                StewardLog.w("scan ran out of memory after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
                _state.update { it.copy(scanning = false, progress = null, outcome = Outcome.Failed("Not enough memory to map this much storage.")) }
            }
        }
    }

    fun shouldAskForNotifications(): Boolean = !app.settings.askedForNotifications

    fun markAskedForNotifications() {
        app.settings.askedForNotifications = true
    }

    fun cancelScan() {
        session.scanJob?.cancel()
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
        session.applyJob = scope.launch {
            val total = items.sumOf { it.operations.size }
            _state.update { it.copy(applying = ApplyProgress(title, 0, total, ""), outcome = null) }
            val started = SystemClock.uptimeMillis()
            StewardLog.i("run \"$title\" ($kind) started: ${items.size} items, $total operations")
            try {
                keepAlive(title) {
                    val s = settings.value
                    val summary = withContext(Dispatchers.IO) {
                        var last = 0L
                        ActionExecutor(rootPath, app.journals, ExecutorOptions(s.quarantineDuplicates, s.protectedFolders))
                            .execute(title, kind, items) { done, count, current ->
                                val now = SystemClock.uptimeMillis()
                                if (now - last >= 80 || done == count) {
                                    last = now
                                    _state.update { it.copy(applying = ApplyProgress(title, done, count, current)) }
                                    KeepAlive.update(app, title, current, done, count)
                                }
                            }
                    }
                    StewardLog.i(RunLog.applied(title, kind, summary, SystemClock.uptimeMillis() - started))
                    _state.update {
                        it.copy(
                            applying = null,
                            outcome = Outcome.Applied(title, summary),
                            report = it.report?.without(summary.completedItemIds),
                            selected = it.selected - summary.completedItemIds,
                            space = StorageAccess.space(),
                        )
                    }
                    refreshHistory()
                    reindex(summary.changedPaths)
                }
            } catch (e: CancellationException) {
                StewardLog.i("run \"$title\" cancelled after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}")
                _state.update { it.copy(applying = null, stale = true) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("run \"$title\" failed after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
                _state.update { it.copy(applying = null, stale = true, outcome = Outcome.Failed(e.message ?: e.javaClass.simpleName)) }
            } finally {
                refreshHistory()
            }
        }
    }

    fun cancelApply() {
        session.applyJob?.cancel()
    }

    fun rollback(runId: String, title: String) {
        if (_state.value.applying != null || _state.value.scanning) return
        if (app.journals.info(runId)?.kind == AppsController.KIND_FOLDERS) {
            launchRun("Undoing: $title") { progress ->
                val summary = apps.rollback(runId, progress)
                reindexLater(summary.changedPaths)
                Outcome.RolledBack(title, summary)
            }
            return
        }
        session.applyJob = scope.launch {
            val label = "Undoing: $title"
            _state.update { it.copy(applying = ApplyProgress(label, 0, 1, ""), outcome = null) }
            val started = SystemClock.uptimeMillis()
            StewardLog.i("undo \"$title\" started")
            try {
                keepAlive(label) {
                    val summary = withContext(Dispatchers.IO) {
                        RollbackEngine(rootPath, app.journals).rollback(runId) { done, total, current ->
                            _state.update { it.copy(applying = ApplyProgress(label, done, total, current)) }
                            KeepAlive.update(app, label, current, done, total)
                        }
                    }
                    StewardLog.i(RunLog.rolledBack(title, summary, SystemClock.uptimeMillis() - started))
                    _state.update { it.copy(applying = null, stale = true, outcome = Outcome.RolledBack(title, summary), space = StorageAccess.space()) }
                    refreshHistory()
                    reindex(summary.changedPaths)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(applying = null, stale = true) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("undo \"$title\" failed after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
                _state.update { it.copy(applying = null, stale = true, outcome = Outcome.Failed(e.message ?: "Undo failed")) }
            } finally {
                refreshHistory()
            }
        }
    }

    // ------------------------------------------------------------------ app data and Termux

    /**
     * Runs [block] as the single active run: progress dialog, foreground service, outcome dialog and a history
     * refresh. [block] reports progress through the callback it is given.
     */
    private fun launchRun(title: String, block: suspend (progress: (Int, Int, String) -> Unit) -> Outcome) {
        if (_state.value.applying != null || _state.value.scanning) return
        session.applyJob = scope.launch {
            _state.update { it.copy(applying = ApplyProgress(title, 0, 1, ""), outcome = null) }
            val started = SystemClock.uptimeMillis()
            StewardLog.i("run \"$title\" started")
            try {
                keepAlive(title) {
                    val outcome = block { done, total, current ->
                        _state.update { it.copy(applying = ApplyProgress(title, done, total, current)) }
                        KeepAlive.update(app, title, current, done, total)
                    }
                    StewardLog.i(outcomeLine(title, outcome, SystemClock.uptimeMillis() - started))
                    _state.update { it.copy(applying = null, outcome = outcome, space = StorageAccess.space()) }
                    refreshHistory()
                    pendingReindex?.let {
                        pendingReindex = null
                        reindex(it)
                    }
                }
            } catch (e: CancellationException) {
                StewardLog.i("run \"$title\" cancelled after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}")
                _state.update { it.copy(applying = null) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("run \"$title\" failed after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
                _state.update { it.copy(applying = null, outcome = Outcome.Failed(e.message ?: e.javaClass.simpleName)) }
            } finally {
                refreshHistory()
            }
        }
    }

    private fun outcomeLine(title: String, outcome: Outcome, millis: Long): String = when (outcome) {
        is Outcome.Applied -> RunLog.applied(title, "app data", outcome.summary, millis)
        is Outcome.RolledBack -> RunLog.rolledBack(title, outcome.summary, millis)
        is Outcome.Report -> "run \"$title\" done in ${RunLog.seconds(millis)}: ${outcome.lines.joinToString("; ")}"
        is Outcome.Failed -> "run \"$title\" failed after ${RunLog.seconds(millis)}: ${outcome.message}"
        is Outcome.Purged -> "run \"$title\" done in ${RunLog.seconds(millis)}: emptied ${outcome.bytes.humanBytes()}"
    }

    private var pendingReindex: List<String>? = null

    /** Media paths to rescan once the outcome is on screen (Android/data and obb are never indexed). */
    private fun reindexLater(paths: List<String>) {
        pendingReindex = paths.filterNot { it.startsWith("$rootPath/Android/data/") || it.startsWith("$rootPath/Android/obb/") }
    }

    fun applyAppFolders(items: List<AppJunkItem>) = launchRun("App folder clean-up") { progress ->
        val summary = apps.applyFolders("App folder clean-up", items, progress)
        reindexLater(summary.changedPaths)
        Outcome.Applied("App folder clean-up", summary)
    }

    /**
     * Removes what you picked in the app folder browser: into the quarantine ([quarantine], undoable from History) or
     * deleted for good. The folder is listed again afterwards.
     */
    fun removePicked(quarantine: Boolean) {
        val browser = apps.browser.value
        val listing = browser.listing ?: return
        val items = listing.itemsFor(listing.entries.filter { it.path in browser.selected }, quarantine)
        if (items.isEmpty()) return
        val title = (if (quarantine) "Removed from " else "Deleted from ") + apps.label(listing.packageName)
        launchRun(title) { progress ->
            val summary = apps.applyFolders(title, items, progress)
            reindexLater(summary.changedPaths)
            apps.refreshBrowser()
            Outcome.Applied(title, summary)
        }
    }

    fun clearAppCaches(packages: List<String>, stopFirst: Boolean) = launchRun("Clearing app caches") { progress ->
        val result = apps.clearCaches(packages, stopFirst, progress)
        Outcome.Report("App caches", AppsController.describe(result), result.unchanged.map { "No verified change: $it" })
    }

    fun cleanTermux(items: List<TermuxItem>) = launchRun("Termux clean-up") { progress ->
        progress(0, 1, "Waiting for Termux")
        val (_, summary) = termux.clean(items)
        val lines = buildList {
            add("Freed ${summary.freed.humanBytes()} inside Termux")
            add("${summary.cleared.plural("location")} cleaned")
            if (summary.skipped.isNotEmpty()) add("${summary.skipped.size.plural("location")} left alone for safety")
        }
        Outcome.Report("Termux cleaned", lines, summary.skipped.map { "${it.status.lowercase().replace('_', ' ')}: ${it.path.ifEmpty { it.targetId }} ${it.note}".trim() })
    }

    /** Empties one run's quarantine, or all of it when [runId] is null. */
    fun purgeQuarantine(runId: String?) {
        scope.launch {
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
        scope.launch(Dispatchers.IO) { refreshHistoryNow() }
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
