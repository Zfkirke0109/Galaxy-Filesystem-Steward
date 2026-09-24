package com.galaxy.steward.termux

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.galaxy.steward.core.RunLog
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.exec.JournalAction
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalStore
import com.galaxy.steward.core.exec.JournalWriter
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.termux.TermuxCleanSummary
import com.galaxy.steward.core.termux.TermuxException
import com.galaxy.steward.core.termux.TermuxItem
import com.galaxy.steward.core.termux.TermuxPackage
import com.galaxy.steward.core.termux.TermuxProgram
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.core.termux.TermuxRemovalPlan
import com.galaxy.steward.core.termux.TermuxRepo
import com.galaxy.steward.core.termux.TermuxReport
import com.galaxy.steward.core.termux.TermuxScript
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.diagnostics.StewardLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TermuxState(
    val status: TermuxStatus = TermuxStatus.NOT_INSTALLED,
    val auditing: Boolean = false,
    val report: TermuxReport? = null,
    val selected: Set<String> = emptySet(),
    val error: String? = null,
    /** Termux refused because `allow-external-apps` is not enabled. */
    val needsExternalApps: Boolean = false,
    /** Installed packages, largest first, once loaded on the Packages screen. */
    val packages: List<TermuxPackage>? = null,
    val packagesLoading: Boolean = false,
    val packagesError: String? = null,
    val packageSelected: Set<String> = emptySet(),
    /** What apt would remove for the selected packages, while it is being asked or shown. */
    val planning: Boolean = false,
    val plan: TermuxRemovalPlan? = null,
    /** Files and folders picked in the Termux browser, by path. */
    val browseSelected: Set<String> = emptySet(),
    /** Programs npm, pip and cargo installed, from the same Packages run. */
    val programs: List<TermuxProgram>? = null,
    val programSelected: Set<String> = emptySet(),
    /** Git repositories in Termux, its distributions and shared storage, once loaded on the Repositories screen. */
    val repos: List<TermuxRepo>? = null,
    val reposLoading: Boolean = false,
    val reposError: String? = null,
    val repoSelected: Set<String> = emptySet(),
)

