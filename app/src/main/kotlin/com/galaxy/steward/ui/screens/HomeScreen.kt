package com.galaxy.steward.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.FolderMerge
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OrganizeMove
import com.galaxy.steward.core.plan.Severity
import com.galaxy.steward.ui.Routes
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.InsightRow
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.StatCard
import com.galaxy.steward.ui.components.UsageBar
import com.galaxy.steward.ui.components.kindColor
import com.galaxy.steward.ui.components.rememberScanStarter
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: StewardViewModel, state: UiState, navigate: (String) -> Unit) {
    var confirmAutopilot by remember { mutableStateOf(false) }
    val report = state.report

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
                Text("Galaxy Steward", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Koa keeps ${vm.deviceLabel.replace('-', ' ')} tidy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { StorageCard(state) }
        item { ScanCard(vm, state) }

        if (report != null && !state.scanning) {
            val dupCount = report.duplicates.sumOf { it.removals.size } + report.folderDuplicates.size
            item { SectionHeader("Review") }
            item {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        Icons.Rounded.ContentCopy, MaterialTheme.colorScheme.primary, "Duplicates",
                        report.duplicateBytes.humanBytes(),
                        dupCount.plural("removable copy", "removable copies") + " · " + report.folderMerges.size.plural("folder merge"),
                        Modifier.weight(1f),
                    ) { navigate(Routes.DUPLICATES) }
                    StatCard(
                        Icons.Rounded.CleaningServices, MaterialTheme.colorScheme.tertiary, "Clutter",
                        report.junkBytes.humanBytes(),
                        report.junk.size.plural("leftover") + " found",
                        Modifier.weight(1f),
                    ) { navigate(Routes.JUNK) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        Icons.AutoMirrored.Rounded.DriveFileMove, MaterialTheme.colorScheme.secondary, "Organize",
                        report.organize.size.plural("item"),
                        "Filed into " + report.organize.map { it.destinationFolder }.distinct().size.plural("folder"),
                        Modifier.weight(1f),
                    ) { navigate(Routes.ORGANIZE) }
                    StatCard(
                        Icons.Rounded.Speed, MaterialTheme.colorScheme.primary, "Optimize",
                        report.optimize.size.plural("fix", "fixes"),
                        report.insights.count { it.severity != Severity.INFO }.plural("health tip"),
                        Modifier.weight(1f),
                    ) { navigate(Routes.OPTIMIZE) }
                }
            }
            item { AutopilotCard(vm, state) { confirmAutopilot = true } }
            item {
                Card(
                    onClick = { navigate(Routes.EXPLORE) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Explore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Storage map", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${report.summary.totalFiles} files · ${report.summary.totalBytes.humanBytes()} - see what uses space",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            val important = report.insights.filter { it.severity != Severity.INFO }.sortedByDescending { it.severity.ordinal }.take(4)
            if (important.isNotEmpty()) {
                item { SectionHeader("Health") }
                items(important) { InsightRow(it, vm.rootPath) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmAutopilot) {
        val items = vm.autopilotItems()
        val freed = items.sumOf { it.reclaimBytes }
        ConfirmDialog(
            title = "Apply all selected?",
            lines = listOfNotNull(
                items.count { it is DuplicateGroup || it is FolderDuplicateGroup || it is FolderMerge }.takeIf { it > 0 }?.let { "$it duplicate groups and merges" },
                items.count { it is JunkItem }.takeIf { it > 0 }?.let { "$it clutter items" },
                items.count { it is OptimizeItem }.takeIf { it > 0 }?.let { "$it layout fixes" },
                items.count { it is OrganizeMove }.takeIf { it > 0 }?.let { "$it items filed into folders" },
                "About ${freed.humanBytes()} reclaimed",
            ),
            confirmLabel = "Apply",
            onConfirm = {
                confirmAutopilot = false
                vm.apply("Autopilot", "autopilot", items)
            },
            onDismiss = { confirmAutopilot = false },
        )
    }
}

@Composable
private fun StorageCard(state: UiState) {
    val space = state.space ?: return
    val used = space.totalBytes - space.freeBytes
    val fraction = if (space.totalBytes > 0) used.toFloat() / space.totalBytes else 0f
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Internal storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(used.humanBytes(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "  of ${space.totalBytes.humanBytes()}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            val kinds = state.report?.summary?.byKind
            if (kinds != null && space.totalBytes > 0) {
                KindBar(kinds.mapValues { it.value.bytes }, used, space.totalBytes)
            } else {
                UsageBar(fraction, height = 12, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${space.freeBytes.humanBytes()} free (${(100 - fraction * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (kinds != null) {
                Spacer(Modifier.height(8.dp))
                KindLegend(kinds.mapValues { it.value.bytes })
                Text(
                    "Grey: apps, system and files Android keeps private",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Scanned files by type, then everything else that is used (apps, system), then free space as the track. */
@Composable
fun KindBar(bytesByKind: Map<FileKind, Long>, usedBytes: Long, totalBytes: Long) {
    Row(
        Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        var accounted = 0f
        for ((kind, bytes) in bytesByKind.entries.sortedByDescending { it.value }) {
            val w = bytes.toFloat() / totalBytes
            if (w <= 0.002f) continue
            accounted += w
            Box(Modifier.weight(w).fillMaxHeight().background(kindColor(kind)))
        }
        val otherUsed = usedBytes.toFloat() / totalBytes - accounted
        if (otherUsed > 0.002f) {
            accounted += otherUsed
            Box(Modifier.weight(otherUsed).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)))
        }
        if (accounted < 0.999f) Spacer(Modifier.weight(1f - accounted))
    }
}

@Composable
fun KindLegend(bytesByKind: Map<FileKind, Long>) {
    val entries = bytesByKind.entries.sortedByDescending { it.value }.filter { it.value > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.chunked(2).forEach { row ->
            Row {
                row.forEach { (kind, bytes) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(kind)))
                        Spacer(Modifier.width(6.dp))
                        Text("${kind.label} ${bytes.humanBytes()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScanCard(vm: StewardViewModel, state: UiState) {
    val startScan = rememberScanStarter(vm)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val progress = state.progress
            when {
                state.scanning && progress != null -> {
                    Text(progress.phase.label, style = MaterialTheme.typography.titleMedium)
                    Text("${progress.phase.koa}…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    UsageBar(progress.overall, height = 10)
                    val counts = when {
                        progress.total > 0 -> "${progress.done} / ${progress.total}"
                        progress.phase.ordinal == 0 -> "${progress.done} folders"
                        else -> ""
                    }
                    Text(
                        listOf(counts, "${progress.filesSeen} files", progress.bytesSeen.humanBytes()).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = vm::cancelScan) { Text("Cancel") }
                }
                state.report == null -> {
                    Text("Smart scan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Maps your storage, fingerprints look-alike files with SHA-256 and plans a tidy layout. " +
                            "Read-only: nothing changes until you review and approve it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = startScan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start smart scan")
                    }
                }
                else -> {
                    val report = state.report
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Last survey", style = MaterialTheme.typography.titleMedium)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.finishedAt)) +
                                    " · ${((report.finishedAt - report.startedAt) / 1000).coerceAtLeast(1)} s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = startScan) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Rescan")
                        }
                    }
                    if (state.stale) InlineNotice("Files or settings changed since this survey - rescan for fresh results.")
                }
            }
        }
    }
}

@Composable
private fun AutopilotCard(vm: StewardViewModel, state: UiState, onApply: () -> Unit) {
    val items = remember(state.selected, state.report, state.keepers) { vm.autopilotItems() }
    if (items.isEmpty()) return
    val freed = items.sumOf { it.reclaimBytes }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Autopilot", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "${items.size} recommended actions · ~${freed.humanBytes()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Button(onClick = onApply) { Text("Review & apply") }
        }
    }
}
