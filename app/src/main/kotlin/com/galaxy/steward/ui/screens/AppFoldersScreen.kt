package com.galaxy.steward.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.GroupHeader
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow
import com.galaxy.steward.ui.components.relativeTo
import com.galaxy.steward.ui.components.toggleState

/** Review of Android/data, obb and media: caches, logs, temp files, outdated OBBs and leftovers of removed apps. */
@Composable
fun AppFoldersScreen(vm: StewardViewModel, onBrowse: () -> Unit, onBack: () -> Unit) {
    val state by vm.apps.state.collectAsStateWithLifecycle()
    val report = state.folders
    val items = report?.items.orEmpty()
    val byKind = remember(items) { items.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal }) }
    val selected = items.filter { it.id in state.folderSelected }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<List<AppJunkItem>?>(null) }

    Scaffold(
        topBar = {
            ReviewTopBar("App folders", onBack) {
                TextButton(onClick = onBrowse) { Text("Browse") }
                TextButton(onClick = { vm.apps.setFolderSelected(items.map { it.id }, false) }) { Text("None") }
            }
        },
        bottomBar = {
            if (items.isNotEmpty()) {
                ActionBar(
                    summary = "Frees ${selected.sumOf { it.bytes }.humanBytes()}",
                    detail = "${selected.size.plural("item", "items")} selected",
                    action = "Clean",
                    enabled = selected.isNotEmpty() && !state.foldersScanning,
                ) { confirm = selected }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "Caches, logs and temp files are removed for good; apps rebuild them. Outdated game data and leftovers of " +
                        "removed apps go to the quarantine, so History can bring them back. Offline music, downloads, saves and " +
                        "databases are never touched.",
                )
            }
            if (state.foldersScanning) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Scanning app folders…", style = MaterialTheme.typography.titleSmall)
                        state.foldersProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            state.foldersError?.let { item { InlineNotice(it, error = true) } }
            if (report != null && report.areas == setOf(AppArea.MEDIA)) {
                item { InlineNotice("Only Android/media could be read. Connect Shizuku on the Apps tab to include Android/data and Android/obb.") }
            }
            if (report != null && items.isEmpty() && !state.foldersScanning) {
                item { EmptyState(Icons.Rounded.FolderSpecial, "Nothing to clean", "App folders hold no stale caches, logs, temp files or leftovers.") }
            }
            for ((kind, group) in byKind) {
                item(key = kind.name) {
                    val isOpen = kind.name in expanded
                    ReviewCard {
                        Column {
                            GroupHeader(
                                modifier = Modifier.clickable { expanded = if (isOpen) expanded - kind.name else expanded + kind.name },
                                title = kind.title,
                                subtitle = group.size.plural("app") + " · " + group.sumOf { it.bytes }.humanBytes() + "\n" + kind.description +
                                    if (kind.undoable) " Undoable." else "",
                                state = toggleState(group.count { it.id in state.folderSelected }, group.size),
                                onToggle = {
                                    val all = group.all { it.id in state.folderSelected }
                                    vm.apps.setFolderSelected(group.map { it.id }, !all)
                                },
                            ) {
                                IconButton(onClick = { expanded = if (isOpen) expanded - kind.name else expanded + kind.name }) {
                                    Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                                }
                            }
                            if (isOpen) {
                                group.take(300).forEach { item ->
                                    SelectRow(
                                        checked = item.id in state.folderSelected,
                                        onCheckedChange = { vm.apps.toggleFolder(item.id) },
                                        title = vm.apps.label(item.packageName),
                                        subtitle = item.area.label + " · " + item.note,
                                        trailing = { SizeText(item.bytes) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val large = report?.largeFiles.orEmpty()
            if (large.isNotEmpty()) {
                item { SectionHeader("Largest files in app folders") }
                item { InlineNotice("These belong to their apps (offline media, game data, models). To remove one you're sure the app can do without, open Browse.") }
                items(large.take(20)) { f ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text("${vm.apps.label(f.packageName)} · ${f.size.humanBytes()}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            f.path.relativeTo(vm.rootPath),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
            }
            val protected = report?.usage.orEmpty().filter { it.protected && it.bytes > 0 }
            if (protected.isNotEmpty()) {
                item { SectionHeader("Never touched") }
                items(protected) { u ->
                    SelectRow(
                        checked = false,
                        onCheckedChange = {},
                        enabled = false,
                        title = vm.apps.label(u.packageName),
                        subtitle = "${u.area.label} · ${u.bytes.humanBytes()} - protected offline library",
                        leading = { IconBadge(Icons.Rounded.Lock, MaterialTheme.colorScheme.tertiary, size = 28) },
                    )
                }
            }
        }
    }

    confirm?.let { list ->
        val permanent = list.filterNot { it.kind.undoable }
        val undoable = list.filter { it.kind.undoable }
        ConfirmDialog(
            title = "Clean app folders?",
            lines = listOfNotNull(
                permanent.takeIf { it.isNotEmpty() }?.let {
                    "${it.sumOf { i -> i.fileCount }.plural("cache, log or temp file", "cache, log and temp files")} removed for good (${it.sumOf { i -> i.bytes }.humanBytes()})"
                },
                undoable.takeIf { it.isNotEmpty() }?.let {
                    "${it.size.plural("outdated OBB or leftover folder", "outdated OBBs and leftover folders")} quarantined (${it.sumOf { i -> i.bytes }.humanBytes()})"
                },
                if (list.any { it.kind == AppJunkKind.LEFTOVERS }) "Leftovers are only moved if their app is still not installed" else null,
            ),
            confirmLabel = "Clean",
            footnote = "Each file is re-checked first (path, size, date, app). Files changed in the last minutes are left alone.",
            onConfirm = {
                confirm = null
                vm.applyAppFolders(list)
            },
            onDismiss = { confirm = null },
        )
    }
}