class TermuxController(
    private val context: Context,
    private val journals: JournalStore,
    private val scope: CoroutineScope,
) {
    val bridge = TermuxBridge(context)
    private val _state = MutableStateFlow(TermuxState(status = bridge.status()))
    val state: StateFlow<TermuxState> = _state.asStateFlow()

    fun refresh() = _state.update { it.copy(status = bridge.status()) }

    fun audit() {
        refresh()
        if (_state.value.auditing || _state.value.status != TermuxStatus.READY) return
        scope.launch {
            _state.update { it.copy(auditing = true, error = null, needsExternalApps = false) }
            val started = SystemClock.uptimeMillis()
            try {
                val output = run("audit", emptyList(), AUDIT_TIMEOUT_MS)
                val report = withContext(Dispatchers.Default) { TermuxProtocol.parseAudit(output) }
                StewardLog.i(RunLog.termux(report, SystemClock.uptimeMillis() - started))
                _state.update {
                    it.copy(auditing = false, report = report, selected = report.items.filter { i -> i.defaultSelected }.map { i -> i.spec }.toSet())
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(auditing = false) }
                throw e
            } catch (e: ExternalAppsDisabled) {
                StewardLog.w("Termux audit refused: allow-external-apps is off")
                _state.update { it.copy(auditing = false, needsExternalApps = true, error = e.message) }
            } catch (e: Exception) {
                StewardLog.w("Termux audit failed after ${RunLog.seconds(SystemClock.uptimeMillis() - started)}", e)
                _state.update { it.copy(auditing = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun toggle(spec: String) = _state.update {
        it.copy(selected = if (spec in it.selected) it.selected - spec else it.selected + spec)
    }

    fun setSelected(specs: Collection<String>, selected: Boolean) = _state.update {
        it.copy(selected = if (selected) it.selected + specs else it.selected - specs.toSet())
    }

    /** Cleans [items] inside Termux and journals what was freed (Termux clean-ups are permanent). */
    suspend fun clean(items: List<TermuxItem>): Pair<String, TermuxCleanSummary> {
        val output = run("clean", items.map { it.spec }, CLEAN_TIMEOUT_MS)
        val summary = TermuxProtocol.parseClean(output)
        val runId = journal("Termux clean-up", summary)
        val done = items.map { it.spec }.toSet()
        _state.update { it.copy(report = it.report?.without(done), selected = it.selected - done) }
        return runId to summary
    }

    /** Records what a Termux run freed in History. Nothing here can be undone, so entries are informational. */
    private suspend fun journal(title: String, summary: TermuxCleanSummary): String {
        val runId = journals.newId()
        withContext(Dispatchers.IO) {
            JournalWriter(journals.fileFor(runId)).use { j ->
                j.meta("title", title)
                j.meta("kind", KIND)
                j.meta("started", System.currentTimeMillis().toString())
                summary.results.filter { it.freed > 0 }.forEach {
                    j.entry(JournalEntry(JournalAction.PURGED, it.path.ifEmpty { "package:${it.targetId}" }, "files=0", it.freed, -1, null))
                }
                j.meta("finished", System.currentTimeMillis().toString())
                j.meta("stats", "cleared=${summary.cleared} freed=${summary.freed} skipped=${summary.skipped.size}")
            }
        }
        return runId
    }

    // ------------------------------------------------------------------ packages

    fun loadPackages() {
        refresh()
        if (_state.value.packagesLoading || _state.value.status != TermuxStatus.READY) return
        scope.launch {
            _state.update { it.copy(packagesLoading = true, packagesError = null) }
            try {
                val output = run("packages", emptyList(), PACKAGES_TIMEOUT_MS)
                val list = TermuxProtocol.parsePackages(output)
                val programs = TermuxProtocol.parsePrograms(output)
                StewardLog.i(
                    "Termux packages listed: ${list.size}, ${list.sumOf { it.bytes }.humanBytes()}; programs ${programs.size}, " +
                        "${programs.sumOf { it.bytes }.humanBytes()}; ${list.count { it.lastUsed > 0 }} with a last use in shell history",
                )
                _state.update { s ->
                    s.copy(
                        packagesLoading = false,
                        packages = list,
                        packageSelected = s.packageSelected.filterTo(HashSet()) { n -> list.any { it.name == n } },
                        programs = programs,
                        programSelected = s.programSelected.filterTo(HashSet()) { k -> programs.any { it.key == k } },
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(packagesLoading = false) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("listing Termux packages failed", e)
                _state.update { it.copy(packagesLoading = false, packagesError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun togglePackage(name: String) = _state.update {
        it.copy(packageSelected = if (name in it.packageSelected) it.packageSelected - name else it.packageSelected + name)
    }

    fun clearPackageSelection() = _state.update { it.copy(packageSelected = emptySet()) }

    /** Asks apt, in a dry run, what removing [names] would take with it. The answer lands in [TermuxState.plan]. */
    fun planRemoval(names: List<String>) {
        if (_state.value.planning || names.isEmpty()) return
        scope.launch {
            _state.update { it.copy(planning = true, plan = null, packagesError = null) }
            try {
                val plan = TermuxProtocol.parsePlan(run("pkg-plan", names, PACKAGES_TIMEOUT_MS))
                _state.update { it.copy(planning = false, plan = plan) }
            } catch (e: CancellationException) {
                _state.update { it.copy(planning = false) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("planning a Termux package removal failed", e)
                _state.update { it.copy(planning = false, packagesError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun dismissPlan() = _state.update { it.copy(plan = null) }

    /** Uninstalls [names] with apt inside Termux (and what nothing needs any more, with [autoremove]). */
    suspend fun removePackages(names: List<String>, autoremove: Boolean): TermuxCleanSummary {
        val args = (if (autoremove) listOf("--autoremove") else emptyList()) + names
        val summary = TermuxProtocol.parseClean(run("pkg-remove", args, REMOVE_TIMEOUT_MS))
        journal("Termux packages removed", summary)
        val removed = summary.results.filter { it.status == "REMOVED" }.map { it.targetId }.toSet()
        _state.update { s ->
            s.copy(plan = null, packages = s.packages?.filterNot { it.name in removed }, packageSelected = s.packageSelected - removed)
        }
        return summary
    }

    fun toggleProgram(key: String) = _state.update {
        it.copy(programSelected = if (key in it.programSelected) it.programSelected - key else it.programSelected + key)
    }

    fun clearProgramSelection() = _state.update { it.copy(programSelected = emptySet()) }

    /** Uninstalls programs with the package manager that installed each (npm rm -g, pip uninstall, cargo uninstall). */
    suspend fun removePrograms(programs: List<TermuxProgram>): TermuxCleanSummary {
        val results = programs.groupBy { it.manager }.flatMap { (manager, list) ->
            TermuxProtocol.parseClean(run("prog-remove", listOf(manager) + list.map { it.name }, REMOVE_TIMEOUT_MS)).results
                .map { r -> r.copy(targetId = "$manager:${r.targetId}") }
        }
        val summary = TermuxCleanSummary(results)
        journal("Termux programs removed", summary)
        val removed = results.filter { it.status == "REMOVED" }.map { it.targetId }.toSet()
        _state.update { s -> s.copy(programs = s.programs?.filterNot { it.key in removed }, programSelected = s.programSelected - removed) }
        return summary
    }

    // ------------------------------------------------------------------ Git repositories

    fun loadRepos() {
        refresh()
        if (_state.value.reposLoading || _state.value.status != TermuxStatus.READY) return
        scope.launch {
            _state.update { it.copy(reposLoading = true, reposError = null) }
            val started = SystemClock.uptimeMillis()
            try {
                val repos = TermuxProtocol.parseRepos(run("repos", emptyList(), REPOS_TIMEOUT_MS))
                StewardLog.i(
                    "Git repositories listed in ${RunLog.seconds(SystemClock.uptimeMillis() - started)}: ${repos.size}, " +
                        "${repos.count { it.packable }} worth packing (${repos.sumOf { it.looseBytes + it.garbageBytes }.humanBytes()} loose), " +
                        "${repos.count { it.onlyACopy }} fully pushed",
                )
                _state.update { s -> s.copy(reposLoading = false, repos = repos, repoSelected = s.repoSelected.filterTo(HashSet()) { p -> repos.any { it.path == p } }) }
            } catch (e: CancellationException) {
                _state.update { it.copy(reposLoading = false) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("listing Git repositories failed", e)
                _state.update { it.copy(reposLoading = false, reposError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun toggleRepo(path: String) = _state.update {
        it.copy(repoSelected = if (path in it.repoSelected) it.repoSelected - path else it.repoSelected + path)
    }

    fun clearRepoSelection() = _state.update { it.copy(repoSelected = emptySet()) }

    /** git gc in each repository: loose objects packed, nothing lost; they work exactly as before. */
    suspend fun packRepos(paths: List<String>): TermuxCleanSummary {
        val summary = TermuxProtocol.parseClean(run("git-gc", paths, REMOVE_TIMEOUT_MS))
        journal("Git repositories packed", summary)
        val after = summary.results.filter { it.status == "CLEARED" }.associate { it.path to it.after }
        _state.update { s ->
            s.copy(
                repos = s.repos?.map { r -> after[r.path]?.let { r.copy(gitBytes = it, looseBytes = 0, garbageBytes = 0) } ?: r },
                repoSelected = s.repoSelected - paths.toSet(),
            )
        }
        return summary
    }

    // ------------------------------------------------------------------ projects out of shared storage

    /**
     * Moves [folder] from shared storage into ~/projects, only after Termux checked the copy byte for byte. History can
     * move it back. Returns the summary and the new path, when it moved.
     */
    suspend fun moveIntoTermux(folder: String): TermuxCleanSummary {
        val summary = TermuxProtocol.parseClean(run("relocate", listOf(folder), RELOCATE_TIMEOUT_MS))
        _state.update { s ->
            val moved = summary.results.filter { it.status == "MOVED" }.map { it.path }.toSet()
            s.copy(repos = s.repos?.filterNot { r -> moved.any { r.path == it || r.path.startsWith("$it/") } }, repoSelected = s.repoSelected - moved)
        }
        summary.results.filter { it.status == "MOVED" }.forEach { r ->
            val runId = journals.newId()
            withContext(Dispatchers.IO) {
                JournalWriter(journals.fileFor(runId)).use { j ->
                    j.meta("title", "Moved into Termux: ${r.path.substringAfterLast('/')}")
                    j.meta("kind", KIND_MOVE)
                    j.meta("started", System.currentTimeMillis().toString())
                    j.entry(JournalEntry(JournalAction.RELOCATED, r.path, r.note, r.before, -1, null))
                    j.meta("finished", System.currentTimeMillis().toString())
                    j.meta("stats", "moved=1 movedBytes=${r.before}")
                }
            }
        }
        return summary
    }

    /** Undoes [moveIntoTermux] for one History entry: the folder goes back to where it was, checked the same way. */
    suspend fun moveBack(runId: String): TermuxCleanSummary {
        val entry = journals.entries(runId).firstOrNull { it.action == JournalAction.RELOCATED }
            ?: throw TermuxException("This entry has nothing to move back")
        val summary = TermuxProtocol.parseClean(run("relocate-back", listOf(entry.b, entry.a), RELOCATE_TIMEOUT_MS))
        if (summary.results.any { it.status == "MOVED" }) {
            withContext(Dispatchers.IO) { journals.appendMeta(runId, "rolledback", System.currentTimeMillis().toString()) }
        }
        return summary
    }

    // ------------------------------------------------------------------ distributions and the browser

    /** Removes one proot distribution (not while one is running). */
    suspend fun removeDistro(rootfs: String): TermuxCleanSummary {
        val summary = TermuxProtocol.parseClean(run("distro-remove", listOf(rootfs), REMOVE_TIMEOUT_MS))
        journal("Linux distribution removed", summary)
        forget(summary)
        return summary
    }

    /** Deletes files and folders picked in the Termux browser; the script refuses anything unsafe, one by one. */
    suspend fun deletePaths(paths: List<String>): TermuxCleanSummary {
        val summary = TermuxProtocol.parseClean(run("delete", paths, REMOVE_TIMEOUT_MS))
        journal("Deleted in Termux", summary)
        forget(summary)
        _state.update { it.copy(browseSelected = it.browseSelected - paths.toSet()) }
        return summary
    }

    /** Drops what is gone from the size map, so the browser shows the space as free without a new audit. */
    private fun forget(summary: TermuxCleanSummary) {
        val gone = summary.results.filter { (it.status == "CLEARED" || it.status == "MOVED") && it.path.isNotEmpty() }.associate { it.path to it.before }
        _state.update { s ->
            s.copy(
                report = s.report?.afterDeleting(gone),
                // A deleted or moved repository is gone from the list too.
                repos = s.repos?.filterNot { r -> gone.keys.any { r.path == it || r.path.startsWith("$it/") } },
                repoSelected = s.repoSelected.filterTo(HashSet()) { p -> gone.keys.none { p == it || p.startsWith("$it/") } },
            )
        }
    }

    /** Shows [report] as if Termux had just been scanned: the end-to-end test has no Termux to ask. */
    @VisibleForTesting
    internal fun showReport(report: TermuxReport) = _state.update { it.copy(report = report) }

    @VisibleForTesting
    internal fun showPackages(packages: List<TermuxPackage>, programs: List<TermuxProgram>) =
        _state.update { it.copy(packages = packages, programs = programs) }

    @VisibleForTesting
    internal fun showRepos(repos: List<TermuxRepo>) = _state.update { it.copy(repos = repos) }

    fun toggleBrowse(path: String) = _state.update {
        it.copy(browseSelected = if (path in it.browseSelected) it.browseSelected - path else it.browseSelected + path)
    }

    fun clearBrowseSelection() = _state.update { it.copy(browseSelected = emptySet()) }

    private class ExternalAppsDisabled : Exception(
        "Termux does not accept commands from other apps yet. Paste the command below into Termux once, then try again.",
    )

    /**
     * Runs the helper script and returns its records. The full report is written to shared storage when Termux
     * has storage access (no size limit); otherwise it comes back through the result bundle.
     */
    private suspend fun run(mode: String, targets: List<String>, timeoutMs: Long): String {
        val outFile = withContext(Dispatchers.IO) { outputFile() }
        val result = try {
            bridge.run(TermuxScript.arguments(mode, outFile?.path, targets), timeoutMs)
        } catch (_: TimeoutCancellationException) {
            throw TermuxException("Termux did not answer in time. Open Termux once and try again.")
        }
        if (result.needsExternalApps) throw ExternalAppsDisabled()
        if (result.err != 0 && result.err != -1 && result.stdout.isEmpty()) {
            throw TermuxException(result.errmsg.ifEmpty { "Termux reported error ${result.err}" })
        }
        return withContext(Dispatchers.IO) {
            val fromFile = outFile?.takeIf { it.isFile }?.let { f -> f.readText().also { f.delete() } }
            if (fromFile != null && fromFile.lineSequence().any { it.startsWith("E\t") }) fromFile else result.stdout
        }
    }

    private fun outputFile(): File? {
        val dir = File(StorageAccess.rootPath, "${SafetyPolicy.STEWARD_DIR}/termux")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        runCatching { File(dir.parentFile, ".nomedia").takeIf { !it.exists() }?.createNewFile() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        return File(dir, "run-${System.currentTimeMillis()}.tsv")
    }

    companion object {
        const val KIND = "termux"

        /** History entries for projects moved from shared storage into Termux; undo moves them back. */
        const val KIND_MOVE = "termux-move"
        private const val REPOS_TIMEOUT_MS = 5 * 60_000L
        private const val RELOCATE_TIMEOUT_MS = 60 * 60_000L
        private const val AUDIT_TIMEOUT_MS = 10 * 60_000L
        private const val CLEAN_TIMEOUT_MS = 20 * 60_000L
        private const val PACKAGES_TIMEOUT_MS = 3 * 60_000L
        private const val REMOVE_TIMEOUT_MS = 30 * 60_000L

        /**
         * Why things stayed, counted: "installed by a package 40, holds a key 3". Goes into the run's log line too, so a
         * run that left everything alone says why without the details on screen.
         */
        fun reasonTally(summary: TermuxCleanSummary): String? = summary.results.filter { !it.ok && it.status != "REMOVED" }
            .groupingBy { r ->
                when (r.status) {
                    "SKIP_PACKAGE" -> "installed by a package"
                    "SKIP_PROTECTED" -> "Termux needs it"
                    "SKIP_KEYS" -> "holds a key"
                    "SKIP_ACTIVE" -> "in use"
                    "SKIP_EXISTS" -> "already there"
                    "SKIP_SPACE" -> "not enough space"
                    "PARTIAL" -> "partly removed"
                    "FAILED" -> "failed"
                    else -> r.note.ifEmpty { r.status.lowercase().replace('_', ' ') }.substringBefore(';').take(60)
                }
            }.eachCount().entries.sortedByDescending { it.value }.take(4)
            .takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "why: ") { "${it.key} ${it.value}" }

        /** One line per result that stayed, with why: "lib/chromium: owned by chromium - uninstall it under Packages". */
        fun skippedNotes(summary: TermuxCleanSummary, home: String, prefix: String): List<String> =
            summary.results.filter { !it.ok && it.status != "REMOVED" }.map { r ->
                val what = r.path.takeIf { it.isNotEmpty() }?.let { TermuxProtocol.relative(it, home, prefix) } ?: r.targetId
                val why = when (r.status) {
                    "SKIP_PACKAGE" -> "installed by ${r.note}: uninstall it under Packages"
                    "SKIP_PROTECTED" -> "Termux needs this package"
                    "SKIP_KEYS" -> if (r.note.startsWith("holds ")) "${r.note}, a key: move it out first" else "a key or credential"
                    "SKIP_ACTIVE" -> if (r.targetId == "gc") r.note else "a proot distribution is running"
                    "SKIP_EXISTS" -> r.note
                    "SKIP_SPACE" -> "not enough free space for a checked copy"
                    else -> r.note.ifEmpty { r.status.lowercase().replace('_', ' ') }
                }
                "$what: $why"
            }
    }
}
