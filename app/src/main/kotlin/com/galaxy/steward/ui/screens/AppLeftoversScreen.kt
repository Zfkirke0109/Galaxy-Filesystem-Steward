package com.galaxy.steward.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.ageText
import com.galaxy.steward.core.footprint.FootprintPlace
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.shizuku.ShizukuStatus
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.IrreversibleDialog
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow

/**
 * Everything an app left behind: pick an app (one you removed, like WoWee, or one still installed) or type a word, and
 * see every folder and file named after it in shared storage, Android/data|obb|media, Termux and what adb left, from
 * the last scans. One confirmed run removes what you pick, each place its own checked way.
 */
@Composable
fun AppLeftoversScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.footprint.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val apps by vm.apps.state.collectAsStateWithLifecycle()
    val termux by vm.termux.state.collectAsStateWithLifecycle()
    val deep by vm.deepSpace.state.collectAsStateWithLifecycle()
    val shizuku by vm.apps.shizuku.status.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        // What each place knows comes from its last scan; start the quick ones that haven't run.
        if (shizuku == ShizukuStatus.READY) {
            if (apps.folders == null && !apps.foldersScanning) vm.apps.scanFolders()
            if (deep.disk == null && !deep.loading) vm.deepSpace.load()
        }
        vm.footprint.suggest()
    }
    val target = state.target
    val picked = state.picked

    Scaffold(
        topBar = {
            ReviewTopBar(target?.label ?: "What an app left behind", { if (target != null) vm.footprint.close() else onBack() })
        },
        bottomBar = {
            if (target != null) {
                ActionBar(
                    summary = "Frees about ${picked.sumOf { it.bytes }.humanBytes()}",
                    detail = "${picked.size.plural("item")} picked",
                    action = "Remove",
                    enabled = picked.isNotEmpty() && ui.applying == null,
                ) { confirm = true }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (target == null) {
                item {
                    InlineNotice(
                        "Uninstalling an app leaves what it put elsewhere: folders and backups in shared storage, its installer, " +
                            "files in Termux, and what was pushed with adb. Pick an app, or type a name, to see all of it in one place.",
                    )
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        placeholder = { Text("App name, package or word (WoWee)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.footprint.find(query) }),
                        trailingIcon = { TextButton(onClick = { vm.footprint.find(query) }, enabled = query.isNotBlank()) { Text("Find") } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.suggestions.isNotEmpty()) {
                    item { SectionHeader("Removed apps that left something") }
                    items(state.suggestions, key = { it.key }) { app ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                            AssistChip(
                                onClick = { vm.footprint.open(app) },
                                label = { Text("${app.label} · ${app.packageName ?: ""} · ${app.why}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                item { Sources(ui.report != null, apps.folders != null, termux.report != null, shizuku == ShizukuStatus.READY) }
            } else {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOfNotNull(target.packageName, if (target.installed) "installed" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (state.tokens.isEmpty()) "That name is too common to tell whose a folder is: type a more particular word."
                            else "Names containing " + state.tokens.joinToString(" or ") { "\"$it\"" } + ", ignoring case and punctuation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (target.installed) {
                            Text(
                                "${target.label} is still installed: nothing is picked for you. Uninstall it first to clear what it keeps privately.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (state.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                if (!state.searching && state.hits.isEmpty()) {
                    item { Text("Nothing named after it in the last scans.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
                }
                for (place in FootprintPlace.entries) {
                    val list = state.hits.filter { it.place == place }
                    if (list.isEmpty()) continue
                    item(key = "h:" + place.name) {
                        SectionHeader("${place.title} · ${list.sumOf { it.bytes }.humanBytes()}")
                    }
                    item(key = "n:" + place.name) {
                        Text(place.how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(list, key = { state.key(it) }) { h ->
                        val shown = when (place) {
                            FootprintPlace.TERMUX -> termux.report?.let { TermuxProtocol.relative(h.path, it.home, it.prefix) } ?: h.path
                            FootprintPlace.SHARED, FootprintPlace.APP_FOLDERS -> h.path.removePrefix(com.galaxy.steward.data.StorageAccess.rootPath + "/")
                            FootprintPlace.SHELL -> h.path
                        }
                        SelectRow(
                            checked = state.key(h) in state.selected && h.lock == null,
                            onCheckedChange = { vm.footprint.toggle(h) },
                            enabled = h.lock == null,
                            title = h.name,
                            subtitle = listOfNotNull(
                                shown,
                                h.files.takeIf { it > 1 }?.plural("file"),
                                h.mtime.takeIf { it > 0 }?.let { "changed ${ageText(it, now)}" },
                            ).joinToString(" · ") + (h.lock?.let { "\n$it" } ?: ""),
                            subtitleLines = 3,
                            trailing = { SizeText(h.bytes) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                item { Sources(ui.report != null, apps.folders != null, termux.report != null, shizuku == ShizukuStatus.READY) }
            }
        }
    }

    if (confirm && target != null) {
        val places = picked.groupBy { it.place }
        IrreversibleDialog(
            title = "Remove ${picked.size.plural("item")} ${target.label} left?",
            names = picked.map { it.name },
            lines = buildList {
                add("Frees about ${picked.sumOf { it.bytes }.humanBytes()}")
                places.forEach { (place, list) -> add("${place.title}: ${list.size.plural("item")}. ${place.how}") }
            },
            confirmLabel = "Remove",
            onConfirm = {
                confirm = false
                vm.removeFootprint(target.label, picked)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Which scans the search used, and what to run to include the rest. */
@Composable
private fun Sources(shared: Boolean, folders: Boolean, termux: Boolean, shizuku: Boolean) {
    val missing = listOfNotNull(
        "scan shared storage on Home".takeIf { !shared },
        "connect Shizuku for Android/data, obb and what adb left".takeIf { !shizuku },
        "scan Termux on the Termux screen".takeIf { !termux },
    )
    val text = if (missing.isEmpty()) "Looked in shared storage, Android/data, obb and media, Termux and what adb left, as the last scans saw them."
    else "To look everywhere: " + missing.joinToString("; ") + "."
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { InlineNotice(text) }
}
