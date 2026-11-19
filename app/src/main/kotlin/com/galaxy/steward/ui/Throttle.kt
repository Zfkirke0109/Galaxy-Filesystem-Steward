package com.galaxy.steward.ui

/**
 * [report] at most every [gapMs] for the steps of a run in between, never for its first or last step or for a new phase
 * (another total): a big undo or app-folder run redrew the progress dialog and the notification for every file, and the
 * first update of a new phase ("Waiting for Termux", right after the last step of the one before) used to be dropped,
 * leaving the finished phase on screen for as long as the wait lasted.
 */
fun throttle(clock: () -> Long, gapMs: Long = 80, report: (Int, Int, String) -> Unit): (Int, Int, String) -> Unit {
    var last = Long.MIN_VALUE
    var lastTotal = -1
    return { done, total, current ->
        val now = clock()
        if (last == Long.MIN_VALUE || now - last >= gapMs || done >= total || done == 0 || total != lastTotal) {
            last = now
            lastTotal = total
            report(done, total, current)
        }
    }
}
