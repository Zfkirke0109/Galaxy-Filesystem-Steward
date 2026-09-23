package com.galaxy.steward.apps

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.RunLog
import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataExecutor
import com.galaxy.steward.core.appdata.AppDataHelper
import com.galaxy.steward.core.appdata.AppDataReport
import com.galaxy.steward.core.appdata.AppDataScanner
import com.galaxy.steward.core.appdata.AppDataWire
import com.galaxy.steward.core.appdata.AppFolderListing
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
import com.galaxy.steward.core.plural
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.diagnostics.StewardLog
import com.galaxy.steward.shizuku.ShizukuBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** Apps measured so far, and in all, while [statsLoading]. */
    val statsDone: Int = 0,
    val statsTotal: Int = 0,
    /** True once every app's size has been read at least once. */
    val everLoaded: Boolean = false,
    val apps: List<AppStorageRow> = emptyList(),
    val statsError: String? = null,
    val cacheSelected: Set<String> = emptySet(),
    /** Apps picked for "Clear all data"; never preselected. */
    val dataSelected: Set<String> = emptySet(),
    val foldersScanning: Boolean = false,
    val foldersProgress: String? = null,
    val folders: AppDataReport? = null,
    val folderSelected: Set<String> = emptySet(),
    val foldersError: String? = null,
    /** Package name -> app label, for folder rows. */
    val labels: Map<String, String> = emptyMap(),
)

/**
 * The app folder browser: the folder being shown (or loaded), its listing, and the entries you picked (by path).
 * [path] is null on the list of apps.
 */
data class BrowserState(
    val path: String? = null,
    val listing: AppFolderListing? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: Set<String> = emptySet(),
)

/** Result of a cache-only clear run, judged from live storage statistics before and after. */
data class CacheClearResult(val runId: String, val verified: Map<String, Long>, val unchanged: List<String>) {
    val freed: Long get() = verified.values.sum()
}

/**
 * Result of a "Clear all data" run: apps whose data verifiably dropped (package -> bytes freed), apps that showed no
 * change, and apps that were refused, with the reason (by label).
 */
data class DataClearResult(val runId: String, val verified: Map<String, Long>, val unchanged: List<String>, val refused: List<String>) {
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

    private val _browser = MutableStateFlow(BrowserState())
    val browser: StateFlow<BrowserState> = _browser.asStateFlow()
    private var browseJob: Job? = null

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

    /** Set when sizes changed while they were being read (a clear finished): they are read again right after. */
    @Volatile private var reloadStats = false

