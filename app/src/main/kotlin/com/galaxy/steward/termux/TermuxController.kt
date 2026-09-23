package com.galaxy.steward.termux

import android.content.Context
import android.os.SystemClock
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
import com.galaxy.steward.core.termux.TermuxProtocol
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
                StewardLog.i(
                    "Termux audit done in ${RunLog.seconds(SystemClock.uptimeMillis() - started)}: Termux uses ${report.totalBytes.humanBytes()}, " +
                        "${report.items.size} cleanable items (${report.items.sumOf { it.bytes }.humanBytes()}), ${report.unsafe.size} unsafe paths skipped",
                )
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
        val runId = journals.newId()
        withContext(Dispatchers.IO) {
            JournalWriter(journals.fileFor(runId)).use { j ->
                j.meta("title", "Termux clean-up")
                j.meta("kind", KIND)
                j.meta("started", System.currentTimeMillis().toString())
                summary.results.filter { it.freed > 0 }.forEach {
                    j.entry(JournalEntry(JournalAction.PURGED, it.path, "files=0", it.freed, -1, null))
                }
                j.meta("finished", System.currentTimeMillis().toString())
                j.meta("stats", "cleared=${summary.cleared} freed=${summary.freed} skipped=${summary.skipped.size}")
            }
        }
        val done = items.map { it.spec }.toSet()
        _state.update { it.copy(report = it.report?.without(done), selected = it.selected - done) }
        return runId to summary
    }

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
        private const val AUDIT_TIMEOUT_MS = 10 * 60_000L
        private const val CLEAN_TIMEOUT_MS = 20 * 60_000L
    }
}
