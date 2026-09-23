package com.galaxy.steward.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.ui.ApplyProgress
import com.galaxy.steward.ui.Outcome

/** Confirmation "token" for any change: the Termux steward required typing one, the app requires this tap. */
@Composable
fun ConfirmDialog(
    title: String,
    lines: List<String>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    footnote: String = "Every change is verified and journaled. You can undo this run from History.",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(6.dp))
                Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ApplyProgressDialog(progress: ApplyProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(progress.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UsageBar(if (progress.total == 0) 0f else progress.done.toFloat() / progress.total)
                Text("${progress.done} of ${progress.total} steps", style = MaterialTheme.typography.bodyMedium)
                if (progress.current.isNotEmpty()) {
                    Text(progress.current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                Text(
                    "Stopping is safe: completed steps stay journaled and can be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Stop") } },
    )
}

@Composable
fun OutcomeDialog(outcome: Outcome, onUndo: ((runId: String, title: String) -> Unit)?, onDismiss: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    val (title, lines, details, runId, runTitle) = when (outcome) {
        is Outcome.Applied -> {
            val s = outcome.summary
            val lines = buildList {
                if (s.bytesFreed > 0) add("Freed ${s.bytesFreed.humanBytes()}")
                if (s.cleared > 0) add("${s.cleared.plural("file")} removed for good")
                if (s.deduped > 0) add("${s.deduped.plural("verified duplicate", "verified duplicates")} removed")
                if (s.quarantined > 0) add("${s.quarantined.plural("item", "items")} quarantined (${s.bytesQuarantined.humanBytes()} - freed when the quarantine is emptied)")
                if (s.moved > 0) add("${s.moved.plural("item", "items")} organised")
                if (s.removedDirs > 0) add("${s.removedDirs.plural("empty folder", "empty folders")} removed")
                if (s.skipped > 0) add("${s.skipped.plural("step", "steps")} skipped safely")
                if (s.failed > 0) add("${s.failed.plural("step", "steps")} failed")
                if (isEmpty()) add("Nothing needed changing.")
            }
            Outcome5(outcome.title, lines, s.messages, s.runId.takeIf { s.changedAnything }, outcome.title)
        }
        is Outcome.RolledBack -> {
            val s = outcome.summary
            Outcome5(
                "Undone",
                listOf("${s.restored.plural("change", "changes")} reverted") +
                    (if (s.skipped > 0) listOf("${s.skipped} left as they are (already changed)") else emptyList()) +
                    (if (s.failed > 0) listOf("${s.failed} could not be reverted") else emptyList()),
                s.messages, null, "",
            )
        }
        is Outcome.Purged -> Outcome5("Quarantine emptied", listOf("Freed ${outcome.bytes.humanBytes()}"), emptyList(), null, "")
        is Outcome.Failed -> Outcome5("Something went wrong", listOf(outcome.message), emptyList(), null, "")
        is Outcome.Report -> Outcome5(outcome.title, outcome.lines, outcome.details, null, "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (outcome is Outcome.Failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (outcome is Outcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (details.isNotEmpty()) {
                    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Why were steps skipped?") }
                    if (showDetails) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(details) {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (runId != null && onUndo != null) {
            { TextButton(onClick = { onUndo(runId, runTitle) }) { Text("Undo") } }
        } else {
            null
        },
    )
}

private data class Outcome5(val title: String, val lines: List<String>, val details: List<String>, val runId: String?, val runTitle: String)

@Composable
fun InlineNotice(text: String, error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Icon(
            if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Shield,
            contentDescription = null,
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.padding(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
