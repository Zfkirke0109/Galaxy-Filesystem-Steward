package com.galaxy.steward.core.goal

import com.galaxy.steward.core.learn.PreferenceModel
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.ScanReport

/**
 * What the weekly upkeep may do on its own, with nobody watching: the clutter that loses nothing
 * ([RiskTier.NOTHING_LOST]: abandoned downloads, old logs, empty folders, heap dumps, thumbnail caches) and that is
 * ticked by default, less whatever your past choices say you untick ([PreferenceModel]). Everything else waits for you.
 */
object Upkeep {
    fun select(report: ScanReport, model: PreferenceModel?, root: String, now: Long): List<JunkItem> = report.junk.filter { item ->
        item.defaultSelected && GoalPlanner.tierOf(item) == RiskTier.NOTHING_LOST && model?.choiceFor(item, root, now)?.select != false
    }
}
