@file:OptIn(ExperimentalLayoutApi::class)

package com.galaxy.steward.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.galaxy.steward.BuildConfig
import com.galaxy.steward.core.SafetyPolicy
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.organize.KeywordRule
import com.galaxy.steward.data.SettingsStore
import com.galaxy.steward.diagnostics.LogcatExporter
import com.galaxy.steward.shizuku.ShizukuStatus
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.SectionHeader

@Composable
fun SettingsScreen(vm: StewardViewModel, state: UiState) {
    val settings by vm.settings.collectAsState()
    val prefs by vm.preferences.collectAsState()
    val shizuku by vm.apps.shizuku.status.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.apps.shizuku.refresh() }
    var ruleDialog by remember { mutableStateOf(false) }
    var protectDialog by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.setWeeklyAudit(true)
    }

    Scaffold(topBar = { ReviewTopBar("Settings", null) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SectionHeader("Duplicates") }
            item {
                ChoiceRow(
                    "Ignore files smaller than",
                    SettingsStore.DUPLICATE_SIZE_CHOICES.map { it.humanBytes() to it },
                    settings.minDuplicateBytes,
                ) { v -> vm.updateSettings { it.copy(minDuplicateBytes = v) } }
            }
            item {
                SwitchRow("Quarantine instead of delete", "Keep removed duplicates recoverable until the quarantine is emptied.", settings.quarantineDuplicates) { v ->
                    vm.updateSettings { it.copy(quarantineDuplicates = v) }
                }
            }
            item {
                SwitchRow("Include hidden files", "Also compare dot-files and files inside hidden folders.", settings.includeHiddenInDuplicates) { v ->
                    vm.updateSettings { it.copy(includeHiddenInDuplicates = v) }
                }
            }

            item { SectionHeader("Clutter & quarantine") }
            item {
                ChoiceRow("Empty quarantine after", listOf("3 days" to 3, "7 days" to 7, "14 days" to 14, "30 days" to 30, "Never" to 0), settings.quarantineRetentionDays) { v ->
                    vm.updateSettings { it.copy(quarantineRetentionDays = v) }
                }
            }
            item {
                ChoiceRow("Unfinished downloads are stale after", listOf("7 days" to 7, "14 days" to 14, "30 days" to 30), settings.staleTempDays) { v ->
                    vm.updateSettings { it.copy(staleTempDays = v) }
                }
            }
            item {
                ChoiceRow("System logs are old after", listOf("7 days" to 7, "14 days" to 14, "30 days" to 30), settings.oldLogDays) { v ->
                    vm.updateSettings { it.copy(oldLogDays = v) }
                }
            }

            item { SectionHeader("Organize & optimize") }
            item {
                SwitchRow("Year folders for imported media", "File downloaded photos and videos under Imported/<year>.", settings.mediaYearBuckets) { v ->
                    vm.updateSettings { it.copy(mediaYearBuckets = v) }
                }
            }
            item {
                ChoiceRow("Leave files alone if changed within", listOf("5 min" to 5, "10 min" to 10, "1 hour" to 60, "1 day" to 1440), settings.recentFileGuardMinutes) { v ->
                    vm.updateSettings { it.copy(recentFileGuardMinutes = v) }
                }
            }
            item {
                ChoiceRow("Suggest date buckets above", listOf("500" to 500, "1000" to 1000, "2000" to 2000, "5000" to 5000), settings.flatDirThreshold) { v ->
                    vm.updateSettings { it.copy(flatDirThreshold = v) }
                }
            }

            item {
                SectionHeader("Custom filing rules") {
                    IconButton(onClick = { ruleDialog = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add rule") }
                }
            }
            if (settings.customRules.isEmpty()) {
                item { Hint("Add rules like \"acme, invoice → Documents/Work/ACME\". Your rules run before the built-in ones.") }
            }
            items(settings.customRules, key = { it.encode() }) { rule ->
                RemovableRow(
                    title = "${rule.name} → ${rule.destination}",
                    subtitle = listOfNotNull(
                        rule.keywords.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Words: "),
                        rule.extensions.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Types: ") { ".$it" },
                    ).joinToString(" · "),
                ) { vm.updateSettings { s -> s.copy(customRules = s.customRules - rule) } }
            }

            item {
                SectionHeader("Pinned folders") {
                    IconButton(onClick = { protectDialog = true }) { Icon(Icons.Rounded.Add, contentDescription = "Pin folder") }
                }
            }
            item {
                Hint(
                    "Always protected: Android/data, obb and media, projects and Git repositories, keys and credentials, the " +
                        "steward's own quarantine, and saved logcat exports. Pin more folders to keep them exactly as they are.",
                )
            }
            items(settings.protectedFolders, key = { it }) { folder ->
                RemovableRow(folder, null) { vm.updateSettings { s -> s.copy(protectedFolders = s.protectedFolders - folder) } }
            }

            item { SectionHeader("Automation") }
            item {
                SwitchRow("Weekly audit", "A read-only survey while charging, with a notification if there is something to reclaim.", prefs.weeklyAudit) { v ->
                    if (v && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setWeeklyAudit(v)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { SwitchRow("Match wallpaper colours", "Use Material You dynamic colour.", prefs.dynamicColor) { vm.setDynamicColor(it) } }
            }

            item { SectionHeader("Diagnostics") }
            item { LogcatExportRow(vm.logcat, wholeDevice = shizuku == ShizukuStatus.READY) }

            item { SectionHeader("About") }
            item {
                Hint(
                    "Galaxy Steward ${BuildConfig.VERSION_NAME} · Koa engine, ported from the Koa Whole-Device Storage Steward v18.2 for Termux. " +
                        "Scans are read-only; every change is SHA-256 verified, journaled and undoable. Device label: ${vm.deviceLabel}.",
                )
            }
        }
    }

    if (ruleDialog) {
        RuleDialog(onDismiss = { ruleDialog = false }) { rule ->
            ruleDialog = false
            vm.updateSettings { it.copy(customRules = (it.customRules + rule).distinctBy { r -> r.encode() }) }
        }
    }
    if (protectDialog) {
        val suggestions = state.report?.tree?.root?.dirs
            ?.filter { !it.hidden && it.name != "Android" && it.name != SafetyPolicy.STEWARD_DIR }
            ?.flatMap { top -> listOf(top.name) + top.dirs.filter { !it.hidden }.take(6).map { "${top.name}/${it.name}" } }
            .orEmpty()
        ProtectDialog(suggestions, onDismiss = { protectDialog = false }) { folder ->
            protectDialog = false
            vm.updateSettings { it.copy(protectedFolders = (it.protectedFolders + folder).distinct()) }
        }
    }
}

/** Saves the log to Documents/Galaxy Steward LogCat and offers to share it. */
@Composable
private fun LogcatExportRow(exporter: LogcatExporter, wholeDevice: Boolean) {
    val state by exporter.state.collectAsState()
    val context = LocalContext.current
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Export logcat", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (wholeDevice) {
                        "Saves the whole device log to ${SafetyPolicy.LOGCAT_DIR}."
                    } else {
                        "Saves Galaxy Steward's own log to ${SafetyPolicy.LOGCAT_DIR}. Connect Shizuku on the Apps tab to save the whole device log."
                    },
                    style = small,
                    color = muted,
                )
            }
            FilledTonalButton(onClick = exporter::export, enabled = !state.running) { Text(if (state.running) "Saving…" else "Export") }
        }
        if (state.running) {
            Text(if (state.bytesWritten > 0) "${state.bytesWritten.humanBytes()} saved so far…" else "Reading the log…", style = small, color = muted)
        }
        state.saved?.let { saved ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Saved ${saved.file.name} (${saved.bytes.humanBytes()}, ${if (saved.wholeDevice) "whole device" else "own log only"})",
                    Modifier.weight(1f),
                    style = small,
                )
                TextButton(onClick = { context.startActivity(exporter.shareIntent(saved.file)) }) { Text("Share") }
            }
            // Shizuku was connected but couldn't read the log: say why.
            if (wholeDevice && !saved.wholeDevice && saved.note != null) Text(saved.note, style = small, color = MaterialTheme.colorScheme.error)
        }
        state.error?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
        Text(
            "Android keeps only the latest part of the log, so export right after something goes wrong (Developer options > " +
                "Logger buffer sizes keeps more). The whole device log can include names and file paths from other apps, so " +
                "share it only with people you trust.",
            style = small,
            color = muted,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, choices: List<Pair<String, T>>, current: T, onChoose: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (label, value) ->
                FilterChip(selected = value == current, onClick = { onChoose(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun RemovableRow(title: String, subtitle: String?, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove") }
    }
}

private fun validDestination(dest: String): Boolean {
    val d = dest.trim().trim('/')
    if (d.isEmpty() || SafetyPolicy.isUnsafeName(d)) return false
    val parts = d.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
    return parts.first() != "Android" && parts.first() != SafetyPolicy.STEWARD_DIR
}

@Composable
private fun RuleDialog(onDismiss: () -> Unit, onSave: (KeywordRule) -> Unit) {
    var name by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var extensions by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("Documents/") }
    val words = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val exts = extensions.split(',').map { it.trim().removePrefix(".").lowercase() }.filter { it.isNotEmpty() }.toSet()
    val valid = name.isNotBlank() && (words.isNotEmpty() || exts.isNotEmpty()) && validDestination(destination)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New filing rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Words in the name (comma separated, * for prefix)") })
                OutlinedTextField(extensions, { extensions = it }, label = { Text("File types, optional (pdf, jpg)") }, singleLine = true)
                OutlinedTextField(destination, { destination = it }, label = { Text("Destination folder") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(KeywordRule(name.trim(), words, destination.trim().trim('/'), exts)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProtectDialog(suggestions: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var folder by remember { mutableStateOf("") }
    val valid = validDestination(folder)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin a folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder, e.g. Documents/Taxes") }, singleLine = true)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.take(24).forEach { s -> FilterChip(selected = folder == s, onClick = { folder = s }, label = { Text(s) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(folder.trim().trim('/')) }) { Text("Pin") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
