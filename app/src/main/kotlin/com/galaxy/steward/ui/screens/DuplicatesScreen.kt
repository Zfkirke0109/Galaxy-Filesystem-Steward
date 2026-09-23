@file:OptIn(ExperimentalMaterial3Api::class)

package com.galaxy.steward.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.Zone
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.FolderMerge
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.ZonePill
import com.galaxy.steward.ui.components.folderIcon
import com.galaxy.steward.ui.components.kindColor
import com.galaxy.steward.ui.components.kindIcon
import com.galaxy.steward.ui.components.relativeTo

@Composable
fun DuplicatesScreen(vm: StewardViewModel, state: UiState, onBack: () -> Unit) {
    val report = state.report
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<List<PlanItem>?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    val files = remember(report, state.keepers) { report?.duplicates.orEmpty().map { vm.effective(it, state.keepers) as DuplicateGroup } }
    val folders = remember(report, state.keepers) { report?.folderDuplicates.orEmpty().map { vm.effective(it, state.keepers) as FolderDuplicateGroup } }
    val merges = report?.folderMerges.orEmpty()
    val selected = remember(files, folders, merges, state.selected) { (files + folders + merges).filter { it.id in state.selected } }
    val visibleFiles = remember(files, kindFilter) { files.filter { kindFilter == null || it.kind.name == kindFilter } }

    Scaffold(
        topBar = {
            ReviewTopBar("Duplicates", onBack) {
                val ids = when (tab) {
                    0 -> visibleFiles.map { it.id }
                    1 -> folders.map { it.id }
                    else -> merges.map { it.id }
                }
                TextButton(onClick = { vm.setSelected(ids, true) }) { Text("All") }
                TextButton(onClick = { vm.setSelected(ids, false) }) { Text("None") }
            }
        },
        bottomBar = {
            ActionBar(
                summary = "Frees ${selected.sumOf { it.reclaimBytes }.humanBytes()}",
                detail = "${selected.size.plural("group", "groups")} selected",
                action = "Clean",
                enabled = selected.isNotEmpty() && selected.any { it.operations.isNotEmpty() },
            ) { confirm = selected }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Files (${files.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Folders (${folders.size})") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Merges (${merges.size})") })
            }
            when (tab) {
                0 -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kindFilter == null, { kindFilter = null }, label = { Text("All") })
                        FileKind.entries.filter { k -> files.any { it.kind == k } }.forEach { k ->
                            FilterChip(kindFilter == k.name, { kindFilter = if (kindFilter == k.name) null else k.name }, label = { Text(k.label) })
                        }
                    }
                    if (visibleFiles.isEmpty()) {
                        EmptyState(Icons.Rounded.ContentCopy, "No duplicate files", "Every file above the size threshold is unique.")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                            item { InlineNotice("Each copy is re-hashed with SHA-256 right before removal. Tap a copy to keep it instead.") }
                            items(visibleFiles, key = { it.id }) { group ->
                                DuplicateGroupCard(group, group.id in state.selected, vm.rootPath, { vm.toggle(group.id) }) { vm.setKeeper(group.id, it) }
                            }
                        }
                    }
                }
                1 -> if (folders.isEmpty()) {
                    EmptyState(folderIcon, "No duplicate folders", "No two folders have identical names, sizes and content.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(folders, key = { it.id }) { group ->
                            FolderGroupCard(group, group.id in state.selected, vm.rootPath, { vm.toggle(group.id) }) { vm.setKeeper(group.id, it) }
                        }
                    }
                }
                else -> if (merges.isEmpty()) {
                    EmptyState(Icons.AutoMirrored.Rounded.MergeType, "No overlapping folders", "No folders share most of their content.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        item { InlineNotice("Merging moves unique files into the kept folder and removes the verified duplicates.") }
                        items(merges, key = { it.id }) { merge -> MergeCard(merge, merge.id in state.selected, vm.rootPath) { vm.toggle(merge.id) } }
                    }
                }
            }
        }
    }

    confirm?.let { items ->
        val copies = items.sumOf {
            when (it) {
                is DuplicateGroup -> it.removals.size
                is FolderDuplicateGroup -> it.removals.size
                else -> 0
            }
        }
        val merges2 = items.count { it is FolderMerge }
        val quarantine = settings.quarantineDuplicates
        ConfirmDialog(
            title = "Remove duplicates?",
            lines = listOfNotNull(
                copies.takeIf { it > 0 }?.let { "$it redundant copies ${if (quarantine) "moved to quarantine" else "deleted after SHA-256 re-verification"}" },
                merges2.takeIf { it > 0 }?.let { "${it.plural("folder merge", "folder merges")}" },
                "Frees ${items.sumOf { it.reclaimBytes }.humanBytes()}",
                "The kept copy of every file is never touched",
            ),
            confirmLabel = "Clean",
            onConfirm = {
                confirm = null
                vm.apply("Duplicate cleanup", "dedupe", items)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup, checked: Boolean, root: String, onToggle: () -> Unit, onKeep: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ReviewCard {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onToggle() }, enabled = group.removals.isNotEmpty())
            IconBadge(kindIcon(group.kind), kindColor(group.kind), size = 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(group.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    "${group.copies.size} copies · ${group.size.humanBytes()} each · frees ${group.reclaimBytes.humanBytes()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.keepReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                group.reviewNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                val removals = group.removals.map { it.path }.toSet()
                group.copies.forEachIndexed { index, copy ->
                    CopyRow(
                        path = copy.path.relativeTo(root),
                        zone = copy.zone,
                        role = when {
                            index == group.keeperIndex -> CopyRole.KEEP
                            copy.path in removals -> CopyRole.REMOVE
                            else -> CopyRole.PROTECTED
                        },
                    ) { onKeep(index) }
                }
            }
        }
    }
}

