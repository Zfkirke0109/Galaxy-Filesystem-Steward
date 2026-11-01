@file:OptIn(ExperimentalLayoutApi::class)

package com.galaxy.steward.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppFolderEntry
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.relativeTo

/**
 * Android/data, obb and media folder by folder, like a file manager: every entry with its total size, largest first.
 * Pick files or folders and remove them, into the quarantine (undoable) or for good.
 */
@Composable
fun AppFolderBrowserScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val browser by vm.apps.browser.collectAsStateWithLifecycle()
    val listing = browser.listing
    val pickable = if (listing == null || listing.protected) emptyList() else listing.entries.filter { it.locked == null }
    val picked = pickable.filter { it.path in browser.selected }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = browser.path != null) { vm.apps.browseUp() }

    val title = browser.path?.let { path ->
        val pkg = path.removePrefix("${vm.rootPath}/").split('/').getOrNull(2).orEmpty()
        vm.apps.label(pkg)
    } ?: "Browse app folders"

    Scaffold(
        topBar = {
            ReviewTopBar(title, { if (!vm.apps.browseUp()) onBack() }) {
                if (pickable.isNotEmpty()) {
                    val all = picked.size == pickable.size
                    TextButton(onClick = { vm.apps.setPicked(pickable.map { it.path }, !all) }) { Text(if (all) "None" else "All") }
                }
            }
        },
        bottomBar = {
            if (listing != null && pickable.isNotEmpty()) {
                ActionBar(
                    summary = "Removes ${picked.sumOf { it.bytes }.humanBytes()}",
                    detail = "${picked.size.plural("item", "items")} selected",
                    action = "Remove",
                    enabled = picked.isNotEmpty(),
                ) { confirm = true }
            }
        },
    ) { padding ->
        if (browser.path == null) {
            AppList(vm, Modifier.padding(padding))
        } else {
            FolderList(vm, Modifier.padding(padding))
        }
    }

    if (confirm && listing != null) {
        RemoveDialog(
            count = picked.size,
            bytes = picked.sumOf { it.bytes },
            app = vm.apps.label(listing.packageName),
            onConfirm = { quarantine ->
                confirm = false
                vm.removePicked(quarantine)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** The apps with a folder in the chosen area, largest first, from the last app folder scan. */
@Composable
private fun AppList(vm: StewardViewModel, modifier: Modifier) {
    val state by vm.apps.state.collectAsStateWithLifecycle()
    var area by rememberSaveable { mutableStateOf(AppArea.DATA) }
    val report = state.folders
    val rows = report?.usage.orEmpty().filter { it.area == area }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppArea.entries.forEach { a -> FilterChip(selected = a == area, onClick = { area = a }, label = { Text(a.label) }) }
            }
        }
        item {
            InlineNotice(
                "Open any app's folder to see what takes the space, then pick files or folders to remove. They go to the " +
                    "quarantine unless you choose to delete them for good. Offline music, downloads and game data live here too, " +
                    "so remove only what you know the app can do without.",
            )
        }
        when {
            state.foldersScanning -> item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Measuring app folders…", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            report == null -> item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Rounded.FolderOpen, "Measure app folders first", "A quick read-only scan finds every app's folder and its size.")
                    FilledTonalButton(onClick = { vm.apps.scanFolders() }) { Text("Scan app folders") }
                }
            }
            area !in report.areas -> item {
                InlineNotice("${area.label} can only be opened through Shizuku. Connect it on the Apps tab, then scan app folders again.")
            }
            rows.isEmpty() -> item { EmptyState(Icons.Rounded.FolderOpen, "No app folders", "No app keeps anything in ${area.label}.") }
        }
        if (report != null && area in report.areas) {
            items(rows, key = { it.packageName }) { u ->
                BrowserRow(
                    icon = if (u.protected) Icons.Rounded.Lock else Icons.Rounded.Folder,
                    title = vm.apps.label(u.packageName),
                    subtitle = listOfNotNull(
                        u.packageName,
                        u.files.plural("file"),
                        if (u.protected) "view only" else null,
                        if (!u.installed) "app not installed" else null,
                    ).joinToString(" · "),
                    bytes = u.bytes,
                    checked = null,
                    onCheck = {},
                ) { vm.apps.openFolder("${vm.rootPath}/Android/${u.area.dir}/${u.packageName}") }
            }
        }
    }
}

