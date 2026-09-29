package com.galaxy.steward.core

import com.galaxy.steward.core.termux.TermuxCleanResult
import com.galaxy.steward.core.termux.TermuxEntry
import com.galaxy.steward.core.termux.TermuxReport
import com.galaxy.steward.core.termux.TermuxUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Termux screen shows after a clean-up or an uninstall, without a new audit. */
class TermuxTotalsTest {
    private val files = "/data/data/com.termux/files"
    private val home = "$files/home"
    private val prefix = "$files/usr"

    private fun report() = TermuxReport(
        home, prefix, false,
        usage = listOf(TermuxUsage(files, 1000 * MIB), TermuxUsage(home, 400 * MIB), TermuxUsage(prefix, 600 * MIB), TermuxUsage("$home/.cache", 150 * MIB)),
        items = emptyList(), rootfs = emptyList(), largeFiles = emptyList(), warnings = emptyList(), unsafe = emptyList(),
        entries = listOf(
            TermuxEntry(home, 400 * MIB, true), TermuxEntry("$home/.cache", 150 * MIB, true),
            TermuxEntry("$home/.cache/pip", 100 * MIB, true), TermuxEntry("$home/proj/build", 50 * MIB, true),
            TermuxEntry("$home/proj/build/out.apk", 20 * MIB, false), TermuxEntry(prefix, 600 * MIB, true),
        ),
    )

    @Test
    fun cleaningAndUninstallingShrinkEveryTotalAboveWhatWent() {
        val after = report().afterCleaning(
            listOf(
                TermuxCleanResult("pip-cache", "CLEARED", 100 * MIB, 0, "$home/.cache/pip", ""),
                TermuxCleanResult("build", "CLEARED", 50 * MIB, 0, "$home/proj/build", ""),
                TermuxCleanResult("chromium", "REMOVED", 200 * MIB, 0, "", ""),
                TermuxCleanResult("gc", "NO_CHANGE", 5 * MIB, 5 * MIB, "$home/repo", "already packed"),
            ),
        )
        // 1.2.9 kept saying 22.1 GiB after a clean-up had freed 2.6 GiB.
        assertEquals(650L * MIB, after.totalBytes)
        assertEquals(250L * MIB, after.usage.single { it.path == home }.bytes)
        assertEquals(400L * MIB, after.usage.single { it.path == prefix }.bytes)
        assertEquals(50L * MIB, after.entry("$home/.cache")!!.bytes)
        // An emptied cache stays, smaller; a removed build output leaves the map with what was in it.
        assertEquals(0L, after.entry("$home/.cache/pip")!!.bytes)
        assertNull(after.entry("$home/proj/build"))
        assertNull(after.entry("$home/proj/build/out.apk"))
    }
}
