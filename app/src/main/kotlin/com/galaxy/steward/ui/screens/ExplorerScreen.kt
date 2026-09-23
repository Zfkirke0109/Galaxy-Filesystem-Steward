@file:OptIn(ExperimentalMaterial3Api::class)

package com.galaxy.steward.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.UsageBar
import com.galaxy.steward.ui.components.ZonePill
import com.galaxy.steward.ui.components.folderIcon
import com.galaxy.steward.ui.components.kindColor
import com.galaxy.steward.ui.components.kindIcon
import com.galaxy.steward.ui.components.relativeTo
import com.galaxy.steward.ui.components.rememberScanStarter
import java.text.DateFormat
import java.util.Date

@Composable
fun ExplorerScreen(vm: StewardViewModel, state: UiState) {
    val report = state.report
    var relPath by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val goUp = { relPath = relPath.substringBeforeLast('/', "") }
    BackHandler(enabled = relPath.isNotEmpty()) { goUp() }
    val startScan = rememberScanStarter(vm)

    Scaffold(topBar = { ReviewTopBar("Storage map", if (relPath.isNotEmpty()) goUp else null) }) { padding ->
        if (report == null) {
            Column(Modifier.padding(padding).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(Icons.Rounded.Explore, "No map yet", "Run a smart scan to see which folders and files use your space.")
                Button(onClick = startScan, enabled = !state.scanning) { Text(if (state.scanning) "Scanning…" else "Start smart scan") }
            }
            return@Scaffold
        }
        val dir = report.tree.find(relPath) ?: report.tree.root
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(relPath) { relPath = it }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Folders") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Largest files") })
            }
            if (tab == 0) {
                FolderList(dir, onOpen = { relPath = it.relPath })
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(report.summary.largestFiles, key = { it.path }) { f ->
                        val kind = FileKind.of(f.path.substringAfterLast('/'))
                        FileRow(f.path.substringAfterLast('/'), f.path.substringBeforeLast('/').relativeTo(vm.rootPath), f.size, kind)
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(relPath: String, onNavigate: (String) -> Unit) {
    val parts = if (relPath.isEmpty()) emptyList() else relPath.split('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AssistChip(onClick = { onNavigate("") }, label = { Text("Storage") })
        parts.forEachIndexed { i, part ->
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            AssistChip(onClick = { onNavigate(parts.take(i + 1).joinToString("/")) }, label = { Text(part, maxLines = 1) })
        }
    }
}

@Composable
private fun FolderList(dir: DirNode, onOpen: (DirNode) -> Unit) {
    val dirs = remember(dir) { dir.dirs.sortedByDescending { it.totalBytes } }
    val files = remember(dir) { dir.files.sortedByDescending { it.size } }
    val total = dir.totalBytes.coerceAtLeast(1)
    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
        item {
            ReviewCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (dir.isRoot) "Internal storage" else dir.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (!dir.isRoot) ZonePill(dir.zone)
                    }
                    Text(
                        "${dir.totalBytes.humanBytes()} · ${dir.totalFiles} files · ${dir.dirs.size} folders (as of last scan)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!dir.isRoot) Text(dir.zone.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dir.hasFlag(NodeFlags.UNREADABLE)) {
                        Text("Android does not allow reading this folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (dir.hasFlag(NodeFlags.PROJECT_ROOT)) {
                        Text("Project root - always left exactly where it is.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        items(dirs, key = { "d:" + it.name }) { child ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(child) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(folderIcon, if (child.hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, size = 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(child.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(child.totalBytes.humanBytes(), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    UsageBar(child.totalBytes.toFloat() / total, height = 5)
                    Text("${child.totalFiles} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(files.take(300), key = { "f:" + it.name }) { f ->
            FileRow(f.name, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.mtime)), f.size, f.kind)
        }
        if (files.size > 300) {
            item { Text("…and ${files.size - 300} smaller files", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun FileRow(name: String, subtitle: String, size: Long, kind: FileKind) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(kindIcon(kind), kindColor(kind), size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(size.humanBytes(), style = MaterialTheme.typography.labelLarge)
    }
}