enum class CopyRole { KEEP, REMOVE, PROTECTED }

@Composable
fun CopyRow(path: String, zone: Zone, role: CopyRole, onClick: () -> Unit) {
    val (label, color) = when (role) {
        CopyRole.KEEP -> "KEEP" to MaterialTheme.colorScheme.primary
        CopyRole.REMOVE -> "REMOVE" to MaterialTheme.colorScheme.error
        CopyRole.PROTECTED -> "STAYS" to MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
        }
        Spacer(Modifier.width(6.dp))
        ZonePill(zone)
    }
}

@Composable
private fun FolderGroupCard(group: FolderDuplicateGroup, checked: Boolean, root: String, onToggle: () -> Unit, onKeep: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    ReviewCard {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onToggle() }, enabled = group.removals.isNotEmpty())
            IconBadge(folderIcon, MaterialTheme.colorScheme.primary, size = 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(group.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    "${group.copies.size} identical folders · ${group.fileCount} files · ${group.bytes.humanBytes()} each",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.keepReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                val removals = group.removals.map { it.path }.toSet()
                group.copies.forEachIndexed { index, copy ->
                    CopyRow(
                        copy.path.relativeTo(root), copy.zone,
                        when {
                            index == group.keeperIndex -> CopyRole.KEEP
                            copy.path in removals -> CopyRole.REMOVE
                            else -> CopyRole.PROTECTED
                        },
                    ) { onKeep(index) }
                }
            }
        }
    }
}

@Composable
private fun MergeCard(merge: FolderMerge, checked: Boolean, root: String, onToggle: () -> Unit) {
    ReviewCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked, { onToggle() })
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(merge.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Pill("${merge.overlapPercent}% shared", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Text("From  ${merge.source.relativeTo(root)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text("Into  ${merge.target.relativeTo(root)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    "Removes ${merge.commonFiles} duplicates (${merge.commonBytes.humanBytes()}) · moves ${merge.uniqueFiles} unique (${merge.uniqueBytes.humanBytes()})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
