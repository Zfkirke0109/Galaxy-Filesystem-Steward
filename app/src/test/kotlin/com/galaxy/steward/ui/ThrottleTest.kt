package com.galaxy.steward.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottleTest {
    @Test
    fun stepsInBetweenAreThinnedButNoPhaseStartOrEndIsDropped() {
        var now = 1_000L
        val shown = ArrayList<String>()
        val progress = throttle({ now }) { done, total, current -> shown += "$done/$total $current" }

        progress(0, 120, "a")
        now += 10
        progress(1, 120, "b") // too soon: thinned out
        now += 100
        progress(2, 120, "c")
        now += 5
        progress(120, 120, "") // the last step always shows
        now += 5
        progress(0, 1, "Waiting for Termux") // a new phase, 5 ms later: shows too
        now += 5
        progress(1, 1, "")

        assertEquals(listOf("0/120 a", "2/120 c", "120/120 ", "0/1 Waiting for Termux", "1/1 "), shown)
    }
}
