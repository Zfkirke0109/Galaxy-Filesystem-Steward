package com.galaxy.steward.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.os.StatFs
import com.galaxy.steward.core.device.DiskStats
import com.galaxy.steward.core.device.PrivateData
import com.galaxy.steward.core.device.PrivateEntry
import com.galaxy.steward.core.device.ShellEntry
import com.galaxy.steward.core.device.ShellRemoval
import com.galaxy.steward.core.device.ShellSpace
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.diagnostics.StewardLog
import com.galaxy.steward.shizuku.ShizukuBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** An app built debuggable (your own debug builds): Android lets the shell user into its private data with run-as. */
data class DebugApp(val packageName: String, val label: String)

data class DeepSpaceState(
    val loading: Boolean = false,
    val error: String? = null,
    val disk: DiskStats? = null,
    val shell: List<ShellEntry> = emptyList(),
    val shellUnreadable: List<String> = emptyList(),
    val shellSelected: Set<String> = emptySet(),
    val debugApps: List<DebugApp> = emptyList(),
    /** The debuggable app whose private data is open, its entries once listed, and what is picked in it. */
    val privateFor: DebugApp? = null,
    val privateEntries: List<PrivateEntry>? = null,
    val privateLoading: Boolean = false,
    val privateError: String? = null,
    val privateSelected: Set<String> = emptySet(),
)

/**
 * What only Android's shell user sees, through the Shizuku helper: Android's own storage breakdown (with the "System"
 * and "Other" no scan can open), its own clean-ups, what adb left in /data/local/tmp, bug reports, and the private data
 * of debuggable apps. Each is a fixed helper call; nothing here runs a command of its own choosing.
 */
