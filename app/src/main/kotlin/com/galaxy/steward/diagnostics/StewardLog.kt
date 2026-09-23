package com.galaxy.steward.diagnostics

import android.util.Log

/**
 * The steward's own lines in the device log, under one tag (`logcat -s GalaxySteward`): one line per scan, run,
 * undo and helper call, with counts and durations (see core's RunLog). File names are never logged.
 */
object StewardLog {
    const val TAG = "GalaxySteward"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, if (error == null) message else "$message: ${error.message ?: error.javaClass.simpleName}")
    }
}
