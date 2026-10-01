package com.galaxy.steward.diagnostics

import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The steward's own lines in the device log, under one tag (`logcat -s GalaxySteward`): one line per scan, run,
 * undo and helper call, with counts and durations (see core's RunLog). File names are never logged.
 *
 * The same lines are also kept in the app's private folder ([history]): Android's log is a ring buffer that busy
 * phones overwrite within the hour (a real export had lost the Termux scan made twenty minutes earlier), so the
 * logcat export starts with this history.
 */
object StewardLog {
    const val TAG = "GalaxySteward"
    private const val KEEP_LINES = 1_000
    private const val TRIM_AT_BYTES = 256L * 1024

    @Volatile
    private var history: File? = null

    fun init(file: File) {
        history = file
    }

    fun i(message: String) {
        Log.i(TAG, message)
        remember("I", message)
    }

    fun w(message: String, error: Throwable? = null) {
        val line = if (error == null) message else "$message: ${error.message ?: error.javaClass.simpleName}"
        Log.w(TAG, line)
        remember("W", line)
    }

    /** The kept lines, oldest first (at most [KEEP_LINES]). */
    fun lines(): List<String> = synchronized(this) {
        try {
            history?.takeIf { it.isFile }?.readLines().orEmpty()
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun remember(level: String, message: String) {
        val file = history ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        synchronized(this) {
            try {
                file.appendText("$stamp $level ${message.replace('\n', ' ')}\n")
                if (file.length() > TRIM_AT_BYTES) file.writeText(file.readLines().takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
            } catch (_: IOException) {
                // The device log still has the line; a full disk must not break the run it describes.
            }
        }
    }
}