class DeepSpaceController(private val context: Context, private val shizuku: ShizukuBridge, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DeepSpaceState())
    val state: StateFlow<DeepSpaceState> = _state.asStateFlow()

    fun load() {
        if (_state.value.loading) return
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // The package manager lists every app: never on the main thread.
            val debug = withContext(Dispatchers.IO) { debugApps() }
            _state.update { it.copy(debugApps = debug) }
            try {
                val helper = shizuku.helper()
                val disk = withContext(Dispatchers.IO) { helper.diskStats() }
                    ?.let { fd -> shizuku.readLines(fd) { lines -> DiskStats.parse(lines.joinToString("\n")) } }
                val fd = withContext(Dispatchers.IO) { shizuku.pipeOf("list").use { helper.shellSpace(it) } }
                    ?: throw IOException("The Shizuku helper is out of date. Stop and restart Shizuku, then try again.")
                val (entries, unreadable) = shizuku.readLines(fd) { ShellSpace.readList(it) }
                StewardLog.i(
                    "deep space: ${disk?.let { "Android counts ${it.used.humanBytes()} used, other ${(it.parts["Other"] ?: 0).humanBytes()}" } ?: "no diskstats"}; " +
                        "shell places ${entries.size} (${entries.sumOf { it.bytes }.humanBytes()}), unreadable ${unreadable.size}; debuggable apps ${_state.value.debugApps.size}",
                )
                _state.update { s ->
                    s.copy(
                        loading = false,
                        disk = disk,
                        shell = entries,
                        shellUnreadable = unreadable,
                        shellSelected = s.shellSelected.filterTo(HashSet()) { p -> entries.any { it.path == p && it.lock == null } },
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                StewardLog.w("deep space failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun debugApps(): List<DebugApp> = try {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .map { DebugApp(it.packageName, context.packageManager.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    } catch (_: RuntimeException) {
        emptyList()
    }

    fun toggleShell(path: String) = _state.update { it.copy(shellSelected = if (path in it.shellSelected) it.shellSelected - path else it.shellSelected + path) }

    /** Removes the picked shell-place entries; the helper checks each again. */
    suspend fun removeShell(paths: List<String>): List<ShellRemoval> {
        val helper = shizuku.helper()
        val fd = withContext(Dispatchers.IO) { shizuku.pipeOf((listOf("remove") + paths).joinToString("\n")).use { helper.shellSpace(it) } }
            ?: throw IOException("The Shizuku helper is out of date. Stop and restart Shizuku, then try again.")
        val results = shizuku.readLines(fd) { ShellSpace.readRemoval(it) }
        val gone = results.filter { it.removed }.map { it.path }.toSet()
        _state.update { s -> s.copy(shell = s.shell.filterNot { it.path in gone }, shellSelected = s.shellSelected - paths.toSet()) }
        return results
    }

    /** Free bytes on the data partition, where apps and their data live. */
    private fun freeBytes(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(0L)

    /**
     * One of Android's own clean-ups ("trim-caches" or "art-cleanup"). What it freed is measured as free space before
     * and after, so the number is what really changed. Returns (freed bytes, what the command said).
     */
    suspend fun systemClean(what: String): Pair<Long, String> {
        val helper = shizuku.helper()
        val before = freeBytes()
        val answer = withContext(Dispatchers.IO) { helper.systemClean(what, 300_000) } ?: "error=helper out of date"
        // Android deletes some of it in the background right after answering.
        kotlinx.coroutines.delay(2_000)
        val freed = (freeBytes() - before).coerceAtLeast(0)
        StewardLog.i("system clean $what: ${answer.lineSequence().first()}, free space +${freed.humanBytes()}")
        return freed to answer
    }

    // ------------------------------------------------------------------ private data of a debuggable app

    fun openPrivate(app: DebugApp) {
        _state.update { it.copy(privateFor = app, privateEntries = null, privateError = null, privateSelected = emptySet(), privateLoading = true) }
        scope.launch {
            try {
                val entries = listPrivate(app.packageName)
                _state.update { s -> if (s.privateFor != app) s else s.copy(privateEntries = entries, privateLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> if (s.privateFor != app) s else s.copy(privateLoading = false, privateError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun listPrivate(pkg: String): List<PrivateEntry> {
        val helper = shizuku.helper()
        val fd = withContext(Dispatchers.IO) { shizuku.pipeOf("list").use { helper.privateData(pkg, it) } }
            ?: throw IOException("The Shizuku helper is out of date. Stop and restart Shizuku, then try again.")
        return shizuku.readLines(fd) { lines -> readPrivate(pkg, lines) }
    }

    fun togglePrivate(rel: String) = _state.update { it.copy(privateSelected = if (rel in it.privateSelected) it.privateSelected - rel else it.privateSelected + rel) }

    /** Stops the app and removes the picked paths of its private data; returns the helper's results and lists it again. */
    suspend fun removePrivate(pkg: String, rels: List<String>): List<ShellRemoval> {
        val helper = shizuku.helper()
        val fd = withContext(Dispatchers.IO) { shizuku.pipeOf((listOf("remove") + rels).joinToString("\n")).use { helper.privateData(pkg, it) } }
            ?: throw IOException("The Shizuku helper is out of date. Stop and restart Shizuku, then try again.")
        val results = shizuku.readLines(fd) { ShellSpace.readRemoval(it) }
        val entries = runCatching { listPrivate(pkg) }.getOrNull()
        _state.update { s -> if (s.privateFor?.packageName != pkg) s else s.copy(privateEntries = entries ?: s.privateEntries, privateSelected = emptySet()) }
        return results
    }

    private fun readPrivate(pkg: String, lines: Sequence<String>): List<PrivateEntry> {
        val out = ArrayList<PrivateEntry>()
        var ended = false
        for (line in lines) {
            if (line == ShellSpace.END) {
                ended = true
                break
            }
            val p = line.split('\t')
            when (p[0]) {
                "E" -> throw IOException(p.getOrElse(1) { "run-as failed" })
                "S" -> if (p.size >= 3) out += PrivateEntry(p[2], p[1].toLongOrNull() ?: 0, PrivateData.label(pkg, p[2]))
            }
        }
        if (!ended) throw IOException("The helper stopped before finishing")
        return out
    }
}
