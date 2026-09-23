package com.galaxy.steward.diagnostics

/** The one log command the app runs, either in its own process or in the Shizuku helper. */
object LogcatDump {
    /**
     * Dumps the log buffers once and exits, in logcat's standard `threadtime` format. The radio buffer is left out: it
     * can hold phone numbers and cell locations, and says nothing about storage.
     */
    val COMMAND = listOf("/system/bin/logcat", "-d", "-v", "threadtime", "-b", "main,system,crash,events")

    const val BUFFERS = "main, system, crash and events"
}
