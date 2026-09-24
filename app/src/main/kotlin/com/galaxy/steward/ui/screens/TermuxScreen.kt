package com.galaxy.steward.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.termux.TermuxGroup
import com.galaxy.steward.core.termux.TermuxItem
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.core.termux.TermuxRootfs
import com.galaxy.steward.termux.TermuxBridge
import com.galaxy.steward.termux.TermuxStatus
import com.galaxy.steward.ui.Routes
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.GroupHeader
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.IrreversibleDialog
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow
import com.galaxy.steward.ui.components.toggleState

/** Termux's private home: package caches, developer caches, proot distros and build outputs. */
@Composable
fun TermuxScreen(vm: StewardViewModel, navigate: (String) -> Unit, onBack: () -> Unit) {
    val state by vm.termux.state.collectAsStateWithLifecycle()
    val report = state.report
    val items = report?.items.orEmpty()
    val groups = remember(items) { items.groupBy { it.group }.toSortedMap(compareBy { it.ordinal }) }
    val selected = items.filter { it.spec in state.selected }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<List<TermuxItem>?>(null) }
    var removeDistro by remember { mutableStateOf<TermuxRootfs?>(null) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.termux.refresh() }

    LifecycleResumeEffect(Unit) {
        vm.termux.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            ReviewTopBar("Termux", onBack) {
                if (items.isNotEmpty()) TextButton(onClick = { vm.termux.setSelected(items.map { it.spec }, false) }) { Text("None") }
            }
        },
        bottomBar = {
            if (items.isNotEmpty()) {
                ActionBar(
                    summary = "Frees ${selected.sumOf { it.bytes }.humanBytes()}",
                    detail = "${selected.size.plural("location")} selected",
                    action = "Clean",
                    enabled = selected.isNotEmpty() && !state.auditing,
                ) { confirm = selected }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "Termux's home is private to Termux, so the steward asks Termux to run a small, audited helper script there. " +
                        "It re-checks every path and never follows symlinks. The clean-up below never touches your sources, configs or " +
                        "packages; Browse, Packages and Remove only change what you pick.",
                )
            }
            if (state.status != TermuxStatus.READY || state.needsExternalApps) {
                item { SetupCard(state.status, state.needsExternalApps, context, onAllow = { permission.launch(TermuxBridge.PERMISSION) }, onOpen = { vm.termux.bridge.openTermux() }) }
            }
            state.error?.takeIf { !state.needsExternalApps }?.let { item { InlineNotice(it, error = true) } }
            if (state.status == TermuxStatus.READY) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(onClick = { vm.termux.audit() }, enabled = !state.auditing) {
                            Text(if (report == null) "Scan Termux" else "Scan again")
                        }
                    }
                }
            }
            if (state.auditing) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Termux is measuring its folders…", style = MaterialTheme.typography.titleSmall)
                        Text("Large homes and proot distros can take a minute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            if (report != null) {
                item {
                    ReviewCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Termux uses ${report.totalBytes.humanBytes()}", style = MaterialTheme.typography.titleMedium)
                            report.usage.drop(1).filter { it.path != report.home && it.path != report.prefix }.take(8).forEach {
                                Text(
                                    "${TermuxProtocol.relative(it.path, report.home, report.prefix)}  ${it.bytes.humanBytes()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                    }
                }
                item {
                    ManageCard(
                        title = "Browse Termux",
                        text = if (report.entries.isEmpty()) {
                            "Run termux-setup-storage once in Termux and scan again: the folder-by-folder map comes back through shared storage."
                        } else {
                            "Every folder and file of 1 MiB or more, largest first. Open folders and delete what you pick."
                        },
                        action = "Browse",
                        enabled = report.entries.isNotEmpty(),
                    ) { navigate(Routes.TERMUX_BROWSER) }
                }
                item {
                    ManageCard(
                        title = "Packages",
                        text = "Every installed package with its size and what needs it. Pick packages to uninstall, as pkg uninstall would.",
                        action = "Open",
                        enabled = true,
                    ) { navigate(Routes.TERMUX_PACKAGES) }
                }
                if (report.prootActive) {
                    item { InlineNotice("A proot distribution is running, so distro caches were only measured. Stop it and scan again to clean them.") }
                }
                for ((group, list) in groups) {
                    item(key = group.name) { GroupCard(group, list, state.selected, group.name in expanded, vm, report.home, report.prefix) { expanded = if (group.name in expanded) expanded - group.name else expanded + group.name } }
                }
                if (report.rootfs.isNotEmpty()) {
                    item { SectionHeader("Linux distributions") }
                    items(report.rootfs) { r -> DistroRow(distroName(r.path), r.bytes, r.active || report.prootActive) { removeDistro = r } }
                }
                if (report.largeFiles.isNotEmpty()) {
                    item { SectionHeader("Largest files in Termux") }
                    items(report.largeFiles.take(15)) { f ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Text(f.size.humanBytes(), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                TermuxProtocol.relative(f.path, report.home, report.prefix),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                }
                (report.warnings + report.unsafe.map { "Skipped (symlinked or out of bounds): ${TermuxProtocol.relative(it, report.home, report.prefix)}" })
                    .forEach { w -> item { InlineNotice(w) } }
            }
        }
    }

    removeDistro?.let { r ->
        IrreversibleDialog(
            title = "Remove the ${distroName(r.path)} distribution?",
            names = emptyList(),
            lines = listOf(
                "Frees about ${r.bytes.humanBytes()}",
                "Everything inside it goes: its packages, your files in its home folders and its settings",
                "You can install a fresh one again with proot-distro install",
            ),
            confirmLabel = "Remove",
            onConfirm = {
                removeDistro = null
                vm.removeTermuxDistro(r.path)
            },
            onDismiss = { removeDistro = null },
        )
    }

    confirm?.let { list ->
        ConfirmDialog(
            title = "Clean ${list.size.plural("location")} in Termux?",
            lines = listOfNotNull(
                "Frees about ${list.sumOf { it.bytes }.humanBytes()}",
                if (list.any { it.group == TermuxGroup.BUILD }) "Build outputs come back on your next build or install" else null,
                if (list.any { it.targetId == "apt-lists" }) "Run pkg update before your next pkg install" else null,
                "Installed packages, configs, sources and your files are not touched",
            ),
            confirmLabel = "Clean",
            footnote = "Termux clean-ups are permanent. Each path is checked again inside Termux right before it is removed.",
            onConfirm = {
                confirm = null
                vm.cleanTermux(list)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun GroupCard(
    group: TermuxGroup,
    list: List<TermuxItem>,
    selected: Set<String>,
    open: Boolean,
    vm: StewardViewModel,
    home: String,
    prefix: String,
    onExpand: () -> Unit,
) {
    ReviewCard {
        Column {
            GroupHeader(
                modifier = Modifier.clickable(onClick = onExpand),
                title = group.title,
                subtitle = list.size.plural("location") + " · " + list.sumOf { it.bytes }.humanBytes() + "\n" + group.description,
                state = toggleState(list.count { it.spec in selected }, list.size),
                onToggle = {
                    val all = list.all { it.spec in selected }
                    vm.termux.setSelected(list.map { it.spec }, !all)
                },
            ) {
                IconButton(onClick = onExpand) {
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }
            if (open) {
                list.take(200).forEach { item ->
                    SelectRow(
                        checked = item.spec in selected,
                        onCheckedChange = { vm.termux.toggle(item.spec) },
                        title = item.title,
                        subtitle = listOf(TermuxProtocol.relative(item.path, home, prefix), item.files.plural("file"), item.note)
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        trailing = { SizeText(item.bytes) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(status: TermuxStatus, needsExternalApps: Boolean, context: Context, onAllow: () -> Unit, onOpen: () -> Unit) {
    ReviewCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect Termux", style = MaterialTheme.typography.titleMedium)
            Step(1, "Install Termux (F-Droid or GitHub build).", done = status != TermuxStatus.NOT_INSTALLED) {
                OutlinedButton(onClick = { openUrl(context, "https://f-droid.org/packages/com.termux/") }) { Text("Get Termux") }
            }
            Step(2, "Allow Galaxy Steward to run commands in Termux.", done = status == TermuxStatus.READY) {
                FilledTonalButton(onClick = onAllow, enabled = status == TermuxStatus.NO_PERMISSION) { Text("Allow") }
            }
            Step(3, "Once, in Termux, run this line so Termux accepts requests from other apps:", done = status == TermuxStatus.READY && !needsExternalApps) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(
                            TermuxBridge.ENABLE_COMMAND,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copy(context, TermuxBridge.ENABLE_COMMAND) }) { Text("Copy") }
                        OutlinedButton(onClick = onOpen, enabled = status != TermuxStatus.NOT_INSTALLED) { Text("Open Termux") }
                    }
                }
            }
            Text(
                "For a complete report, also run termux-setup-storage once so Termux can hand its results over through shared storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(number: Int, text: String, done: Boolean, action: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (done) "✓" else "$number.",
                style = MaterialTheme.typography.titleSmall,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (!done) action()
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Termux command", text))
}

/** "debian" for both proot-distro layouts: installed-rootfs/debian and containers/debian/rootfs. */
private fun distroName(path: String): String = path.removeSuffix("/rootfs").substringAfterLast('/')

@Composable
private fun ManageCard(title: String, text: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    ReviewCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}
