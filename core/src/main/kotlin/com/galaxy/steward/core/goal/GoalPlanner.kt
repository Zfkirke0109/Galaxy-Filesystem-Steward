package com.galaxy.steward.core.goal

import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.termux.TermuxGroup
import com.galaxy.steward.core.termux.TermuxItem
import com.galaxy.steward.core.termux.TermuxReport

/** How much could go wrong with a pick, least first. A goal takes from a tier only when the ones before it aren't enough. */
enum class RiskTier(val title: String, val why: String) {
    NOTHING_LOST("Nothing you'd miss", "Temp files, partial downloads, old logs, empty folders and caches that are rebuilt on demand."),
    COPIES("Exact copies", "Byte-for-byte duplicates and archives already unpacked next to them: one copy always stays."),
    RECOVERABLE(
        "Easy to get back",
        "Installers of apps you have, folders packed losslessly into a zip, build outputs and download caches that tools fetch again.",
    ),
    REVIEW("Worth a look first", "Near-copies, old run folders, leftovers and other caches: likely unneeded, but check them."),
}

/** One suggestion a goal takes: a shared-storage [PlanItem] or a Termux clean-up item, never both. */
data class GoalPick(val tier: RiskTier, val bytes: Long, val plan: PlanItem? = null, val termux: TermuxItem? = null) {
    val id: String get() = plan?.id ?: "termux:" + termux!!.spec
    val title: String get() = plan?.title ?: termux!!.title
    val inTermux: Boolean get() = termux != null
}

/** [left]: what you unticked, still shown so you can tick it again. */
data class GoalPlan(val target: Long, val picks: List<GoalPick>, val available: Map<RiskTier, Long>, val left: List<GoalPick> = emptyList()) {
    val total: Long get() = picks.sumOf { it.bytes }
    val reached: Boolean get() = total >= target

    /** Everything up to [RiskTier.REVIEW] together: the most any goal could free from this scan. */
    val possible: Long get() = available.values.sum()
}

/**
 * Plans "free up this much": everything that loses nothing, then the least risky tier (by [RiskTier]) that still has
 * something, the biggest first so the goal takes few steps, and to close the last gap the smallest pick that does. Only suggestions the scans already made are used, so every rule and safety check still applies when it runs.
 */
object GoalPlanner {
    fun tierOf(item: PlanItem): RiskTier? = when (item) {
        is JunkItem -> when (item.category) {
            JunkCategory.STALE_DOWNLOADS, JunkCategory.OLD_LOGS, JunkCategory.EMPTY_FOLDERS, JunkCategory.HEAP_DUMPS,
            JunkCategory.THUMBNAIL_CACHES,
            -> RiskTier.NOTHING_LOST
            JunkCategory.EXTRACTED_ARCHIVES -> RiskTier.COPIES
            JunkCategory.INSTALLED_APKS, JunkCategory.OLD_INSTALLERS, JunkCategory.TRASHED_MEDIA, JunkCategory.RECYCLE_BINS -> RiskTier.RECOVERABLE
            JunkCategory.NEAR_COPIES, JunkCategory.OLD_RUNS, JunkCategory.ORPHANED_APP_FOLDERS, JunkCategory.APP_KEEPS_A_COPY -> RiskTier.REVIEW
            JunkCategory.ZERO_BYTE_FILES -> null
        }
        is DuplicateGroup -> RiskTier.COPIES.takeIf { item.removals.isNotEmpty() }
        is FolderDuplicateGroup -> RiskTier.COPIES.takeIf { item.removals.isNotEmpty() }
        is OptimizeItem -> RiskTier.RECOVERABLE.takeIf { item.kind == OptimizeKind.PACK_COLD_FOLDER }
        // Merging and filing move things; they free nothing.
        else -> null
    }

    /** The files and folders in shared storage a pick removes. */
    private fun pathsOf(item: PlanItem): List<String> = when (item) {
        is JunkItem -> listOf(item.path)
        is DuplicateGroup -> item.removals.map { it.path }
        is FolderDuplicateGroup -> item.removals.map { it.path }
        is OptimizeItem -> listOf(item.path)
        else -> emptyList()
    }

    /** Paths taken so far: [touches] is true for one of them, one inside one, or one around one. */
    private class PathSet {
        private val paths = java.util.TreeSet<String>()

        fun add(path: String) {
            paths += path
        }

        fun touches(path: String): Boolean {
            if (path in paths) return true
            var up = path.substringBeforeLast('/', "")
            while (up.isNotEmpty()) {
                if (up in paths) return true
                up = up.substringBeforeLast('/', "")
            }
            return paths.ceiling("$path/")?.startsWith("$path/") == true
        }
    }

    fun tierOf(item: TermuxItem): RiskTier = when (item.group) {
        TermuxGroup.SAFE, TermuxGroup.PROOT -> RiskTier.NOTHING_LOST
        TermuxGroup.DEV, TermuxGroup.BUILD -> RiskTier.RECOVERABLE
        TermuxGroup.OTHER, TermuxGroup.LEFTOVERS -> RiskTier.REVIEW
    }

    /**
     * Picks for freeing [target] bytes from [report] (shared storage) and [termux] (the last Termux scan), taking tiers
     * up to [upTo]. [leaveOut] are ids the goal must not take (ones you unticked on the goal screen).
     */
    fun plan(
        target: Long,
        report: ScanReport?,
        termux: TermuxReport?,
        upTo: RiskTier = RiskTier.RECOVERABLE,
        leaveOut: Set<String> = emptySet(),
    ): GoalPlan {
        val all = ArrayList<GoalPick>()
        report?.allItems()?.forEach { item ->
            val tier = tierOf(item) ?: return@forEach
            if (item.reclaimBytes > 0) all += GoalPick(tier, item.reclaimBytes, plan = item)
        }
        termux?.items?.forEach { item -> if (item.bytes > 0) all += GoalPick(tierOf(item), item.bytes, termux = item) }
        val available = all.groupBy { it.tier }.mapValues { (_, list) -> list.sumOf { it.bytes } }
        val candidates = all.filter { it.tier <= upTo && it.id !in leaveOut }
        val picks = ArrayList<GoalPick>()
        var total = 0L
        // Two suggestions can take the same files (a folder that is both an exact copy and packable, near-copies inside
        // one another): a pick is left out once one taken before it covers its files, so they count once.
        val taken = PathSet()
        fun overlaps(p: GoalPick) = p.plan?.let { item -> pathsOf(item).any(taken::touches) } ?: false
        fun take(p: GoalPick) {
            picks += p
            total += p.bytes
            p.plan?.let { item -> pathsOf(item).forEach(taken::add) }
        }
        for (tier in RiskTier.entries) {
            val here = candidates.filter { it.tier == tier }.sortedByDescending { it.bytes }.toMutableList()
            // What loses nothing is always worth taking; beyond it, only what the goal still needs.
            if (tier == RiskTier.NOTHING_LOST) {
                for (p in here) if (!overlaps(p)) take(p)
                continue
            }
            while (total < target) {
                here.removeAll(::overlaps)
                if (here.isEmpty()) break
                val gap = target - total
                // The smallest one that closes the gap, or else the biggest, and look again.
                val pick = here.lastOrNull { it.bytes >= gap } ?: here.first()
                here.remove(pick)
                take(pick)
            }
            if (total >= target) break
        }
        return GoalPlan(target, picks, available, all.filter { it.tier <= upTo && it.id in leaveOut })
    }
}
