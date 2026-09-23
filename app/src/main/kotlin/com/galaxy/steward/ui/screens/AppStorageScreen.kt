package com.galaxy.steward.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.apps.AppStorage
import com.galaxy.steward.apps.AppStorageRow
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.shizuku.ShizukuStatus
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.SelectRow

private enum class AppSort(val label: String) { TOTAL("Largest"), DATA("App data"), CACHE("Cache") }

/**
 * Every app's storage split the way Android accounts it. App data (accounts, messages, offline downloads) is
 * shown so you can see where space goes and manage it in App info; only the cache is offered for clearing.
 */
@Composable
fun AppStorageScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.apps.state.collectAsStateWithLifecycle()
    val shizuku by vm.apps.shizuku.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(AppSort.TOTAL) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // Off by default: a force-stopped app receives no notifications until it is opened again.
    var stopFirst by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<AppStorageRow>?>(null) }
    val canClear = shizuku == ShizukuStatus.READY

    val rows = remember(state.apps, sort, showSystem) {
        state.apps.filter { showSystem || !it.system || it.cacheBytes >= 50L * 1024 * 1024 }.sortedByDescending {
            when (sort) {
                AppSort.TOTAL -> it.totalBytes
                AppSort.DATA -> it.dataBytes
                AppSort.CACHE -> it.cacheBytes
            }
        }
    }
    val selected = state.apps.filter { it.packageName in state.cacheSelected && !it.protected }

    Scaffold(
        topBar = {
            ReviewTopBar("App storage", onBack) {
                TextButton(onClick = { vm.apps.setCacheSelected(state.apps.map { it.packageName }, false) }) { Text("None") }
            }
        },
        bottomBar = {
            ActionBar(
                summary = "Frees about ${selected.sumOf { it.cacheBytes }.humanBytes()}",
                detail = if (canClear) "${selected.size.plural("app cache", "app caches")} selected" else "Connect Shizuku to clear caches here",
                action = "Clear",
                enabled = canClear && selected.isNotEmpty(),
            ) { confirm = selected }
        },
    ) { padding ->
        if (state.apps.isEmpty()) {
            EmptyState(
                Icons.Rounded.Apps,
                if (state.usageAccess) "Measuring apps…" else "Usage access needed",
                if (state.usageAccess) "This takes a few seconds." else "Grant usage access on the Apps tab to see each app's storage.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "App data holds accounts, messages, offline music and settings; clearing it signs you out, so it is only shown here. " +
                        "Tap ⓘ to manage an app in Android's App info. Cache is rebuilt by each app and is safe to clear.",
                )
            }
            if (!canClear) {
                item { InlineNotice("Without Shizuku, open an app's App info and tap Clear cache there. Connect Shizuku on the Apps tab to clear many at once.") }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppSort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            item {
                Column {
                    ToggleLine("Show system apps", showSystem) { showSystem = it }
                    if (canClear) {
                        ToggleLine("Stop each app first (more thorough; stopped apps stay silent until you open them)", stopFirst) { stopFirst = it }
                    }
                }
            }
            // Hundreds of apps: one lazy row each rather than one big card.
            items(rows, key = { it.packageName }) { row ->
                SelectRow(
                    checked = row.packageName in state.cacheSelected && !row.protected,
                    onCheckedChange = { vm.apps.toggleCache(row.packageName) },
                    enabled = canClear && !row.protected && row.cacheBytes > 0,
                    title = row.label,
                    subtitle = "App data ${row.dataBytes.humanBytes()} · cache ${row.cacheBytes.humanBytes()} · app ${row.appBytes.humanBytes()}",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (row.protected) Pill("Protected")
                            IconButton(onClick = { context.startActivity(AppStorage.appInfoIntent(row.packageName)) }) {
                                Icon(Icons.Outlined.Info, contentDescription = "App info for ${row.label}")
                            }
                        }
                    },
                )
            }
        }
    }

    confirm?.let { list ->
        ConfirmDialog(
            title = "Clear ${list.size.plural("app cache", "app caches")}?",
            lines = listOfNotNull(
                "Frees about ${list.sumOf { it.cacheBytes }.humanBytes()} - each app rebuilds what it needs",
                if (stopFirst) "Each app is stopped first and shows no notifications until you open it again (messengers and mail are never stopped)" else null,
                "App data, accounts, downloads and settings are not touched",
            ),
            confirmLabel = "Clear",
            footnote = "Cache clears are permanent. Only a verified drop in each app's cache counts. Amazon Music and Audible are never touched.",
            onConfirm = {
                confirm = null
                vm.clearAppCaches(list.map { it.packageName }, stopFirst)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ToggleLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