/** One folder's entries, with a box to pick each one that may be removed. */
@Composable
private fun FolderList(vm: StewardViewModel, modifier: Modifier) {
    val browser by vm.apps.browser.collectAsStateWithLifecycle()
    val listing = browser.listing
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                browser.path.orEmpty().relativeTo(vm.rootPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (browser.loading) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Measuring this folder…", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        browser.error?.let { error ->
            item { InlineNotice(error, error = true) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.apps.refreshBrowser() }) { Text("Try again") }
                    TextButton(onClick = { vm.apps.browseUp() }) { Text("Back") }
                }
            }
        }
        if (listing != null) {
            if (listing.protected) {
                item { InlineNotice("${vm.apps.label(listing.packageName)} is a protected offline library: you can look, but nothing here is removed.") }
            }
            if (listing.entries.isEmpty()) {
                item { EmptyState(Icons.Rounded.FolderOpen, "Empty folder", "Nothing is stored here.") }
            }
            items(listing.entries, key = { it.path }) { e ->
                val canPick = e.locked == null && !listing.protected
                BrowserRow(
                    icon = entryIcon(e),
                    title = e.name,
                    subtitle = describe(e),
                    bytes = e.bytes,
                    checked = if (canPick) e.path in browser.selected else null,
                    onCheck = { vm.apps.togglePick(e.path) },
                ) { if (e.isDirectory && e.locked != "link") vm.apps.openFolder(e.path) else if (canPick) vm.apps.togglePick(e.path) }
            }
            if (listing.hidden > 0) {
                item { InlineNotice("Showing the largest ${listing.entries.size}; ${listing.hidden} smaller entries are not listed.") }
            }
        }
    }
}

private fun entryIcon(e: AppFolderEntry) = when {
    e.locked == "link" -> Icons.Rounded.Link
    e.locked != null -> Icons.Rounded.Lock
    e.isDirectory -> Icons.Rounded.Folder
    else -> Icons.Rounded.Description
}

private fun describe(e: AppFolderEntry): String = listOfNotNull(
    if (e.isDirectory) e.files.plural("file") else null,
    if (e.partial) "partly unreadable" else null,
    e.locked?.let {
        when (it) {
            "link" -> "link - never followed"
            "key or credential" -> "looks like a key - kept"
            "protected app" -> null
            else -> it
        }
    },
).joinToString(" · ")

/** A row that opens on tap; [checked] null means it can't be picked. */
@Composable
internal fun BrowserRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    bytes: Long,
    checked: Boolean?,
    onCheck: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() })
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        SizeText(bytes)
    }
}

@Composable
private fun RemoveDialog(count: Int, bytes: Long, app: String, onConfirm: (quarantine: Boolean) -> Unit, onDismiss: () -> Unit) {
    var quarantine by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${count.plural("item", "items")}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${bytes.humanBytes()} from $app.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().clickable { quarantine = !quarantine }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep an undo copy in the quarantine", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = quarantine, onCheckedChange = { quarantine = it })
                }
                Text(
                    if (quarantine) {
                        "History can bring everything back. The space is freed when the quarantine is emptied."
                    } else {
                        "Deleted for good, like in a file manager. This can't be undone."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quarantine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Text(
                    "The app may lose downloads, saves or settings kept here. Files changed after you opened this folder, " +
                        "key-like files and links are left alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(quarantine) }) { Text(if (quarantine) "Remove" else "Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
