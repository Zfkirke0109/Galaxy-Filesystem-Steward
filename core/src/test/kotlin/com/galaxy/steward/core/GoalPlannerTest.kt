package com.galaxy.steward.core

import com.galaxy.steward.core.goal.GoalPlanner
import com.galaxy.steward.core.goal.RiskTier
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.termux.TermuxGroup
import com.galaxy.steward.core.termux.TermuxItem
import com.galaxy.steward.core.termux.TermuxReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Free up this much": the least risky suggestions first, and no more than the goal needs. */
class GoalPlannerTest {
    private fun junk(category: JunkCategory, name: String, mib: Long) =
        JunkItem("junk:$name", category, "/sd/$name", false, mib * MIB, 0L, "")

    private fun termux(group: TermuxGroup, name: String, mib: Long) =
        TermuxItem(name, name, name, group, "/t/$name", mib * MIB, 1, false, "")

    private suspend fun report(items: List<JunkItem>): ScanReport = TestFs().use { fs ->
        fs.text("Documents/a.txt", "a")
        Steward(fs.rootPath, testSettings, TestEnv, null).scan().copy(junk = items, duplicates = emptyList(), organize = emptyList(), optimize = emptyList())
    }

    @Test
    fun theSafestGoFirstAndTheGoalTakesNoMoreThanItNeeds() = runTest {
        val report = report(
            listOf(
                junk(JunkCategory.OLD_LOGS, "logs", 300),
                junk(JunkCategory.STALE_DOWNLOADS, "partial", 100),
                junk(JunkCategory.INSTALLED_APKS, "big.apk", 900),
                junk(JunkCategory.INSTALLED_APKS, "small.apk", 250),
                junk(JunkCategory.NEAR_COPIES, "old-copy", 5000),
            ),
        )
        val t = TermuxReport("/t/home", "/t/usr", false, emptyList(), listOf(termux(TermuxGroup.SAFE, "apt", 200), termux(TermuxGroup.LEFTOVERS, "decompiled", 800)),
            emptyList(), emptyList(), emptyList(), emptyList())

        // 500 MiB: the logs, partial downloads and apt's downloads (600 MiB) lose nothing: all taken, and enough.
        val small = GoalPlanner.plan(500 * MIB, report, t)
        assertEquals(setOf("junk:logs", "junk:partial", "termux:apt"), small.picks.map { it.id }.toSet())
        assertTrue(small.reached)

        // 800 MiB: all three, then an installer; the small one closes the gap, so the big one isn't taken.
        val more = GoalPlanner.plan(800 * MIB, report, t)
        assertEquals(listOf("junk:logs", "termux:apt", "junk:partial", "junk:small.apk"), more.picks.map { it.id })
        assertEquals(RiskTier.RECOVERABLE, more.picks.last().tier)
        // 1 GiB: the small one isn't enough; the big one is, alone.
        assertEquals("junk:big.apk", GoalPlanner.plan(1024 * MIB, report, t).picks.last().id)
        assertEquals(4, GoalPlanner.plan(1024 * MIB, report, t).picks.size)

        // Near-copies and leftovers only when asked for, and what you leave out stays out.
        val big = GoalPlanner.plan(4096 * MIB, report, t)
        assertFalse(big.reached)
        assertTrue(big.picks.none { it.tier == RiskTier.REVIEW })
        val review = GoalPlanner.plan(4096 * MIB, report, t, upTo = RiskTier.REVIEW, leaveOut = setOf("termux:decompiled"))
        assertTrue(review.reached)
        assertTrue(review.picks.none { it.id == "termux:decompiled" })
        assertEquals((300 + 100 + 900 + 250 + 5000 + 200 + 800) * MIB, big.possible)
    }
}
