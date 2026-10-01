package com.galaxy.steward.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.plan.OrganizeMove
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.GroupHeader
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.InsightRow
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow
import com.galaxy.steward.ui.components.folderIcon
import com.galaxy.steward.ui.components.kindColor
import com.galaxy.steward.ui.components.kindIcon
import com.galaxy.steward.ui.components.relativeTo
import com.galaxy.steward.ui.components.toggleState

@Composable
fun OrganizeScreen(vm: StewardViewModel, state: UiState, onBack: () -> Unit) {
    val moves = state.report?.organize.orEmpty()
    val groups = remember(moves) { moves.groupBy { it.destinationFolder }.toSortedMap() }
    val leftInPlace = remember(state.report) { state.report?.insights.orEmpty().filter { it.title.startsWith("Left in place") } }
    val selected = moves.filter { it.id in state.selected }
    var confirm by remember { mutableStateOf<List<OrganizeMove>?>(null) }

    Scaffold(
        topBar = {
            ReviewTopBar("Smart organize", onBack) {
                TextButton(onClick = { vm.setSelected(moves.map { it.id }, true) }) { Text("All") }
                TextButton(onClick = { vm.setSelected(moves.map { it.id }, false) }) { Text("None") }
            }
        },
        bottomBar = {
            ActionBar(
                summary = "${selected.sumOf { it.fileCount }.plural("file", "files")} to file",
                detail = "${selected.sumOf { it.bytes }.humanBytes()} into ${(selected.map { it.destinationFolder }.distinct().size).plural("folder", "folders")}",
                action = "Organize",
                enabled = selected.isNotEmpty(),
            ) { confirm = selected }
        },
    ) { padding ->
        if (moves.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Rounded.DriveFileMove, "Already organised", "Download is empty of loose files and Documents has no stray wrappers.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "Download is treated as an inbox. Files get a permanent home by name, type and content; folders move as a unit. " +
                        "Nothing is overwritten - identical files are deduped, different ones get a unique name.",
                )
            }
            for ((folder, items) in groups) {
                item(key = "g:$folder") {
                    ReviewCard {
                        Column {
                            GroupHeader(
                                title = folder.relativeTo(vm.rootPath),
                                subtitle = "${items.size.plural("item", "items")} · ${items.sumOf { it.bytes }.humanBytes()}",
                                state = toggleState(items.count { it.id in state.selected }, items.size),
                                onToggle = {
                                    val all = items.all { it.id in state.selected }
                                    vm.setSelected(items.map { it.id }, !all)
                                },
                            )
                            items.take(200).forEach { move -> MoveRow(move, move.id in state.selected, vm.rootPath, state.learned[move.id]?.note) { vm.toggle(move.id) } }
                            if (items.size > 200) {
                                Text("…and ${items.size - 200} more", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
                            }
                        }
                    }
                }
            }
            if (leftInPlace.isNotEmpty()) {
                item { SectionHeader("Left in place (${leftInPlace.size})") }
                items(leftInPlace.take(50)) { InsightRow(it, vm.rootPath) }
            }
        }
    }

    confirm?.let { items ->
        ConfirmDialog(
            title = "Organize ${items.size.plural("item", "items")}?",
            lines = listOf(
                "${items.count { it.isDirectory }.plural("folder", "folders")} and ${items.count { !it.isDirectory }.plural("file", "files")} get a permanent home",
                "Destinations: ${items.map { it.destinationFolder.relativeTo(vm.rootPath).substringBefore('/') }.distinct().joinToString()}",
                "Folders emptied by the move are tidied away",
            ),
            confirmLabel = "Organize",
            onConfirm = {
                confirm = null
                vm.apply("Smart organize", "organize", items)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun MoveRow(move: OrganizeMove, checked: Boolean, root: String, learned: String?, onToggle: () -> Unit) {
    val kind = if (move.isDirectory) null else FileKind.of(move.title)
    // A learned home explains itself in a sentence: that goes under the name, with a short pill.
    val learnedHome = move.reason.substringAfter("Learned: ", "").takeIf { it.isNotEmpty() }
    SelectRow(
        checked = checked,
        onCheckedChange = { onToggle() },
        title = move.title + if (move.isDirectory) "/" else "",
        subtitle = "from " + move.source.substringBeforeLast('/').relativeTo(root) +
            (if (move.isDirectory) " · ${move.fileCount} files" else "") + " · ${move.bytes.humanBytes()}" +
            (learnedHome?.let { "\nLearned from your folders: it $it" } ?: "") +
            (learned?.let { "\n$it" } ?: ""),
        leading = {
            if (kind == null) IconBadge(folderIcon, MaterialTheme.colorScheme.primary, size = 32) else IconBadge(kindIcon(kind), kindColor(kind), size = 32)
        },
        trailing = { Pill(if (learnedHome != null) "Learned" else move.reason) },
        subtitleLines = 4,
    )
}
