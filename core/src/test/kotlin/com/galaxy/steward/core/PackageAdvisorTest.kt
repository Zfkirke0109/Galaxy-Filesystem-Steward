package com.galaxy.steward.core

import com.galaxy.steward.core.termux.HistorySpan
import com.galaxy.steward.core.termux.PackageAdvice
import com.galaxy.steward.core.termux.PackageAdvisor
import com.galaxy.steward.core.termux.TermuxPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageAdvisorTest {
    private val now = 1_800_000_000_000L
    private val mib = 1L shl 20

    private fun pkg(
        name: String,
        mb: Int,
        manual: Boolean = true,
        depends: Set<String> = emptySet(),
        installedDaysAgo: Int = 400,
        lastRunDaysAgo: Int? = null,
        uses: Int = if (lastRunDaysAgo != null) 3 else 0,
        commands: List<String> = listOf(name),
        protected: Boolean = false,
        keptBy: String = "",
    ) = TermuxPackage(
        name, mb * mib, manual, protected, "1.0", depends, "", now - installedDaysAgo * DAY_MS,
        lastRunDaysAgo?.let { now - it * DAY_MS } ?: 0, uses, commands, keptBy,
    )

    private val history = HistorySpan(now - 500 * DAY_MS, 4000)

    @Test
    fun suggestsOnlyWhatYouInstalledThatNothingNeedsOrKeeps() {
        val packages = listOf(
            // Two JDKs: the one you ran last stays.
            pkg("openjdk-17", 300, lastRunDaysAgo = 200, commands = listOf("java17")),
            pkg("openjdk-21", 320, lastRunDaysAgo = 2, commands = listOf("java")),
            // Not run for half a year, with a dependency only it needs, and one another package needs too.
            pkg("chromium", 400, lastRunDaysAgo = 180, depends = setOf("libnss", "libgtk3")),
            pkg("libnss", 30, manual = false),
            pkg("libgtk3", 50, manual = false),
            pkg("firefox", 250, lastRunDaysAgo = 1, depends = setOf("libgtk3")),
            // Not run for five months, and nothing else does its job.
            pkg("gimp", 150, lastRunDaysAgo = 150),
            // Never run in the year since it was installed, and history reaches back that far.
            pkg("pandoc", 256, installedDaysAgo = 365),
            // Never run, but installed before the history starts: nothing can be said.
            pkg("ffmpeg", 90, installedDaysAgo = 600),
            // Run recently; needed by something; Termux needs it; a Layla relay runs with it.
            pkg("git", 40, lastRunDaysAgo = 1),
            pkg("python", 100, lastRunDaysAgo = 400),
            pkg("python-pip", 5, depends = setOf("python"), lastRunDaysAgo = 1),
            pkg("bash", 5, protected = true, lastRunDaysAgo = 400),
            pkg("nodejs", 60, lastRunDaysAgo = 300, keptBy = "started by Termux:Boot (node)"),
            // A library installed by hand that nothing uses.
            pkg("libllvm-extra", 120, commands = emptyList()),
        )
        val suggestions = PackageAdvisor.suggest(packages, history, now).associateBy { it.pkg.name }

        assertEquals(setOf("openjdk-17", "chromium", "gimp", "pandoc", "libllvm-extra"), suggestions.keys)
        assertEquals(PackageAdvice.UNUSED, suggestions.getValue("gimp").advice)
        assertEquals(PackageAdvice.SAME_JOB, suggestions.getValue("openjdk-17").advice)
        assertTrue(suggestions.getValue("openjdk-17").why.startsWith("openjdk-21 is also installed as your Java"))
        val chromium = suggestions.getValue("chromium")
        // Firefox does the same job and ran yesterday: that says more than "not run in six months".
        assertEquals(PackageAdvice.SAME_JOB, chromium.advice)
        assertEquals("firefox is also installed as your browser, and you ran it more recently", chromium.why)
        // libgtk3 stays for firefox; libnss goes with chromium.
        assertEquals(listOf("libnss"), chromium.alsoGoes)
        assertEquals(430 * mib, chromium.total)
        assertEquals(PackageAdvice.NEVER_RUN, suggestions.getValue("pandoc").advice)
        assertEquals(PackageAdvice.NOTHING_USES, suggestions.getValue("libllvm-extra").advice)
        assertFalse("python" in suggestions)
        // Largest first, with what goes along.
        assertEquals("chromium", PackageAdvisor.suggest(packages, history, now).first().pkg.name)
    }

    @Test
    fun withoutDatedHistoryNothingCountsAsNeverRun() {
        val packages = listOf(pkg("pandoc", 256, installedDaysAgo = 365))
        assertTrue(PackageAdvisor.suggest(packages, HistorySpan(0, 0), now).isEmpty())
    }
}
