package com.galaxy.steward.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.DeviceEnvironment
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.core.appdata.AppPolicy
import com.galaxy.steward.core.footprint.AppFootprint
import com.galaxy.steward.core.footprint.FootprintHit
import com.galaxy.steward.core.footprint.FootprintPlace
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.termux.TermuxController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An app whose leftovers to look for: installed or not, with why it is suggested. */
data class FootprintApp(val packageName: String?, val label: String, val installed: Boolean, val why: String) {
    val key: String get() = packageName ?: "query:$label"
}

data class FootprintState(
    val suggestions: List<FootprintApp> = emptyList(),
    val target: FootprintApp? = null,
    val tokens: Set<String> = emptySet(),
    val hits: List<FootprintHit> = emptyList(),
    val searching: Boolean = false,
    /** Picked hits, by [key]. */
    val selected: Set<String> = emptySet(),
) {
    fun key(hit: FootprintHit): String = hit.place.name + ":" + hit.path
    val picked: List<FootprintHit> get() = hits.filter { key(it) in selected && it.lock == null }
}

/**
 * "Everything an app left behind": one app (installed, removed, or just a word you type) and every folder and file
 * named after it in shared storage, Android/data|obb|media, Termux and the shell's places, from the last scans. The
 * removal itself is the view model's, which runs each place through its own checked path.
 */
