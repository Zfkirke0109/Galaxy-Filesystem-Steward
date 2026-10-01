package com.galaxy.steward.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.GIB
import com.galaxy.steward.core.goal.GoalPick
import com.galaxy.steward.core.goal.GoalPlanner
import com.galaxy.steward.core.goal.RiskTier
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.learn.ScanMemory
import com.galaxy.steward.core.learn.StoragePoint
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.termux.TermuxStatus
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow

private val GOALS = listOf(1, 2, 5, 10, 20)

/** Rows shown per tier before "and N more". */
private const val ROWS = 25

/**
 * "Free up this much": pick a size, and the safest suggestions from the last scans (shared storage and Termux) that
 * reach it are ticked, least risky first. Untick anything; the plan fills the gap from what is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalScreen(vm: StewardViewModel, state: UiState, onBack: () -> Unit) {
    val termuxState by vm.termux.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var gib by rememberSaveable { mutableIntStateOf(2) }
    var review by rememberSaveable { mutableStateOf(false) }
    var freeNow by rememberSaveable { mutableStateOf(false) }
    var leaveOut by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<StoragePoint>>(emptyList()) }
    LaunchedEffect(state.report) { history = vm.storageHistory() }

    val target = gib * GIB
    val plan = remember(state.report, termuxState.report, target, review, leaveOut) {
        GoalPlanner.plan(target, state.report, termuxState.report, if (review) RiskTier.REVIEW else RiskTier.RECOVERABLE, leaveOut)
    }
    // Clutter, packed folders and (when set so) duplicates go to the quarantine first; it frees their space when emptied.
    val quarantined = plan.picks.filter { p ->
        val item = p.plan
        item != null && (settings.quarantineDuplicates || (item !is DuplicateGroup && item !is FolderDuplicateGroup))
    }.sumOf { it.bytes }

    Scaffold(
        topBar = { ReviewTopBar("Free up space", onBack) },
        bottomBar = {
            ActionBar(
                summary = "Frees about ${plan.total.humanBytes()}",
                detail = when {
                    plan.picks.isEmpty() -> "Nothing to free from the last scan"
                    plan.reached -> "Your goal: $gib GiB · ${plan.picks.size.plural("step")}"
                    else -> "Short of $gib GiB: this is all the last scans found" +
                        if (!review && (plan.available[RiskTier.REVIEW] ?: 0L) > 0) " without items worth a look" else ""
                },
                action = "Free up",
                enabled = plan.picks.isNotEmpty() && state.applying == null && !state.scanning,
            ) { confirm = true }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.space?.let { s ->
                        Text("${s.freeBytes.humanBytes()} free of ${s.totalBytes.humanBytes()}", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("How much do you want to free?", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GOALS.forEach { g -> FilterChip(selected = g == gib, onClick = { gib = g }, label = { Text("$g GiB") }) }
                    }
                    Text(
                        "The least risky go first: what loses nothing, then exact copies, then what is easy to get back. " +
                            "Untick anything and the plan fills the gap from what is left.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (history.size >= 2) item { HistoryCard(history) }
            if (state.report == null) item { InlineNotice("Scan storage first: the goal picks from what the scan found.") }
            if (termuxState.report == null && termuxState.status == TermuxStatus.READY) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Termux wasn't scanned yet, so its caches aren't counted.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { vm.termux.audit() }, enabled = !termuxState.auditing) {
                            Text(if (termuxState.auditing) "Scanning…" else "Scan Termux")
                        }
                    }
                }
            }
            val worthALook = plan.available[RiskTier.REVIEW] ?: 0L
            if (worthALook > 0 || review) {
                item {
                    SwitchLine(
                        "Include items worth a look",
                        "Near-copies, old run folders and leftovers: ${worthALook.humanBytes()} more.",
                        review,
                    ) { review = it }
                }
            }
            if (quarantined > 0) {
                item {
                    SwitchLine(
                        "Free it right away",
                        "Clutter and copies wait ${settings.quarantineRetentionDays} days in the quarantine, where History can undo them, " +
                            "and only then free their space (${quarantined.humanBytes()} here). Right away can't be undone.",
                        freeNow,
                    ) { freeNow = it }
                }
            }
            for (tier in RiskTier.entries) {
                val picked = plan.picks.filter { it.tier == tier }
                val left = plan.left.filter { it.tier == tier }
                if (picked.isEmpty() && left.isEmpty()) continue
                item(key = "tier-${tier.name}") {
                    SectionHeader("${tier.title} · ${picked.sumOf { it.bytes }.humanBytes()}")
                    Text(
                        tier.why,
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val rows = (picked + left).take(ROWS)
                items(rows, key = { "pick-${it.id}" }) { pick ->
                    ReviewCard {
                        SelectRow(
                            checked = pick.id !in leaveOut,
                            onCheckedChange = { leaveOut = if (pick.id in leaveOut) leaveOut - pick.id else leaveOut + pick.id },
                            title = pick.title,
                            subtitle = describe(pick, vm.rootPath, termuxState.report?.home, termuxState.report?.prefix),
                            trailing = { SizeText(pick.bytes) },
                        )
                    }
                }
                val more = picked.size + left.size - rows.size
                if (more > 0) {
                    item(key = "more-${tier.name}") {
                        Text(
                            "and $more more (${(picked + left).drop(ROWS).sumOf { it.bytes }.humanBytes()})",
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (confirm) {
        val shared = plan.picks.count { !it.inTermux }
        val termux = plan.picks.count { it.inTermux }
        ConfirmDialog(
            title = "Free about ${plan.total.humanBytes()}?",
            lines = listOfNotNull(
                shared.takeIf { it > 0 }?.let { "${it.plural("item")} in shared storage" },
                termux.takeIf { it > 0 }?.let { "${it.plural("location")} in Termux (can't be undone)" },
                if (freeNow && quarantined > 0) "The quarantine is emptied right away" else null,
            ),
            confirmLabel = "Free up",
            onConfirm = {
                confirm = false
                vm.reachGoal(plan, freeNow)
                leaveOut = emptySet()
            },
            onDismiss = { confirm = false },
            footnote = "Every change in shared storage is checked and journaled" +
                if (freeNow) "." else "; History can undo it until the quarantine is emptied.",
        )
    }
}

private fun describe(pick: GoalPick, root: String, home: String?, prefix: String?): String {
    pick.termux?.let { t ->
        val where = if (home != null && prefix != null) TermuxProtocol.relative(t.path, home, prefix) else t.path
        return "Termux · ${t.group.title} · $where"
    }
    fun rel(path: String) = path.removePrefix("$root/")
    return when (val p = pick.plan) {
        is JunkItem -> "${p.category.title} · ${rel(p.path)}"
        is DuplicateGroup -> "${p.removals.size.plural("copy", "copies")} of ${rel(p.keeper.path)}, which stays"
        is FolderDuplicateGroup -> "${p.removals.size.plural("copy", "copies")} of the folder ${rel(p.keeper.path)}, which stays"
        is OptimizeItem -> "Packed losslessly into a zip · ${rel(p.path)}"
        else -> ""
    }
}

@Composable
private fun SwitchLine(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Used space after each scan (the volume's, or what the scan saw), with how fast it grows. */
@Composable
private fun HistoryCard(history: List<StoragePoint>) {
    fun used(p: StoragePoint) = if (p.total > 0 && p.free >= 0) p.total - p.free else p.scanned
    val points = history.takeLast(60)
    val growth = remember(history) { ScanMemory.growthInsight(history.dropLast(1), history.last()) }
    ReviewCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Storage over time", style = MaterialTheme.typography.titleSmall)
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val values = points.map { used(it).toFloat() }
                val lo = values.min()
                val hi = values.max().coerceAtLeast(lo + 1f)
                val first = points.first().time
                val span = (points.last().time - first).coerceAtLeast(1L).toFloat()
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = (p.time - first) / span * size.width
                    val y = size.height - (values[i] - lo) / (hi - lo) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color, 3.dp.toPx(), Offset(size.width, size.height - (values.last() - lo) / (hi - lo) * size.height))
            }
            Text(
                growth?.let { "${it.title}. ${it.detail}" }
                    ?: "${used(points.first()).humanBytes()} → ${used(points.last()).humanBytes()} used over ${points.size} scans.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