    /**
     * Reads every app's size. Apps show up as they are measured: Android can take a minute for one app with a huge
     * number of files (Termux, emulators), and the rest shouldn't wait for it. [changed] says sizes changed (after a
     * clear): a read already under way may have missed that, so another one follows it.
     */
    fun loadStats(changed: Boolean = false) {
        refreshAccess()
        if (!_state.value.usageAccess) return
        if (_state.value.statsLoading) {
            if (changed) reloadStats = true
            return
        }
        _state.update { it.copy(statsLoading = true, statsError = null, statsDone = 0, statsTotal = 0) }
        reloadStats = false
        scope.launch {
            val started = SystemClock.uptimeMillis()
            val arrived = ArrayList<AppStorageRow>()
            val slow = ArrayList<Pair<String, Long>>()
            var shownAt = 0L
            try {
                val rows = withContext(Dispatchers.IO) {
                    AppStorage.query(context, onTotal = { n -> _state.update { it.copy(statsTotal = n) } }) { row, millis ->
                        synchronized(arrived) {
                            arrived += row
                            if (millis >= SLOW_QUERY_MS) slow += row.packageName to millis
                            val now = SystemClock.uptimeMillis()
                            if (now - shownAt >= SHOW_EVERY_MS) {
                                shownAt = now
                                showRows(arrived.toList(), done = false)
                            }
                        }
                    }
                }
                val slowest = slow.sortedByDescending { it.second }.take(5)
                StewardLog.i(
                    "app sizes read in ${RunLog.seconds(SystemClock.uptimeMillis() - started)}: ${rows.size} apps" +
                        if (slowest.isEmpty()) "" else "; slow: " + slowest.joinToString { (pkg, ms) -> "$pkg ${RunLog.seconds(ms)}" },
                )
                showRows(rows, done = true)
            } catch (e: SecurityException) {
                StewardLog.w("app sizes need usage access", e)
                _state.update { it.copy(statsLoading = false, usageAccess = false, statsError = "Usage access is needed to read app sizes") }
            } catch (e: CancellationException) {
                _state.update { it.copy(statsLoading = false) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("reading app sizes failed", e)
                _state.update { it.copy(statsLoading = false, statsError = e.message ?: e.javaClass.simpleName) }
            }
            if (reloadStats) loadStats(changed = true)
        }
    }

    /**
     * Shows measured apps. While reading, [rows] replace the ones already shown and the rest stay; once [done] the
     * list is exactly [rows]. Apps seen for the first time get the default cache selection, on the first read only.
     */
    private fun showRows(rows: List<AppStorageRow>, done: Boolean) = _state.update { s ->
        val known = s.apps.mapTo(HashSet()) { it.packageName }
        val firstRead = !s.everLoaded
        val apps = if (done) {
            rows
        } else {
            val byPackage = s.apps.associateByTo(LinkedHashMap()) { it.packageName }
            rows.forEach { byPackage[it.packageName] = it }
            byPackage.values.toList()
        }
        s.copy(
            statsLoading = !done,
            statsDone = if (done) rows.size else maxOf(s.statsDone, rows.size),
            apps = apps,
            everLoaded = s.everLoaded || done,
            cacheSelected = if (firstRead) {
                s.cacheSelected + rows.filter { it.packageName !in known && defaultCacheSelection(it) }.map { it.packageName }
            } else {
                s.cacheSelected
            },
        )
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

    fun toggleData(pkg: String) = _state.update {
        it.copy(dataSelected = if (pkg in it.dataSelected) it.dataSelected - pkg else it.dataSelected + pkg)
    }

    fun clearDataSelection() = _state.update { it.copy(dataSelected = emptySet()) }

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
        loadStats(changed = true)
        return CacheClearResult(runId, verified, targets.filterNot { it in verified }.map { labels.getValue(it) })
    }

    /**
     * Clears all data of each app, one at a time, through Shizuku: `pm clear`, which is what Android's App info >
     * Storage > Clear storage does. Apps [AppPolicy.clearDataBlock] rules out are refused here, and again by the
     * helper. There is no undo; a clear only counts once the app's live data size has dropped.
     */
    suspend fun clearData(packages: List<String>, progress: (Int, Int, String) -> Unit): DataClearResult {
        val requested = packages.distinct()
        val blocked = requested.associateWith { AppPolicy.clearDataBlock(it, ownPackage, AppStorage.isSystem(context, it)) }
            .filterValues { it != null }
        val targets = requested.filterNot { it in blocked }
        val helper = shizuku.helper()
        val runId = journals.newId()
        val labels = requested.associateWith { AppStorage.label(context, it) }
        val verified = LinkedHashMap<String, Long>()
        val unchanged = ArrayList<String>()
        val refused = blocked.map { (pkg, why) -> "${labels.getValue(pkg)}: $why" }.toMutableList()
        withContext(Dispatchers.IO) {
            JournalWriter(journals.fileFor(runId)).use { journal ->
                journal.meta("title", "App data")
                journal.meta("kind", KIND_DATA)
                journal.meta("started", System.currentTimeMillis().toString())
                targets.forEachIndexed { index, pkg ->
                    val label = labels.getValue(pkg)
                    progress(index, targets.size, label)
                    val started = SystemClock.uptimeMillis()
                    val before = AppStorage.dataBytes(context, pkg)
                    // Null from a helper started by an older version of the app, which doesn't know this call yet.
                    val answer = helper.clearAppData(pkg, AppStorage.userId, DATA_CLEAR_TIMEOUT_MS)
                        ?: throw IllegalStateException("The Shizuku helper is out of date. Stop and restart Shizuku, then try again.")
                    if (answer.startsWith("error=")) {
                        StewardLog.w("data clear of $pkg refused by the helper: ${answer.removePrefix("error=")}")
                        refused += "$label: ${answer.removePrefix("error=")}"
                        return@forEachIndexed
                    }
                    // `pm clear` returns once the data is deleted; the statistics may lag a moment behind. It prints
                    // "Failed" and exits with 1 when the package manager refused; a timeout (124) may still have worked.
                    val exit = answer.removePrefix("exit=").toIntOrNull()
                    var freed: Long? = null
                    var waited = if (exit == 0 || exit == 124) 0L else DATA_VERIFY_MS
                    while (true) {
                        val now = AppStorage.dataBytes(context, pkg)
                        if (before != null && now != null && now < before) freed = before - now
                        if (freed != null || waited >= DATA_VERIFY_MS) break
                        delay(DATA_POLL_MS)
                        waited += DATA_POLL_MS
                    }
                    StewardLog.i(
                        "data clear of $pkg: $answer, " +
                            (freed?.let { "freed ${it.humanBytes()}" } ?: "no verified change") +
                            " in ${RunLog.seconds(SystemClock.uptimeMillis() - started)}",
                    )
                    if (freed != null) {
                        verified[pkg] = freed
                        journal.entry(JournalEntry(JournalAction.PURGED, "app-data:$pkg", "files=0", freed, -1, null))
                    } else {
                        unchanged += label
                    }
                }
                journal.meta("finished", System.currentTimeMillis().toString())
                journal.meta("stats", "reset=${verified.size} freed=${verified.values.sum()} skipped=${requested.size - verified.size}")
            }
        }
        _state.update { it.copy(dataSelected = it.dataSelected - verified.keys) }
        progress(targets.size, targets.size, "")
        loadStats(changed = true)
        return DataClearResult(runId, verified, unchanged, refused)
    }

    // ------------------------------------------------------------------ Android/data, obb and media

    fun scanFolders() {
        if (_state.value.foldersScanning) return
        scope.launch {
            _state.update { it.copy(foldersScanning = true, foldersProgress = null, foldersError = null) }
            val started = SystemClock.uptimeMillis()
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
                StewardLog.i(
                    "app folder scan done in ${RunLog.seconds(SystemClock.uptimeMillis() - started)} " +
                        "(${if (shizuku.ready) "through Shizuku" else "in the app"}, ${areas.joinToString { it.name.lowercase() }}): " +
                        "${report.items.size} findings, ${report.items.sumOf { it.bytes }.humanBytes()}, ${report.unreadable.size} folders unreadable",
                )
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
                StewardLog.w("app folder scan failed after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
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

    // ------------------------------------------------------------------ app folder browser

    /** Opens a folder inside `Android/{data,obb,media}/<package>` in the browser. */
    fun openFolder(path: String) {
        browseJob?.cancel()
        _browser.value = BrowserState(path = path, loading = true)
        browseJob = scope.launch {
            val started = SystemClock.uptimeMillis()
            try {
                val listing = listFolder(path)
                StewardLog.i(
                    "app folder listed in ${SystemClock.uptimeMillis() - started} ms: ${listing.entries.size} entries" +
                        if (listing.hidden > 0) " (${listing.hidden} more not shown)" else "",
                )
                _browser.update { if (it.path == path) BrowserState(path = path, listing = listing) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StewardLog.w("listing an app folder failed", e)
                _browser.update { if (it.path == path) it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) else it }
            }
        }
    }

    /** One level up; from an app's own folder back to the list of apps. Returns false when already there. */
    fun browseUp(): Boolean {
        val path = _browser.value.path ?: return false
        val depth = path.removePrefix("${StorageAccess.rootPath}/").count { it == '/' }
        if (depth <= 2) closeBrowser() else openFolder(path.substringBeforeLast('/'))
        return true
    }

    fun closeBrowser() {
        browseJob?.cancel()
        _browser.value = BrowserState()
    }

    /** Lists the current folder again (after a removal). */
    fun refreshBrowser() {
        _browser.value.path?.let(::openFolder)
    }

    fun togglePick(path: String) = _browser.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun setPicked(paths: Collection<String>, selected: Boolean) = _browser.update {
        it.copy(selected = if (selected) it.selected + paths else it.selected - paths.toSet())
    }

    /**
     * Lists one app folder: through Shizuku for Android/data and obb on Android 11+ (no normal app may read them), in
     * the app for Android/media.
     */
    suspend fun listFolder(path: String): AppFolderListing {
        val request = AppDataWire.encodeListRequest(AppDataWire.ListRequest(StorageAccess.rootPath, ownPackage, path))
        val restricted = !path.startsWith("${StorageAccess.rootPath}/Android/media/") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (restricted) {
            val helper = shizuku.helper()
            // Null from a helper started by an older version of the app, which doesn't know this call yet.
            val fd = withContext(Dispatchers.IO) { shizuku.pipeOf(request).use { helper.listAppFolder(it) } }
                ?: throw IllegalStateException("The Shizuku helper is out of date. Stop and restart Shizuku, then try again.")
            return shizuku.readLines(fd) { AppDataHelper.Client.readList(it) }
        }
        return withContext(Dispatchers.IO) {
            val lines = ArrayList<String>()
            AppDataHelper.Helper.list(request) { lines += it }
            AppDataHelper.Client.readList(lines.asSequence())
        }
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
        const val KIND_DATA = "appreset"
        // The clear itself is asynchronous in the package manager; on Samsung the shell call then waits for a callback
        // that never comes (every call hit a 15 s timeout on a real S23), so don't wait long for it.
        private const val CLEAR_TIMEOUT_MS = 6_000L
        private const val POLL_MS = 5_000L
        private const val VERIFY_WINDOW_MS = 45_000L

        // Deleting many gigabytes of files takes a while; the helper allows at most two minutes.
        private const val DATA_CLEAR_TIMEOUT_MS = 90_000L
        private const val DATA_POLL_MS = 1_000L
        private const val DATA_VERIFY_MS = 20_000L

        /** An app whose size takes this long to read is named in the log. */
        private const val SLOW_QUERY_MS = 10_000L
        private const val SHOW_EVERY_MS = 400L

        fun describe(result: CacheClearResult): List<String> = buildList {
            add("${result.verified.size} app cache${if (result.verified.size == 1) "" else "s"} cleared - ${result.freed.humanBytes()} verified")
            if (result.unchanged.isNotEmpty()) add("${result.unchanged.size} showed no change (Android may clear them later)")
        }

        fun describe(result: DataClearResult): List<String> = buildList {
            add("${result.verified.size.plural("app")} reset - ${result.freed.humanBytes()} of app data freed")
            if (result.unchanged.isNotEmpty()) add("${result.unchanged.size.plural("app")} showed no change")
            if (result.refused.isNotEmpty()) add("${result.refused.size.plural("app")} refused to protect your data")
        }
    }
}
