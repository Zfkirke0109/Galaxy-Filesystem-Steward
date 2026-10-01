package com.galaxy.steward.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.apps.AppStorage
import com.galaxy.steward.apps.DebugApp
import com.galaxy.steward.core.ageText
import com.galaxy.steward.core.device.KnownApps
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.shizuku.ShizukuStatus
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.IrreversibleDialog
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow

/**
 * Beyond app folders, through Shizuku: Android's own storage breakdown, its own clean-ups, what adb left in the shell's
 * places, apps that keep big private data (Layla, UserLAnd) and the private data of your debuggable builds.
 */
@Composable
fun DeepSpaceScreen(vm: StewardViewModel, openPrivate: () -> Unit, onBack: () -> Unit) {
    val state by vm.deepSpace.state.collectAsStateWithLifecycle()
    val apps by vm.apps.state.collectAsStateWithLifecycle()
    val termux by vm.termux.state.collectAsStateWithLifecycle()
    val shizuku by vm.apps.shizuku.status.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    var confirmShell by remember { mutableStateOf(false) }
    var confirmTrim by remember { mutableStateOf<String?>(null) }
    val now = remember { System.currentTimeMillis() }
    LaunchedEffect(shizuku) { if (shizuku == ShizukuStatus.READY && state.disk == null && !state.loading) vm.deepSpace.load() }
    val picked = state.shell.filter { it.path in state.shellSelected && it.lock == null }

    Scaffold(
        topBar = {
            ReviewTopBar("Beyond app folders", onBack) {
                if (shizuku == ShizukuStatus.READY) TextButton(onClick = { vm.deepSpace.load() }, enabled = !state.loading) { Text("Rescan") }
            }
        },
        bottomBar = {
            if (state.shell.isNotEmpty()) {
                ActionBar(
                    summary = "Frees about ${picked.sumOf { it.bytes }.humanBytes()}",
                    detail = "${picked.size.plural("item")} picked",
                    action = "Delete",
                    enabled = picked.isNotEmpty() && ui.applying == null,
                ) { confirmShell = true }
            }
        },
    ) { padding ->
        if (shizuku != ShizukuStatus.READY) {
            EmptyState(Icons.Rounded.Key, "Connect Shizuku", "These places only Android's shell user can see. Connect Shizuku on the Apps tab first.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            state.error?.let { item { InlineNotice(it, error = true) } }

            // Android's own account, with what no scan can open.
            state.disk?.let { disk ->
                item { SectionHeader("What Android counts") }
                item {
                    ReviewCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${disk.used.humanBytes()} used of ${disk.total.humanBytes()} · ${disk.free.humanBytes()} free", style = MaterialTheme.typography.titleMedium)
                            if (!disk.measured) {
                                Text("Android hasn't measured the categories yet (it does so about once a day while charging).", style = MaterialTheme.typography.bodySmall)
                            }
                            disk.parts.entries.sortedByDescending { it.value }.forEach { (name, bytes) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(bytes.humanBytes(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            // What "Other" is made of, from Android's live count and the app folder scan (the first line
                            // repeats what is shown above).
                            val phone = remember(state.disk, state.shared, apps.folders, ui.report) { vm.phoneSpace() }
                            phone.lines().drop(1).forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.shared == null && (disk.parts["Other"] ?: 0) > 0) {
                                Text(
                                    "\"Other\" is shared storage that isn't photos, videos or audio: your files, and every app's Android/data and obb. " +
                                        "Grant usage access on the Apps tab to see how much is which. Android refreshes these numbers about once a day.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Android's own clean-ups") }
            item {
                ReviewCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "What Android does by itself when storage runs low, on request. Free space is measured before and after, " +
                                "so the result says what really changed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { confirmTrim = "trim-caches" }, enabled = ui.applying == null) { Text("Trim all app caches") }
                            OutlinedButton(onClick = { confirmTrim = "art-cleanup" }, enabled = ui.applying == null) { Text("Old compiled code") }
                        }
                    }
                }
            }

            // Layla, UserLAnd: what is known about them from every side.
            val known = apps.apps.filter { KnownApps.hint(it.packageName) != null }
            if (known.isNotEmpty() || !apps.usageAccess) item { SectionHeader("Apps that keep big private data") }
            if (!apps.usageAccess) item { InlineNotice("Grant usage access on the Apps tab to see how much Layla, UserLAnd and others keep.") }
            items(known, key = { "known:" + it.packageName }) { row ->
                val hint = KnownApps.hint(row.packageName)!!
                val outside = apps.folders?.usage?.filter { it.packageName == row.packageName }?.sumOf { it.bytes } ?: 0L
                val relays = termux.report?.keptTopmost?.count { it.why.contains("Layla", ignoreCase = true) || it.path.contains("layla", ignoreCase = true) } ?: 0
                val debug = state.debugApps.firstOrNull { it.packageName == row.packageName }
                ReviewCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${row.label} · ${row.totalBytes.humanBytes()}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                "app ${row.appBytes.humanBytes()}",
                                "private data ${row.dataBytes.humanBytes()}",
                                "cache ${row.cacheBytes.humanBytes()}",
                                outside.takeIf { it > 0 }?.let { "Android/data ${it.humanBytes()}" },
                                row.lastUsed?.let { "last used ${ageText(it, now)}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (hint.name == "Layla" && relays > 0) {
                            Text("${relays.plural("Sidekick relay")} kept in Termux", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(hint.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val context = LocalContext.current
                            TextButton(onClick = { context.startActivity(AppStorage.appInfoIntent(row.packageName)) }) { Text("App info") }
                            if (debug != null) {
                                TextButton(onClick = {
                                    vm.deepSpace.openPrivate(debug)
                                    openPrivate()
                                }) { Text("Browse private data") }
                            }
                        }
                    }
                }
            }

            if (state.debugApps.isNotEmpty()) {
                item { SectionHeader("Your debuggable builds") }
                item {
                    InlineNotice(
                        "Android lets the shell user into the private data of apps built debuggable, and only those: open one to see " +
                            "what it keeps and remove what you choose (the app is stopped first).",
                    )
                }
                items(state.debugApps, key = { "debug:" + it.packageName }) { app ->
                    val row = apps.apps.firstOrNull { it.packageName == app.packageName }
                    DebugAppRow(app, row?.dataBytes) {
                        vm.deepSpace.openPrivate(app)
                        openPrivate()
                    }
                }
            }

            item { SectionHeader("Left by adb and bug reports") }
            item {
                InlineNotice(
                    "/data/local/tmp holds what adb pushed (installers, tools, dumps); Android keeps bug reports it made. Nothing is " +
                        "picked for you, and what Shizuku, rish, Frida or their kind start from here can't be picked at all.",
                )
            }
            state.shellUnreadable.forEach { item { InlineNotice("Couldn't read $it on this phone") } }
            if (state.shell.isEmpty() && !state.loading) {
                item { Text("Nothing there.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            }
            items(state.shell, key = { it.path }) { e ->
                SelectRow(
                    checked = e.path in state.shellSelected && e.lock == null,
                    onCheckedChange = { vm.deepSpace.toggleShell(e.path) },
                    enabled = e.lock == null,
                    title = e.name,
                    subtitle = listOfNotNull(e.kind, e.path.substringBeforeLast('/'), e.mtime.takeIf { it > 0 }?.let { "changed ${ageText(it, now)}" }).joinToString(" · ") +
                        (e.lock?.let { "\n$it" } ?: ""),
                    subtitleLines = 3,
                    trailing = { SizeText(e.bytes) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }

    if (confirmShell) {
        IrreversibleDialog(
            title = "Delete ${picked.size.plural("item")}?",
            names = picked.map { it.name },
            lines = listOf(
                "Frees about ${picked.sumOf { it.bytes }.humanBytes()}",
                "Deleted for good: adb can push them again",
                "Each is checked again right before it goes",
            ),
            confirmLabel = "Delete",
            onConfirm = {
                confirmShell = false
                vm.removeShellSpace(picked.map { it.path })
            },
            onDismiss = { confirmShell = false },
        )
    }
    confirmTrim?.let { what ->
        ConfirmDialog(
            title = if (what == "trim-caches") "Trim every app's cache?" else "Clean up old compiled code?",
            lines = if (what == "trim-caches") {
                listOf(
                    "Android clears app caches the way it does when storage runs low",
                    "Apps rebuild what they need; the first start of some may be slower",
                    "Your data, logins and settings stay",
                )
            } else {
                listOf(
                    "Android removes compiled code of apps that were updated or removed",
                    "Apps in use keep theirs; nothing needs to be installed again",
                )
            },
            confirmLabel = if (what == "trim-caches") "Trim" else "Clean up",
            footnote = "Runs Android's own command through Shizuku.",
            onConfirm = {
                confirmTrim = null
                vm.systemClean(what)
            },
            onDismiss = { confirmTrim = null },
        )
    }
}

@Composable
private fun DebugAppRow(app: DebugApp, dataBytes: Long?, onOpen: () -> Unit) {
    ReviewCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    app.packageName + (dataBytes?.let { " · ${it.humanBytes()} private data" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

/** The private data of one debuggable app, largest first: pick what to remove (run-as, after stopping the app). */
@Composable
fun PrivateDataScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.deepSpace.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val app = state.privateFor
    var confirm by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    val entries = state.privateEntries.orEmpty()
    // A folder picked with something inside it: the folder covers both.
    val picked = entries.filter { it.rel in state.privateSelected }.let { list -> list.filter { e -> list.none { e.rel.startsWith(it.rel + "/") } } }
    Scaffold(
        topBar = { ReviewTopBar(app?.label ?: "Private data", onBack) },
        bottomBar = {
            ActionBar(
                summary = "Frees about ${picked.sumOf { it.bytes }.humanBytes()}",
                detail = "${picked.size.plural("item")} picked",
                action = "Delete",
                enabled = picked.isNotEmpty() && ui.applying == null && app != null,
            ) { confirm = true }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.privateLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            state.privateError?.let { item { InlineNotice(it, error = true) } }
            if (app != null && KnownApps.hint(app.packageName) != null) item { InlineNotice(KnownApps.hint(app.packageName)!!.text) }
            item {
                InlineNotice(
                    "Everything one level in, and anything deeper of 1 MiB or more. Removing data an app still expects can reset it " +
                        "or lose what it saved: caches and downloaded archives are the safe picks.",
                )
            }
            items(entries, key = { it.rel }) { e ->
                SelectRow(
                    checked = e.rel in state.privateSelected,
                    onCheckedChange = { vm.deepSpace.togglePrivate(e.rel) },
                    title = e.rel,
                    subtitle = e.label,
                    subtitleLines = 3,
                    trailing = {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SizeText(e.bytes)
                            if (e.label?.startsWith("Cache") == true) Pill("Cache")
                        }
                    },
                    modifier = Modifier.padding(start = (8 + 12 * e.depth).dp),
                )
            }
        }
    }
    if (confirm && app != null) {
        IrreversibleDialog(
            title = "Delete ${picked.size.plural("item")} of ${app.label}?",
            names = picked.map { it.rel },
            lines = listOf(
                "Frees about ${picked.sumOf { it.bytes }.humanBytes()}",
                "${app.label} is stopped first; what it saved there is gone for good",
            ),
            confirmLabel = "Delete",
            onConfirm = {
                confirm = false
                vm.removePrivateData(app.packageName, picked.map { it.rel })
            },
            onDismiss = { confirm = false },
        )
    }
}
