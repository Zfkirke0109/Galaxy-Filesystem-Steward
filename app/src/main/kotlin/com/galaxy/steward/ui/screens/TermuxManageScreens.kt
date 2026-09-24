package com.galaxy.steward.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.termux.PlannedRemoval
import com.galaxy.steward.core.termux.TermuxLocks
import com.galaxy.steward.core.termux.TermuxPackage
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.core.termux.TermuxRemovalPlan
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.IrreversibleDialog
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SelectRow
import com.galaxy.steward.ui.components.folderIcon
import com.galaxy.steward.ui.components.kindIcon

/**
 * Everything in Termux of 1 MiB or more, folder by folder and largest first, from the last Termux scan. Pick files and
 * folders to delete for good; Termux checks each one again and refuses package files, distributions' systems, keys and
 * what Termux itself needs.
 */
@Composable
fun TermuxBrowserScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.termux.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val report = state.report
    if (report == null || report.entries.isEmpty()) {
        Scaffold(topBar = { ReviewTopBar("Browse Termux", onBack) }) { padding ->
            EmptyState(
                Icons.Rounded.Inventory2,
                "Scan Termux first",
                "The Termux screen's scan measures every folder. Run termux-setup-storage once in Termux, so the full map can come back.",
                Modifier.padding(padding),
            )
        }
        return
    }
    var path by rememberSaveable { mutableStateOf(report.filesRoot) }
    var confirm by remember { mutableStateOf(false) }
    val atRoot = path == report.filesRoot
    fun up() {
        path = path.substringBeforeLast('/')
    }
    BackHandler(enabled = !atRoot) { up() }

    val children = report.children(path)
    val size = report.entry(path)?.bytes ?: report.totalBytes
    val selected = state.browseSelected.mapNotNull { report.entry(it) }
    val shown = if (atRoot) "Termux" else TermuxProtocol.relative(path, report.home, report.prefix).let { if (it == path) path.removePrefix(report.filesRoot + "/") else it }

    Scaffold(
        topBar = {
            ReviewTopBar(if (atRoot) "Browse Termux" else path.substringAfterLast('/'), { if (atRoot) onBack() else up() }) {
                if (state.browseSelected.isNotEmpty()) TextButton(onClick = { vm.termux.clearBrowseSelection() }) { Text("None") }
            }
        },
        bottomBar = {
            ActionBar(
                summary = "Frees about ${selected.sumOf { it.bytes }.humanBytes()}",
                detail = "${selected.size.plural("item")} picked",
                action = "Delete",
                enabled = selected.isNotEmpty() && ui.applying == null,
            ) { confirm = true }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(shown, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    val rest = size - children.sumOf { it.bytes }
                    Text(
                        "${size.humanBytes()}" + if (rest > 0) " · ${rest.humanBytes()} in things under 1 MiB each" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (atRoot) {
                item {
                    InlineNotice(
                        "Deleting here is permanent. Package files go through Packages, whole distributions through the Termux screen; " +
                            "Termux checks every pick again and refuses what it needs.",
                    )
                }
            }
            items(children, key = { it.path }) { e ->
                val lock = TermuxLocks.reason(e.path, report)
                val hasInside = e.isDirectory && report.children(e.path).isNotEmpty()
                BrowserRow(
                    icon = if (e.isDirectory) folderIcon else kindIcon(FileKind.of(e.name)),
                    title = e.name,
                    subtitle = lock ?: if (e.isDirectory) "folder" else "file",
                    bytes = e.bytes,
                    checked = if (lock == null) e.path in state.browseSelected else null,
                    onCheck = { vm.termux.toggleBrowse(e.path) },
                    onClick = { if (hasInside) path = e.path else if (lock == null) vm.termux.toggleBrowse(e.path) },
                )
            }
            if (children.isEmpty()) {
                item { Text("Nothing of 1 MiB or more in here.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            }
        }
    }

    if (confirm) {
        IrreversibleDialog(
            title = "Delete ${selected.size.plural("item")} in Termux?",
            names = selected.sortedByDescending { it.bytes }.map { it.name },
            lines = listOf(
                "Frees about ${selected.sumOf { it.bytes }.humanBytes()}",
                "Deleted for good: Termux has no recycle bin",
                "Termux refuses package files, distributions' systems, keys and its own settings",
            ),
            confirmLabel = "Delete",
            onConfirm = {
                confirm = false
                vm.deleteTermuxPaths(selected.map { it.path })
            },
            onDismiss = { confirm = false },
        )
    }
}

private enum class PackageFilter(val label: String) { YOURS("Installed by you"), ALL("All"), DEPENDENCIES("Dependencies") }

/**
 * Installed Termux packages, largest first, with what needs each one. Pick packages to uninstall: apt's own dry run
 * shows everything that would go with them before anything happens, and packages Termux needs are never offered.
 */
@Composable
fun TermuxPackagesScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.termux.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(PackageFilter.YOURS) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (state.packages == null) vm.termux.loadPackages() }

    val packages = state.packages.orEmpty()
    val neededBy = remember(packages) {
        val map = HashMap<String, MutableList<String>>()
        packages.forEach { p -> p.depends.forEach { d -> map.getOrPut(d) { ArrayList() } += p.name } }
        map
    }
    val shown = remember(packages, filter, query) {
        packages.filter {
            when (filter) {
                PackageFilter.YOURS -> it.manual
                PackageFilter.ALL -> true
                PackageFilter.DEPENDENCIES -> !it.manual
            } && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.summary.contains(query.trim(), ignoreCase = true))
        }
    }
    val selected = packages.filter { it.name in state.packageSelected }

    Scaffold(
        topBar = {
            ReviewTopBar("Termux packages", onBack) {
                if (selected.isNotEmpty()) TextButton(onClick = { vm.termux.clearPackageSelection() }) { Text("None") }
            }
        },
        bottomBar = {
            ActionBar(
                summary = "Frees about ${selected.sumOf { it.bytes }.humanBytes()}",
                detail = if (state.planning) "Asking apt what goes with them…" else "${selected.size.plural("package")} picked",
                action = "Uninstall",
                enabled = selected.isNotEmpty() && !state.planning && ui.applying == null,
            ) { vm.termux.planRemoval(selected.map { it.name }) }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "Uninstalling runs apt inside Termux, as pkg uninstall would. You can install anything again with pkg install. " +
                        "Packages Termux itself needs can't be picked.",
                )
            }
            state.packagesError?.let { item { InlineNotice(it, error = true) } }
            if (state.packagesLoading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Termux is listing its packages…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            if (packages.isNotEmpty()) {
                item {
                    Text(
                        "${packages.size.plural("package")} use ${packages.sumOf { it.bytes }.humanBytes()}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        PackageFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        placeholder = { Text("Find a package") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            items(shown, key = { it.name }) { p -> PackageRow(p, p.name in state.packageSelected, neededBy[p.name].orEmpty()) { vm.termux.togglePackage(p.name) } }
        }
    }

    state.plan?.let { plan ->
        PlanDialog(
            plan = plan,
            onConfirm = { autoremove ->
                val names = selected.map { it.name }
                vm.termux.dismissPlan()
                vm.removeTermuxPackages(names, autoremove)
            },
            onDismiss = { vm.termux.dismissPlan() },
        )
    }
}

@Composable
private fun PackageRow(p: TermuxPackage, checked: Boolean, neededBy: List<String>, onToggle: () -> Unit) {
    val needs = when {
        neededBy.isEmpty() -> null
        neededBy.size <= 3 -> "needed by ${neededBy.sorted().joinToString()}"
        else -> "needed by ${neededBy.sorted().take(3).joinToString()} and ${neededBy.size - 3} more"
    }
    SelectRow(
        checked = checked && !p.protected,
        onCheckedChange = { onToggle() },
        enabled = !p.protected,
        title = p.name,
        subtitle = listOfNotNull(p.summary.ifEmpty { null }, p.version, needs).joinToString(" · "),
        modifier = Modifier.padding(horizontal = 8.dp),
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SizeText(p.bytes)
                when {
                    p.protected -> Pill("Termux needs it")
                    !p.manual -> Pill("Dependency")
                }
            }
        },
    )
}

/** What apt would remove, in its own words: what you picked, what needs it, and (optionally) what nothing needs after. */
@Composable
private fun PlanDialog(plan: TermuxRemovalPlan, onConfirm: (autoremove: Boolean) -> Unit, onDismiss: () -> Unit) {
    var autoremove by remember { mutableStateOf(true) }
    val requested = plan.packages.filter { it.kind == "requested" }
    val dependents = plan.packages.filter { it.kind == "dependent" }
    val orphans = plan.packages.filter { it.kind == "orphan" }
    val going = if (autoremove) plan.packages else plan.withoutOrphans()
    fun names(list: List<PlannedRemoval>) = list.joinToString { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan.blocked) "Can't uninstall these" else "Uninstall ${going.size.plural("package")}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.blocked) {
                    Text(
                        "apt would also remove what Termux itself needs: ${names(plan.packages.filter { it.protected })}. Nothing was changed.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (plan.packages.isEmpty()) {
                    Text(plan.warnings.firstOrNull() ?: "apt would not remove anything.")
                } else {
                    Text("• ${names(requested)}", style = MaterialTheme.typography.bodyMedium)
                    if (dependents.isNotEmpty()) {
                        Text("• Also goes, because it needs what you picked: ${names(dependents)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (orphans.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().clickable { autoremove = !autoremove }, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Also remove ${orphans.size.plural("package")} nothing needs after this (${orphans.sumOf { it.bytes }.humanBytes()}): ${names(orphans)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = autoremove, onCheckedChange = { autoremove = it })
                        }
                    }
                    Text("Frees about ${going.sumOf { it.bytes }.humanBytes()}. Install any of them again with pkg install.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!plan.blocked && plan.packages.isNotEmpty()) TextButton(onClick = { onConfirm(autoremove) }) { Text("Uninstall") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (plan.blocked || plan.packages.isEmpty()) "Close" else "Cancel") } },
    )
}

/** A distribution row with its size and a way to remove it whole. */
@Composable
internal fun DistroRow(name: String, bytes: Long, running: Boolean, onRemove: () -> Unit) {
    ReviewCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    bytes.humanBytes() + if (running) " · running: stop it to remove" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove, enabled = !running) { Text("Remove") }
        }
    }
}
