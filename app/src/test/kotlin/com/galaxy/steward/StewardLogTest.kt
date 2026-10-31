package com.galaxy.steward

import com.galaxy.steward.diagnostics.StewardLog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The log says what went wrong, never which file: it goes into exports shared for support. */
class StewardLogTest {
    @Test
    fun pathsInErrorMessagesAreLeftOut() {
        assertEquals("<path>: open failed: EACCES (Permission denied)", StewardLog.redact("/storage/emulated/0/Download/my notes.txt: open failed: EACCES (Permission denied)"))
        assertEquals("<path> -> <path>", StewardLog.redact("/storage/emulated/0/DCIM/a.jpg -> /storage/emulated/0/.StorageSteward/Quarantine/1/DCIM/a.jpg"))
        assertEquals("cannot read <path>", StewardLog.redact("cannot read /data/data/com.termux/files/home/projects/x"))
        assertEquals("Not an attached client", StewardLog.redact("Not an attached client"))
        // Versions, sizes and package names stay.
        assertEquals("helper 1.2.13 took 20001 ms for com.example.app", StewardLog.redact("helper 1.2.13 took 20001 ms for com.example.app"))
    }
}
