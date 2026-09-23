package com.galaxy.steward.apps

import android.content.Context
import android.os.Build
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataExecutor
import com.galaxy.steward.core.appdata.AppDataHelper
import com.galaxy.steward.core.appdata.AppDataReport
import com.galaxy.steward.core.appdata.AppDataScanner
import com.galaxy.steward.core.appdata.AppDataWire
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppPolicy
import com.galaxy.steward.core.appdata.AppScanOptions
import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.JournalWriter
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.exec.RollbackSummary
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.shizuku.ShizukuBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class AppsState(
    val usageAccess: Boolean = false,
    val statsLoading: Boolean = false,
    val apps: List<AppStorageRow> = emptyList(),
    val statsError: String? = null,
    val cacheSelected: Set<String> = emptySet(),
    val foldersScanning: Boolean = false,
    val foldersProgress: String? = null,
    val folders: AppDataReport? = null,
    val folderSelected: Set<String> = emptySet(),
    val foldersError: String? = null,
    /** Package name -> app label, for folder rows. */
    val labels: Map<String, String> = emptyMap(),
)

/** Result of a cache-only clear run, judged from live storage statistics before and after. */
data class CacheClearResult(val runId: String, val verified: Map<String, Long>, val unchanged: List<String>) {
    val freed: Long get() = verified.values.sum()
}

/**
 * App-level state and operations for everything outside shared storage that belongs to apps: per-app storage
 * (StorageStatsManager), cache-only clears, and Android/data|obb|media clean-ups through the Shizuku helper.
 */
