package com.galaxy.steward.core.appdata

import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalSink
import com.galaxy.steward.core.exec.RollbackEngine
import com.galaxy.steward.core.exec.RollbackSummary

/**
 * Both ends of the app-data protocol. [Helper] runs where Android/data is reachable (the Shizuku service, or
 * in-process in tests) and writes lines; [Client] runs in the app and turns those lines back into results.
 */
object AppDataHelper {
    object Helper {
        fun scan(request: String, out: (String) -> Unit) {
            try {
                val r = AppDataWire.decodeScanRequest(request)
                var seen = 0
                val report = AppDataScanner(r.rootPath, r.installed, r.options, r.ownPackage).scan(r.areas) { pkg ->
                    seen++
                    if (seen % 5 == 0) out(AppDataWire.progress(seen, 0, pkg))
                }
                AppDataWire.encodeReport(report).forEach(out)
            } catch (e: Exception) {
                out(AppDataWire.error(e.message ?: e.javaClass.simpleName))
            }
            out(AppDataWire.END)
        }

        fun list(request: String, out: (String) -> Unit) {
            try {
                val r = AppDataWire.decodeListRequest(request)
                AppDataWire.encodeListing(AppDataBrowser(r.rootPath, r.ownPackage).list(r.path)).forEach(out)
            } catch (e: Exception) {
                out(AppDataWire.error(e.message ?: e.javaClass.simpleName))
            }
            out(AppDataWire.END)
        }

        suspend fun apply(request: String, out: (String) -> Unit) {
            try {
                val r = AppDataWire.decodeApplyRequest(request)
                val sink = object : JournalSink {
                    override fun meta(key: String, value: String) = out(AppDataWire.journalMeta(key, value))
                    override fun entry(entry: JournalEntry) = out(AppDataWire.journalEntry(entry))
                }
                val summary = AppDataExecutor(r.rootPath, sink, r.runId, r.ownPackage, r.installedNow)
                    .execute(r.title, r.items) { done, total, current -> out(AppDataWire.progress(done, total, current)) }
                summary.messages.forEach { out(AppDataWire.message(it)) }
                summary.changedPaths.forEach { out(AppDataWire.changedPath(it)) }
                out(AppDataWire.encodeSummary(summary))
            } catch (e: Exception) {
                out(AppDataWire.error(e.message ?: e.javaClass.simpleName))
            }
            out(AppDataWire.END)
        }

        suspend fun rollback(rootPath: String, entries: String, out: (String) -> Unit) {
            try {
                val summary = RollbackEngine(rootPath, null).rollbackEntries(AppDataWire.decodeEntries(entries)) { done, total, current ->
                    out(AppDataWire.progress(done, total, current))
                }
                summary.messages.forEach { out(AppDataWire.message(it)) }
                summary.changedPaths.forEach { out(AppDataWire.changedPath(it)) }
                out(AppDataWire.encodeRollback(summary))
            } catch (e: Exception) {
                out(AppDataWire.error(e.message ?: e.javaClass.simpleName))
            }
            out(AppDataWire.END)
        }
    }

    class HelperException(message: String) : Exception(message)

    object Client {
        fun readScan(lines: Sequence<String>, onProgress: (String) -> Unit = {}): AppDataReport {
            var error: String? = null
            var ended = false
            val report = AppDataWire.decodeReport(lines) { p ->
                when (p[0]) {
                    "P" -> onProgress(p.getOrElse(3) { "" })
                    "E" -> error = p.getOrElse(1) { "Helper error" }
                    AppDataWire.END -> ended = true
                }
            }
            error?.let { throw HelperException(it) }
            if (!ended) throw HelperException("The helper stopped before finishing the scan")
            return report
        }

        fun readList(lines: Sequence<String>): AppFolderListing {
            var error: String? = null
            var ended = false
            val listing = AppDataWire.decodeListing(lines) { p ->
                when (p[0]) {
                    "E" -> error = p.getOrElse(1) { "Helper error" }
                    AppDataWire.END -> ended = true
                }
            }
            error?.let { throw HelperException(it) }
            if (!ended || listing == null) throw HelperException("The helper stopped before finishing the listing")
            return listing
        }

        /** Replays journal lines into [journal] as they arrive, so an interrupted run is still recorded truthfully. */
        fun readApply(lines: Sequence<String>, journal: JournalSink, onProgress: (Int, Int, String) -> Unit): ExecutionSummary {
            val messages = ArrayList<String>()
            val changed = ArrayList<String>()
            var summary: List<String>? = null
            var error: String? = null
            for (line in lines) {
                val p = line.split('\t')
                when (p[0]) {
                    "P" -> if (p.size >= 4) onProgress(p[1].toInt(), p[2].toInt(), p[3])
                    "M" -> if (p.size >= 3) journal.meta(p[1], p[2])
                    "J" -> JournalEntry.decode(line.substringAfter('\t'))?.let(journal::entry)
                    "N" -> messages += p.getOrElse(1) { "" }
                    "C" -> changed += line.substringAfter('\t')
                    "S" -> summary = p
                    "E" -> error = p.getOrElse(1) { "Helper error" }
                    AppDataWire.END -> break
                }
            }
            error?.let { throw HelperException(it) }
            return AppDataWire.decodeSummary(summary ?: throw HelperException("The helper stopped before finishing"), messages, changed)
        }

        fun readRollback(lines: Sequence<String>, onProgress: (Int, Int, String) -> Unit): RollbackSummary {
            val messages = ArrayList<String>()
            val changed = ArrayList<String>()
            var result: List<String>? = null
            var error: String? = null
            for (line in lines) {
                val p = line.split('\t')
                when (p[0]) {
                    "P" -> if (p.size >= 4) onProgress(p[1].toInt(), p[2].toInt(), p[3])
                    "N" -> messages += p.getOrElse(1) { "" }
                    "C" -> changed += line.substringAfter('\t')
                    "R" -> result = p
                    "E" -> error = p.getOrElse(1) { "Helper error" }
                    AppDataWire.END -> break
                }
            }
            error?.let { throw HelperException(it) }
            val r = result ?: throw HelperException("The helper stopped before finishing")
            return RollbackSummary(r[1].toInt(), r[2].toInt(), r[3].toInt(), messages, changed)
        }
    }
}
