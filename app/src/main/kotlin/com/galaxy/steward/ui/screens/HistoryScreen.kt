package com.galaxy.steward.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.exec.JournalInfo
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import java.text.DateFormat
import java.util.Date

private sealed interface HistoryConfirm {
    data class Undo(val info: JournalInfo) : HistoryConfirm
    data class Purge(val runId: String?, val bytes: Long) : HistoryConfirm
}

@Composable
fun HistoryScreen(vm: StewardViewModel, state: UiState) {
    var confirm by remember { mutableStateOf<HistoryConfirm?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val retention = settings.quarantineRetentionDays

    Scaffold(topBar = { ReviewTopBar("History", null) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ReviewCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Inventory2, MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Quarantine", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.quarantineBytes > 0) {
                                    "${state.quarantineBytes.humanBytes()} held for undo · emptied automatically after ${retention} days"
                                } else {
                                    "Empty. Quarantined items are kept for $retention days."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.quarantineBytes > 0) {
                            FilledTonalButton(onClick = { confirm = HistoryConfirm.Purge(null, state.quarantineBytes) }) { Text("Empty") }
                        }
                    }
                }
            }
            if (state.journals.isEmpty()) {
                item { EmptyState(Icons.Rounded.History, "No runs yet", "Every cleanup is journaled here so you can undo it.") }
            } else {
                item { SectionHeader("Runs") }
                items(state.journals, key = { it.id }) { info ->
                    RunCard(
                        info = info,
                        quarantined = state.quarantineByRun[info.id] ?: 0L,
                        onUndo = { confirm = HistoryConfirm.Undo(info) },
                        onPurge = { confirm = HistoryConfirm.Purge(info.id, state.quarantineByRun[info.id] ?: 0L) },
                    )
                }
            }
        }
    }

    when (val c = confirm) {
        is HistoryConfirm.Undo -> ConfirmDialog(
            title = "Undo \"${c.info.title}\"?",
            lines = listOf(
                "Moved files go back to where they were",
                "Deleted duplicates are recreated from the kept copy",
                "Quarantined items are restored",
                "Anything changed since then is left alone",
            ),
            confirmLabel = "Undo",
            footnote = "Each step is verified (size and SHA-256) before it is reverted.",
            onConfirm = {
                confirm = null
                vm.rollback(c.info.id, c.info.title)
            },
            onDismiss = { confirm = null },
        )
        is HistoryConfirm.Purge -> ConfirmDialog(
            title = "Empty quarantine?",
            lines = listOf("Permanently frees ${c.bytes.humanBytes()}", "Those items can no longer be restored"),
            confirmLabel = "Empty",
            footnote = "Moves and deduplicated files stay undoable; only quarantined items become permanent.",
            onConfirm = {
                confirm = null
                vm.purgeQuarantine(c.runId)
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
private fun RunCard(info: JournalInfo, quarantined: Long, onUndo: () -> Unit, onPurge: () -> Unit) {
    ReviewCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when {
                    info.rolledBackAt != null -> Pill("Undone")
                    info.finishedAt == null -> Pill("Interrupted", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info.startedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val facts = buildList {
                if (info.stat("freed") > 0) add("freed ${info.stat("freed").humanBytes()}")
                if (info.stat("deduped") > 0) add("${info.stat("deduped")} deduped")
                if (info.stat("moved") > 0) add("${info.stat("moved")} moved")
                if (info.stat("quarantined") > 0) add("${info.stat("quarantined")} quarantined")
                if (info.stat("rmdir") > 0) add("${info.stat("rmdir")} folders removed")
                if (info.stat("skipped") > 0) add("${info.stat("skipped")} skipped")
            }
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (quarantined > 0) {
                Text("${quarantined.humanBytes()} in quarantine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            } else if (info.purgedAt != null && info.stat("quarantined") > 0) {
                Text("Quarantine emptied", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (quarantined > 0) {
                    OutlinedButton(onClick = onPurge) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Empty")
                    }
                }
                if (info.canRollback) {
                    FilledTonalButton(onClick = onUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                }
            }
        }
    }
}
