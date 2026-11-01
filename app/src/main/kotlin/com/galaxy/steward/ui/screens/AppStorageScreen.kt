package com.galaxy.steward.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import com.galaxy.steward.core.ageText
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
import com.galaxy.steward.ui.components.UsageBar

private enum class AppSort(val label: String) { TOTAL("Largest"), DATA("App data"), CACHE("Cache"), UNUSED("Unused longest") }

/** What the check boxes pick apps for. */
private enum class ClearMode(val label: String) { CACHE("Clear cache"), DATA("Clear all data") }

/**
 * Every app's storage split the way Android accounts it, with when each app was last used. Caches can be cleared
 * for many apps at once. "Clear all data" resets the apps you pick, like Android's Clear storage button; it is never
 * preselected, never offered for apps whose data may be irreplaceable, and always asks first.
 */
@Composable
fun AppStorageScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.apps.state.collectAsStateWithLifecycle()
    val shizuku by vm.apps.shizuku.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(AppSort.TOTAL) }
    var mode by rememberSaveable { mutableStateOf(ClearMode.CACHE) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // Off by default: a force-stopped app receives no notifications until it is opened again.
    var stopFirst by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<AppStorageRow>?>(null) }
    var confirmData by remember { mutableStateOf<List<AppStorageRow>?>(null) }
    val canClear = shizuku == ShizukuStatus.READY
    val dataMode = mode == ClearMode.DATA
    // Android 17 ignores cache clears from Shizuku; the cache mode then only points to what still works.
    val canClearCache = canClear && AppStorage.shellCanClearCaches
    val now = remember(state.apps) { System.currentTimeMillis() }

    val rows = remember(state.apps, sort, showSystem) {
        val shown = state.apps.filter { showSystem || !it.system || it.cacheBytes >= 50L * 1024 * 1024 }
        when (sort) {
            // No recorded use at all first: Android keeps two years of usage history.
            AppSort.UNUSED -> shown.sortedWith(compareBy<AppStorageRow> { it.lastUsed ?: 0L }.thenByDescending { it.totalBytes })
            AppSort.TOTAL -> shown.sortedByDescending { it.totalBytes }
            AppSort.DATA -> shown.sortedByDescending { it.dataBytes }
            AppSort.CACHE -> shown.sortedByDescending { it.cacheBytes }
        }
    }
    val selected = state.apps.filter { it.packageName in state.cacheSelected && !it.protected }
    val dataSelected = state.apps.filter { it.packageName in state.dataSelected && it.clearBlock == null }

    Scaffold(
        topBar = {
            ReviewTopBar("App storage", onBack) {
                TextButton(
                    onClick = { if (dataMode) vm.apps.clearDataSelection() else vm.apps.setCacheSelected(state.apps.map { it.packageName }, false) },
                ) { Text("None") }
            }
        },
        bottomBar = {
            if (dataMode) {
                ActionBar(
                    summary = "Frees about ${dataSelected.sumOf { it.clearableBytes }.humanBytes()}",
                    detail = if (canClear) "${dataSelected.size.plural("app")} to reset" else "Connect Shizuku to clear app data here",
                    action = "Clear data",
                    enabled = canClear && dataSelected.isNotEmpty(),
                ) { confirmData = dataSelected }
            } else {
                ActionBar(
                    summary = "Frees about ${selected.sumOf { it.cacheBytes }.humanBytes()}",
                    detail = when {
                        !AppStorage.shellCanClearCaches -> "Android ${Build.VERSION.RELEASE} blocks cache clears from other apps"
                        canClear -> "${selected.size.plural("app cache", "app caches")} selected"
                        else -> "Connect Shizuku to clear caches here"
                    },
                    action = "Clear",
                    enabled = canClearCache && selected.isNotEmpty(),
                ) { confirm = selected }
            }
        },
    ) { padding ->
        if (state.apps.isEmpty()) {
            EmptyState(
                Icons.Rounded.Apps,
                if (state.usageAccess) "Measuring apps…" else "Usage access needed",
                if (state.usageAccess) "Each app shows up as soon as it is measured." else "Grant usage access on the Apps tab to see each app's storage.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            if (state.statsLoading && state.statsTotal > 0) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Measuring apps… ${state.statsDone} of ${state.statsTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UsageBar(state.statsDone.toFloat() / state.statsTotal, height = 4)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClearMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            item {
                if (dataMode) {
                    InlineNotice(
                        "Clear all data resets an app to how it was when installed, like Android's Clear storage button: sign-ins, " +
                            "settings, saved games and downloads it keeps on this phone, including its Android/data folder, are deleted " +
                            "for good. The app stays installed. Messengers and mail, authenticators and wallets, Termux, Shizuku and " +
                            "system apps are never offered.",
                        error = true,
                    )
                } else {
                    InlineNotice(
                        "Cache is rebuilt by each app and is safe to clear. App data holds accounts, messages, saved games and offline " +
                            "downloads: to reset an app completely, use Clear all data. Tap ⓘ to open an app's App info.",
                    )
                }
            }
            if (!dataMode && !AppStorage.shellCanClearCaches) {
                item {
                    InlineNotice(
                        "Android ${Build.VERSION.RELEASE} no longer lets Shizuku clear another app's cache: the request is ignored " +
                            "without an error. Clear an app's cache in its App info (ⓘ → Storage → Clear cache). Caches inside " +
                            "Android/data can still be cleaned on the Apps tab under App folders.",
                        error = true,
                    )
                }
            } else if (!canClear) {
                item {
                    InlineNotice(
                        if (dataMode) {
                            "Without Shizuku, open an app's App info > Storage and tap Clear storage there. Connect Shizuku on the Apps tab to clear it here."
                        } else {
                            "Without Shizuku, open an app's App info and tap Clear cache there. Connect Shizuku on the Apps tab to clear many at once."
                        },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppSort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            item {
                Column {
                    ToggleLine("Show system apps", showSystem) { showSystem = it }
                    if (canClearCache && !dataMode) {
                        ToggleLine("Stop each app first (more thorough; stopped apps stay silent until you open them)", stopFirst) { stopFirst = it }
                    }
                }
            }
            // Hundreds of apps: one lazy row each rather than one big card.
            items(rows, key = { it.packageName }) { row ->
                SelectRow(
                    checked = if (dataMode) row.packageName in state.dataSelected && row.clearBlock == null else row.packageName in state.cacheSelected && !row.protected,
                    onCheckedChange = { if (dataMode) vm.apps.toggleData(row.packageName) else vm.apps.toggleCache(row.packageName) },
                    enabled = if (dataMode) canClear && row.clearBlock == null && row.clearableBytes > 0 else canClearCache && !row.protected && row.cacheBytes > 0,
                    title = row.label,
                    subtitle = "App data ${row.dataBytes.humanBytes()} · cache ${row.cacheBytes.humanBytes()} · app ${row.appBytes.humanBytes()} · " +
                        (row.lastUsed?.let { "used ${ageText(it, now)}" } ?: "no use recorded"),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val pill = if (dataMode) row.clearBlock else if (row.protected) "Protected" else null
                            if (pill != null) Pill(pill)
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

    confirmData?.let { list ->
        ClearDataDialog(
            apps = list,
            onConfirm = {
                confirmData = null
                vm.clearAppData(list.map { it.packageName })
            },
            onDismiss = { confirmData = null },
        )
    }
}

/** The one place the steward deletes app data with no undo, so it names the apps and waits for an explicit "I understand". */
@Composable
internal fun ClearDataDialog(apps: List<AppStorageRow>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    val names = apps.sortedByDescending { it.clearableBytes }.map { it.label }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Clear all data of ${apps.size.plural("app")}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (names.size <= 4) names.joinToString() else names.take(3).joinToString() + " and ${names.size - 3} more",
                    style = MaterialTheme.typography.titleSmall,
                )
                listOf(
                    "Frees about ${apps.sumOf { it.clearableBytes }.humanBytes()}",
                    "Each app goes back to how it was when installed: sign-ins, settings, saved games and downloads are deleted, " +
                        "including everything in its Android/data folder",
                    "Each app is stopped and asks for its permissions again",
                    "There is no undo: anything not backed up elsewhere is gone for good",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Row(
                    Modifier.fillMaxWidth().clickable { understood = !understood },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand this can't be undone", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = understood,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Clear data") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
