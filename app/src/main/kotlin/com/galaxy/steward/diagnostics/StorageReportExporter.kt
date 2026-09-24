package com.galaxy.steward.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.galaxy.steward.BuildConfig
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.report.StorageReportText
import com.galaxy.steward.core.termux.TermuxReport
import com.galaxy.steward.data.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class StorageReportState(val running: Boolean = false, val saved: File? = null, val error: String? = null)

/**
 * Saves a plain-text map of the last scan and Termux audit ([StorageReportText]) next to the logcat exports, so how
 * storage is laid out, and why the steward leaves each folder where it is, can be shared with one tap.
 */
class StorageReportExporter(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(StorageReportState())
    val state: StateFlow<StorageReportState> = _state.asStateFlow()

    fun export(scan: ScanReport?, termux: TermuxReport?) {
        if (_state.value.running) return
        _state.value = StorageReportState(running = true)
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { write(scan, termux) }
            } catch (e: CancellationException) {
                _state.value = StorageReportState()
                throw e
            } catch (e: Exception) {
                StewardLog.w("storage report export failed", e)
                _state.value = StorageReportState(error = "Couldn't save the report: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            StewardLog.i("storage report saved: ${file.length().humanBytes()}")
            _state.value = StorageReportState(saved = file)
            StorageAccess.rescan(context, listOf(file.path))
        }
    }

    fun shareIntent(file: File): Intent = shareTextFile(context, file, "Share storage report")

    private fun write(scan: ScanReport?, termux: TermuxReport?): File {
        val folder = File(StorageAccess.rootPath, SafetyPolicy.LOGCAT_DIR)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("can't create ${SafetyPolicy.LOGCAT_DIR}")
        val now = ZonedDateTime.now()
        val file = File(folder, "storage-${now.format(FILE_TIME)}.txt")
        val header = listOfNotNull(
            "Galaxy Steward ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) storage report",
            "Saved:    ${now.format(HEADER_TIME)}",
            "Device:   ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            scan?.let { "Scanned:  ${Instant.ofEpochMilli(it.finishedAt).atZone(ZoneId.systemDefault()).format(HEADER_TIME)}" },
            "Contents: folder names and sizes; no file contents. Share it only with people you trust.",
        )
        file.writeText(StorageReportText.render(scan, termux, header))
        return file
    }

    private companion object {
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val HEADER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
    }
}

/** A share sheet for a text file in the logcat folder, through this app's FileProvider (which serves only that folder). */
fun shareTextFile(context: Context, file: File, title: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    return Intent.createChooser(send, title)
}
