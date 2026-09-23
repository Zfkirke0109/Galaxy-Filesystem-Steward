package com.galaxy.steward.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.content.FileProvider
import com.galaxy.steward.BuildConfig
import com.galaxy.steward.apps.AppStorage
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.data.StorageAccess
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A saved log. [wholeDevice] is false when only the app's own lines could be read. */
data class LogcatFile(val file: File, val bytes: Long, val wholeDevice: Boolean, val note: String?)

data class LogcatExportState(
    val running: Boolean = false,
    val bytesWritten: Long = 0,
    val saved: LogcatFile? = null,
    val error: String? = null,
)

/**
 * Saves the device log to Documents/Galaxy Steward LogCat, so a problem can be reported with one tap.
 *
 * A normal app can only read its own log lines. With Shizuku connected, the helper (which runs as Android's shell
 * user) dumps the whole device log instead, as `adb logcat -d` would. The log goes straight into the file, because a
 * busy phone's buffers hold tens of megabytes.
 */
class LogcatExporter(
    private val context: Context,
    private val shizuku: ShizukuBridge,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LogcatExportState())
    val state: StateFlow<LogcatExportState> = _state.asStateFlow()

    private class Source(val stream: InputStream, val wholeDevice: Boolean, val contents: String, val note: String?)

    fun export() {
        if (_state.value.running) return
        _state.value = LogcatExportState(running = true)
        scope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { write() }
            } catch (e: CancellationException) {
                _state.value = LogcatExportState()
                throw e
            } catch (e: Exception) {
                _state.value = LogcatExportState(error = "Couldn't save the log: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            _state.value = LogcatExportState(saved = saved)
            // So the file also shows up over USB and in apps that browse the media index.
            StorageAccess.rescan(context, listOf(saved.file.path))
        }
    }

    /** A share sheet for [file], through this app's FileProvider (which only serves the logcat folder). */
    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Share logcat")
    }

    private suspend fun write(): LogcatFile {
        val folder = File(StorageAccess.rootPath, SafetyPolicy.LOGCAT_DIR)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("can't create ${SafetyPolicy.LOGCAT_DIR}")
        val now = ZonedDateTime.now()
        val file = File(folder, "logcat-${now.format(FILE_TIME)}.txt")
        val source = open()
        try {
            // The source is closed whatever happens, so logcat or the helper never blocks on a pipe nobody reads.
            source.stream.use { input ->
                file.outputStream().buffered(BUFFER_BYTES).use { out ->
                    out.write(header(now, source).toByteArray())
                    copy(input, out)
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return LogcatFile(file, file.length(), source.wholeDevice, source.note)
    }

    private suspend fun open(): Source {
        val ready = withContext(Dispatchers.Main.immediate) {
            shizuku.refresh()
            shizuku.ready
        }
        var note = "Connect Shizuku on the Apps tab to include the whole device log."
        if (ready) {
            try {
                // Null when a helper from an older version is still running: it doesn't know this call yet.
                val fd = shizuku.helper().dumpLogcat() ?: throw IllegalStateException("the Shizuku helper is out of date; restart Shizuku")
                return Source(ParcelFileDescriptor.AutoCloseInputStream(fd), true, "the whole device log, read through Shizuku", null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "Shizuku couldn't read the device log (${e.message ?: e.javaClass.simpleName})."
            }
        }
        val stream = try {
            ProcessBuilder(LogcatDump.COMMAND).redirectErrorStream(true).start().inputStream
        } catch (e: IOException) {
            // Every Android device has logcat. The header is still worth saving if it can't start.
            "--------- logcat could not be started: ${e.message}\n".byteInputStream()
        }
        return Source(stream, false, "Galaxy Steward's own log lines only", note)
    }

    private fun header(now: ZonedDateTime, source: Source): String = buildString {
        appendLine("Galaxy Steward ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) logcat export")
        appendLine("Saved:    ${now.format(HEADER_TIME)}")
        appendLine("Device:   ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        appendLine("Contents: ${source.contents}; buffers ${LogcatDump.BUFFERS}")
        source.note?.let { appendLine("Note:     $it") }
        appendLine("App:      ${context.packageName}, pid ${Process.myPid()}, uid ${Process.myUid()}")
        appendLine(
            "Access:   all files ${yesNo(StorageAccess.hasAccess(context))}, usage access ${yesNo(AppStorage.hasUsageAccess(context))}, " +
                "Shizuku: ${shizuku.status.value.label}",
        )
        appendLine()
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun copy(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var reported = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            if (total - reported >= PROGRESS_STEP_BYTES) {
                reported = total
                _state.update { it.copy(bytesWritten = total) }
            }
        }
    }

    companion object {
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
        private const val BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 1L shl 20
    }
}