class AppsController(
    private val context: Context,
    private val journals: JournalStore,
    val shizuku: ShizukuBridge,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppsState())
    val state: StateFlow<AppsState> = _state.asStateFlow()

    private val ownPackage = context.packageName

    /** Without Shizuku only Android/media is reachable on Android 11+; older versions reach every area. */
    private fun reachableAreas(): Set<AppArea> = when {
        shizuku.ready -> AppArea.entries.toSet()
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> AppArea.entries.toSet()
        else -> setOf(AppArea.MEDIA)
    }

    fun refreshAccess() {
        shizuku.refresh()
        _state.update { it.copy(usageAccess = AppStorage.hasUsageAccess(context)) }
    }

    // ------------------------------------------------------------------ per-app storage

    fun loadStats() {
        refreshAccess()
        if (!_state.value.usageAccess || _state.value.statsLoading) return
        scope.launch {
            _state.update { it.copy(statsLoading = true, statsError = null) }
            try {
                val rows = withContext(Dispatchers.IO) { AppStorage.query(context) }
                _state.update { s ->
                    val fresh = s.apps.isEmpty()
                    s.copy(
                        statsLoading = false,
                        apps = rows,
                        cacheSelected = if (fresh) rows.filter { defaultCacheSelection(it) }.map { it.packageName }.toSet() else s.cacheSelected,
                    )
                }
            } catch (e: SecurityException) {
                _state.update { it.copy(statsLoading = false, usageAccess = false, statsError = "Usage access is needed to read app sizes") }
            } catch (e: Exception) {
                _state.update { it.copy(statsLoading = false, statsError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Like the Termux steward's adapters: caches of 25 MiB and more, never protected apps. */
    private fun defaultCacheSelection(row: AppStorageRow) = !row.protected && row.cacheBytes >= 25 * MIB

    /** Grants usage access through Shizuku when connected. Returns false when the user must do it in Settings. */
    suspend fun grantUsageAccessWithShizuku(): Boolean {
        if (!shizuku.ready) return false
        val ok = try {
            val helper = shizuku.helper()
            withContext(Dispatchers.IO) { helper.grantUsageAccess(ownPackage) }
        } catch (_: Exception) {
            false
        }
        refreshAccess()
        if (ok) loadStats()
        return ok && _state.value.usageAccess
    }

    fun toggleCache(pkg: String) = _state.update {
        it.copy(cacheSelected = if (pkg in it.cacheSelected) it.cacheSelected - pkg else it.cacheSelected + pkg)
    }

    fun setCacheSelected(pkgs: Collection<String>, selected: Boolean) = _state.update {
        it.copy(cacheSelected = if (selected) it.cacheSelected + pkgs else it.cacheSelected - pkgs.toSet())
    }

    /**
     * Cache-only clears through Shizuku (`cmd package clear --cache-only`), four apps at a time. A clear only
     * counts when the app's live cache size actually dropped; the command's own exit code is not trusted.
     */
    suspend fun clearCaches(packages: List<String>, stopFirst: Boolean, progress: (Int, Int, String) -> Unit): CacheClearResult {
        val targets = packages.filterNot { AppPolicy.isProtected(it, ownPackage) }.distinct()
        val helper = shizuku.helper()
        val runId = journals.newId()
        val labels = targets.associateWith { AppStorage.label(context, it) }
        val verified = LinkedHashMap<String, Long>()
        withContext(Dispatchers.IO) {
            JournalWriter(journals.fileFor(runId)).use { journal ->
                journal.meta("title", "App caches")
                journal.meta("kind", KIND_CACHE)
                journal.meta("started", System.currentTimeMillis().toString())
                val before = targets.associateWith { AppStorage.cacheBytes(context, it) ?: 0L }
                var done = 0
                val gate = Semaphore(4)
                coroutineScope {
                    targets.map { pkg ->
                        async {
                            gate.withPermit {
                                val forceStop = stopFirst && AppPolicy.mayForceStop(pkg) && !AppStorage.isSystem(context, pkg)
                                helper.clearAppCache(pkg, AppStorage.userId, forceStop, CLEAR_TIMEOUT_MS)
                                synchronized(verified) { done++ }
                                progress(done, targets.size + 1, labels.getValue(pkg))
                            }
                        }
                    }.awaitAll()
                }
                // Android updates the statistics a little after the clear: poll until every app dropped or time is up.
                progress(targets.size, targets.size + 1, "Checking the results")
                val pending = targets.filter { (before[it] ?: 0L) > 0L }.toMutableSet()
                var waited = 0L
                while (pending.isNotEmpty() && waited < VERIFY_WINDOW_MS) {
                    delay(POLL_MS)
                    waited += POLL_MS
                    pending.toList().forEach { pkg ->
                        val now = AppStorage.cacheBytes(context, pkg) ?: return@forEach
                        val was = before.getValue(pkg)
                        if (now < was) {
                            verified[pkg] = was - now
                            pending -= pkg
                        }
                    }
                }
                verified.forEach { (pkg, bytes) ->
                    journal.entry(JournalEntry(JournalAction.PURGED, "app-cache:$pkg", "files=0", bytes, -1, null))
                }
                journal.meta("finished", System.currentTimeMillis().toString())
                journal.meta("stats", "apps=${verified.size} freed=${verified.values.sum()} skipped=${targets.size - verified.size}")
            }
        }
        progress(targets.size + 1, targets.size + 1, "")
        loadStats()
        return CacheClearResult(runId, verified, targets.filterNot { it in verified }.map { labels.getValue(it) })
    }

    // ------------------------------------------------------------------ Android/data, obb and media

    fun scanFolders() {
        if (_state.value.foldersScanning) return
        scope.launch {
            _state.update { it.copy(foldersScanning = true, foldersProgress = null, foldersError = null) }
            try {
                val areas = reachableAreas()
                val installed = withContext(Dispatchers.IO) { AppStorage.knownPackages(context) }
                val request = AppDataWire.encodeScanRequest(
                    AppDataWire.ScanRequest(StorageAccess.rootPath, installed, AppScanOptions(), ownPackage, areas),
                )
                val onProgress: (String) -> Unit = { pkg -> _state.update { it.copy(foldersProgress = pkg) } }
                val report = if (shizuku.ready) {
                    val helper = shizuku.helper()
                    val fd = withContext(Dispatchers.IO) { shizuku.pipeOf(request).use { helper.scanAppData(it) } }
                    shizuku.readLines(fd) { AppDataHelper.Client.readScan(it, onProgress) }
                } else {
                    withContext(Dispatchers.IO) {
                        val lines = ArrayList<String>()
                        AppDataHelper.Helper.scan(request) { lines += it }
                        AppDataHelper.Client.readScan(lines.asSequence(), onProgress)
                    }
                }
                val labels = withContext(Dispatchers.IO) {
                    (report.usage.map { it.packageName } + report.items.map { it.packageName }).distinct()
                        .associateWith { AppStorage.label(context, it) }
                }
                _state.update {
                    it.copy(
                        foldersScanning = false,
                        foldersProgress = null,
                        folders = report,
                        labels = it.labels + labels,
                        folderSelected = report.items.filter { item -> item.defaultSelected }.map { item -> item.id }.toSet(),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(foldersScanning = false, foldersProgress = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(foldersScanning = false, foldersProgress = null, foldersError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun toggleFolder(id: String) = _state.update {
        it.copy(folderSelected = if (id in it.folderSelected) it.folderSelected - id else it.folderSelected + id)
    }

    fun setFolderSelected(ids: Collection<String>, selected: Boolean) = _state.update {
        it.copy(folderSelected = if (selected) it.folderSelected + ids else it.folderSelected - ids.toSet())
    }

    fun label(pkg: String): String = _state.value.labels[pkg] ?: pkg

    suspend fun applyFolders(title: String, items: List<AppJunkItem>, progress: (Int, Int, String) -> Unit): ExecutionSummary {
        val runId = journals.newId()
        val installed = withContext(Dispatchers.IO) { AppStorage.knownPackages(context) }
            .takeIf { it.size >= AppDataScanner.MIN_KNOWN_PACKAGES }
        val request = AppDataWire.encodeApplyRequest(
            AppDataWire.ApplyRequest(StorageAccess.rootPath, runId, title, ownPackage, installed, items),
        )
        val needsShizuku = items.any { it.area != AppArea.MEDIA } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        // Connect first, so a missing Shizuku never leaves an empty run behind in History.
        val helper = if (needsShizuku || shizuku.ready) shizuku.helper() else null
        val summary = withContext(Dispatchers.IO) {
            JournalWriter(journals.fileFor(runId)).use { journal ->
                if (helper != null) {
                    val fd = shizuku.pipeOf(request).use { helper.applyAppData(it) }
                    shizuku.readLines(fd) { AppDataHelper.Client.readApply(it, journal, progress) }
                } else {
                    AppDataExecutor(StorageAccess.rootPath, journal, runId, ownPackage, installed)
                        .execute(title, items) { d, t, c -> progress(d, t, c) }
                }
            }
        }
        _state.update {
            it.copy(folders = it.folders?.without(summary.completedItemIds), folderSelected = it.folderSelected - summary.completedItemIds)
        }
        return summary
    }

    /** Undo for an app-folder run: quarantined OBBs and leftovers go back (through Shizuku when needed). */
    suspend fun rollback(runId: String, progress: (Int, Int, String) -> Unit): RollbackSummary {
        val entries = journals.entries(runId)
        val root = StorageAccess.rootPath
        val restricted = entries.any { e ->
            val rel = e.a.removePrefix("$root/")
            rel.startsWith("Android/data/") || rel.startsWith("Android/obb/")
        }
        val helper = if (restricted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shizuku.helper() else null
        val summary = withContext(Dispatchers.IO) {
            if (helper != null) {
                val fd = shizuku.pipeOf(AppDataWire.encodeEntries(entries)).use { helper.rollbackAppData(root, it) }
                shizuku.readLines(fd) { AppDataHelper.Client.readRollback(it, progress) }
            } else {
                RollbackEngine(root, null).rollbackEntries(entries) { d, t, c -> progress(d, t, c) }
            }
        }
        journals.appendMeta(runId, "rolledback", System.currentTimeMillis().toString())
        return summary
    }

    companion object {
        const val KIND_FOLDERS = AppDataExecutor.KIND
        const val KIND_CACHE = "appcache"
        private const val CLEAR_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 5_000L
        private const val VERIFY_WINDOW_MS = 45_000L

        fun describe(result: CacheClearResult): List<String> = buildList {
            add("${result.verified.size} app cache${if (result.verified.size == 1) "" else "s"} cleared - ${result.freed.humanBytes()} verified")
            if (result.unchanged.isNotEmpty()) add("${result.unchanged.size} showed no change (Android may clear them later)")
        }
    }
}