class AppFootprintController(
    private val context: Context,
    private val environment: DeviceEnvironment,
    private val apps: AppsController,
    private val termux: TermuxController,
    private val deepSpace: DeepSpaceController,
    private val scope: CoroutineScope,
    private val report: () -> ScanReport?,
) {
    private val _state = MutableStateFlow(FootprintState())
    val state: StateFlow<FootprintState> = _state.asStateFlow()
    private var job: Job? = null

    /** Apps that are gone but left something: from usage history, leftover app folders and installers nobody installed. */
    fun suggest() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { removedApps() }
            _state.update { it.copy(suggestions = list) }
        }
    }

    private fun removedApps(): List<FootprintApp> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, FootprintApp>()
        fun installed(pkg: String) = try {
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        fun add(pkg: String, why: String) {
            if (!AppPolicy.isPackageName(pkg) || pkg in out || installed(pkg)) return
            if (AppFootprint.tokens(pkg).isEmpty()) return
            out[pkg] = FootprintApp(pkg, nameOf(pkg), false, why)
        }
        // Usage history, where Android still keeps it for a removed app.
        val now = System.currentTimeMillis()
        runCatching {
            context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 60 * DAY_MS, now).keys
        }.getOrDefault(emptySet<String>()).forEach { add(it, "used in the last two months, now removed") }
        apps.state.value.folders?.usage?.filter { !it.installed }?.forEach { add(it.packageName, "left its ${it.area.label} folder") }
        report()?.junk?.filter { it.category == JunkCategory.ORPHANED_APP_FOLDERS }?.forEach { add(it.title, "left a folder in shared storage") }
        report()?.tree?.let { tree -> installers(tree).forEach { (path, pkg) -> add(pkg, "its installer is in ${path.substringBeforeLast('/').substringAfterLast('/')}") } }
        return out.values.toList()
    }

    /** "com.wowee.client" reads as "Wowee". */
    private fun nameOf(pkg: String): String =
        AppFootprint.tokens(pkg).firstOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg

    /** APK files in shared storage with their package names (parsed once and cached by the scan's environment). */
    private fun installers(tree: StorageTree): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        tree.root.walkFiles { f ->
            if (out.size < 400 && f.name.endsWith(".apk", ignoreCase = true) && f.zone.removable) {
                environment.apkInfo(f.path)?.let { out += f.path to it.packageName }
            }
        }
        return out
    }

    /** Looks for an installed app by name or package, or uses [query] as the word to look for. */
    fun find(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val installed = environment.installedApps()
        val app = installed.entries.firstOrNull { (pkg, label) -> pkg.equals(q, true) || label.equals(q, true) }?.let { (pkg, label) ->
            FootprintApp(pkg, label, true, "installed")
        } ?: _state.value.suggestions.firstOrNull { it.packageName.equals(q, true) || it.label.equals(q, true) }
            ?: FootprintApp(null, q, false, "a word you looked for")
        open(app)
    }

    fun open(app: FootprintApp) {
        val tokens = if (app.packageName == null) AppFootprint.tokens(query = app.label) else AppFootprint.tokens(app.packageName, app.label)
        job?.cancel()
        _state.update { it.copy(target = app, tokens = tokens, hits = emptyList(), selected = emptySet(), searching = true) }
        job = scope.launch {
            val hits = withContext(Dispatchers.Default) { gather(app, tokens) }
            _state.update { s ->
                if (s.target != app) return@update s
                // What a removed app left is picked; for an app still installed, or a word, you pick.
                val picked = if (app.installed || app.packageName == null) emptySet() else hits.filter { it.lock == null }.mapTo(HashSet()) { s.key(it) }
                s.copy(hits = hits, searching = false, selected = picked)
            }
        }
    }

    fun close() {
        job?.cancel()
        _state.update { it.copy(target = null, tokens = emptySet(), hits = emptyList(), selected = emptySet(), searching = false) }
    }

    fun toggle(hit: FootprintHit) = _state.update { s ->
        val k = s.key(hit)
        s.copy(selected = if (k in s.selected) s.selected - k else s.selected + k)
    }

    private fun gather(app: FootprintApp, tokens: Set<String>): List<FootprintHit> {
        val out = ArrayList<FootprintHit>()
        val tree = report()?.tree
        if (tree != null) {
            out += AppFootprint.inTree(tree, tokens)
            // Installers of it under another name ("app-release.apk"), known by the package inside.
            app.packageName?.let { pkg ->
                val known = out.mapTo(HashSet()) { it.path }
                installers(tree).filter { (path, p) -> p == pkg && path !in known && known.none { path.startsWith("$it/") } }.forEach { (path, _) ->
                    val f = java.io.File(path)
                    out += FootprintHit(FootprintPlace.SHARED, path, f.length(), 1, false, f.lastModified())
                }
            }
        }
        // Its own Android/data, obb and media folders: a removed app's go as leftovers; an installed app keeps them.
        val folders = apps.state.value.folders
        val leftovers = folders?.items?.filter { it.kind == AppJunkKind.LEFTOVERS }?.associateBy { "${it.area.dir}:${it.packageName}" }.orEmpty()
        folders?.usage?.filter { u -> u.packageName == app.packageName || AppFootprint.matches(u.packageName, tokens) }?.forEach { u ->
            val lock = when {
                u.installed -> "Installed: its own folder stays while it is (Clear all data resets it)"
                u.protected -> "Protected"
                "${u.area.dir}:${u.packageName}" !in leftovers -> "Not a leftover the app folder scan can remove"
                else -> null
            }
            out += FootprintHit(
                FootprintPlace.APP_FOLDERS, "${StorageAccess.rootPath}/Android/${u.area.dir}/${u.packageName}", u.bytes, u.files, true, 0, lock, u.packageName,
            )
        }
        termux.state.value.report?.let { out += AppFootprint.inTermux(it, tokens) }
        deepSpace.state.value.shell.filter { AppFootprint.matches(it.name, tokens) }.forEach { e ->
            out += FootprintHit(FootprintPlace.SHELL, e.path, e.bytes, 0, e.isDirectory, e.mtime, e.lock)
        }
        return out
    }

    /** Drops what went, after a removal ran. */
    fun forget(paths: Set<String>) = _state.update { s ->
        s.copy(hits = s.hits.filterNot { it.path in paths }, selected = s.selected.filterTo(HashSet()) { k -> k.substringAfter(':') !in paths })
    }
}
