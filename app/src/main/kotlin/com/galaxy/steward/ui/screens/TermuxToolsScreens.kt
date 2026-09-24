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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.ageText
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.optimize.ProjectMoves
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.core.termux.TermuxRepo
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.termux.TermuxStatus
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.IrreversibleDialog
import com.galaxy.steward.ui.components.Pill
import com.galaxy.steward.ui.components.SelectRow

private enum class RepoSort(val label: String) { SIZE("Largest"), IDLE("Untouched longest"), PACK("Most to pack") }

/**
 * Every Git repository Termux can see: in its home, in $PREFIX/opt, inside Linux distributions and in shared storage.
 * Each says when it was last committed to, fetched and worked in, and whether everything in it is pushed. Pack runs
 * git gc (lossless); repositories in shared storage can move into Termux; ones in Termux can be deleted.
 */
@Composable
fun TermuxReposScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val state by vm.termux.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(RepoSort.SIZE) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val now = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) { if (state.repos == null) vm.termux.loadRepos() }
    val shared = StorageAccess.rootPath
    val tree = ui.report?.tree
    fun size(r: TermuxRepo): Long = if (r.bytes >= 0) r.bytes else tree?.find(r.path.removePrefix("$shared/"))?.totalBytes ?: r.gitBytes
    val repos = state.repos.orEmpty().let { list ->
        when (sort) {
            RepoSort.SIZE -> list.sortedByDescending { size(it) }
            RepoSort.IDLE -> list.sortedBy { it.lastTouched.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }
            RepoSort.PACK -> list.sortedByDescending { it.looseBytes + it.garbageBytes }
        }
    }
    val picked = repos.filter { it.path in state.repoSelected }
    val inShared = picked.filter { it.path.startsWith("$shared/") }
    val inTermux = picked - inShared.toSet()
    val home = state.report?.home.orEmpty()
    val prefix = state.report?.prefix.orEmpty()

    Scaffold(
        topBar = {
            ReviewTopBar("Git repositories", onBack) {
                if (picked.isNotEmpty()) TextButton(onClick = { vm.termux.clearRepoSelection() }) { Text("None") }
            }
        },
        bottomBar = {
            val loose = picked.sumOf { it.looseBytes + it.garbageBytes }
            ActionBar(
                summary = if (picked.isNotEmpty() && loose == 0L) "Already packed" else "Packs about ${loose.humanBytes()} of loose objects",
                detail = "${picked.size.plural("repository", "repositories")} picked",
                action = "Pack",
                enabled = picked.isNotEmpty() && ui.applying == null,
            ) { confirm = "pack" }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "Pack runs git gc: loose objects go into one compressed pack and the repository works exactly as before. " +
                        "\"Everything pushed\" means cloning it again gives it back, so it is safe to delete.",
                )
            }
            if (state.status != TermuxStatus.READY) item { InlineNotice("Connect Termux on the Termux screen first.", error = true) }
            state.reposError?.let { item { InlineNotice(it, error = true) } }
            if (state.reposLoading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Termux is looking at every repository…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            if (repos.isNotEmpty()) {
                item {
                    Text(
                        "${repos.size.plural("repository", "repositories")} · ${repos.count { it.onlyACopy }} with everything pushed · " +
                            "${repos.sumOf { it.looseBytes + it.garbageBytes }.humanBytes()} to pack",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        RepoSort.entries.forEach { s ->
                            FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                if (picked.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { confirm = "move" }, enabled = inShared.isNotEmpty() && inTermux.isEmpty() && ui.applying == null) {
                                Text("Move into Termux")
                            }
                            OutlinedButton(onClick = { confirm = "delete" }, enabled = inTermux.isNotEmpty() && inShared.isEmpty() && ui.applying == null) {
                                Text("Delete")
                            }
                        }
                    }
                }
            } else if (!state.reposLoading && state.repos != null) {
                item { EmptyState(Icons.Rounded.DriveFileMove, "No repositories", "Termux found no Git repositories it may look at.") }
            }
            items(repos, key = { it.path }) { r ->
                val where = when {
                    r.path.startsWith("$shared/") -> "shared storage: " + r.path.removePrefix("$shared/")
                    else -> TermuxProtocol.relative(r.path, home, prefix)
                }
                val status = when {
                    r.remote.isEmpty() -> "no remote: this is the only copy"
                    r.dirty == true -> "uncommitted changes"
                    (r.unpushed ?: 0) > 0 -> "${r.unpushed!!.plural("commit")} not pushed"
                    r.onlyACopy -> "everything pushed to ${r.remoteLabel}"
                    else -> r.remoteLabel
                }
                SelectRow(
                    checked = r.path in state.repoSelected,
                    onCheckedChange = { vm.termux.toggleRepo(r.path) },
                    title = r.name + if (r.branch.isNotEmpty()) " (${r.branch})" else "",
                    subtitle = listOfNotNull(
                        where,
                        listOfNotNull(
                            r.lastCommit.takeIf { it > 0 }?.let { "last commit ${ageText(it, now)}" },
                            r.lastFetch.takeIf { it > 0 }?.let { "fetched ${ageText(it, now)}" },
                            r.lastActive.takeIf { it > 0 }?.let { "worked in ${ageText(it, now)}" },
                        ).joinToString(" · ").ifEmpty { null },
                        status,
                        if (r.packable) "git gc would pack ${(r.looseBytes + r.garbageBytes).humanBytes()}" else null,
                    ).joinToString("\n"),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    subtitleLines = 6,
                    trailing = {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SizeText(size(r))
                            when {
                                r.onlyACopy -> Pill("Pushed")
                                r.shallow -> Pill("Shallow")
                            }
                        }
                    },
                )
            }
        }
    }

    when (confirm) {
        "pack" -> ConfirmDialog(
            title = "Pack ${picked.size.plural("repository", "repositories")}?",
            lines = listOf(
                "git gc packs about ${picked.sumOf { it.looseBytes + it.garbageBytes }.humanBytes()} of loose objects",
                "Lossless: history, branches and files stay exactly as they are",
            ),
            confirmLabel = "Pack",
            footnote = "Termux runs git gc in each; a repository Git is working in right now is left alone.",
            onConfirm = {
                confirm = null
                vm.packTermuxRepos(picked.map { it.path })
            },
            onDismiss = { confirm = null },
        )
        "move" -> MoveIntoTermuxDialog(inShared.map { it.path }, inShared.sumOf { size(it) }, onDismiss = { confirm = null }) {
            confirm = null
            vm.moveIntoTermux(inShared.map { it.path })
        }
        "delete" -> IrreversibleDialog(
            title = "Delete ${inTermux.size.plural("repository", "repositories")}?",
            names = inTermux.map { it.name },
            lines = listOfNotNull(
                "Frees about ${inTermux.sumOf { size(it) }.humanBytes()}",
                inTermux.filterNot { it.onlyACopy }.takeIf { it.isNotEmpty() }?.let { list ->
                    "Not everything is pushed in ${list.joinToString { it.name }}: work only in it is lost"
                },
                "Deleted for good: Termux has no recycle bin",
            ),
            confirmLabel = "Delete",
            onConfirm = {
                confirm = null
                vm.deleteTermuxPaths(inTermux.map { it.path })
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun MoveIntoTermuxDialog(paths: List<String>, bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Move ${paths.size.plural("folder")} into Termux?",
        lines = listOf(
            "To ~/projects in Termux: ${paths.joinToString { it.substringAfterLast('/') }} (${bytes.humanBytes()})",
            "Git, jadx, apktool and scans read it there without shared storage's slow layer",
            "Apps that open it from shared storage (file managers, MT Manager) won't see it any more",
            "Shared storage and Termux share one partition, so this frees no space",
        ),
        confirmLabel = "Move",
        footnote = "Termux copies it, compares every byte, and only then removes the original. History → Undo moves it back.",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Projects and decompiled apps in shared storage, from the last scan, to move into Termux's home one by one. */
@Composable
fun TermuxProjectsScreen(vm: StewardViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val termux by vm.termux.state.collectAsStateWithLifecycle()
    val tree = ui.report?.tree
    // The scan may be older than a move: what isn't in shared storage any more is no longer offered.
    val candidates = remember(tree, ui.journals.size) {
        tree?.let { ProjectMoves.candidates(it) }.orEmpty().filter { java.io.File(it.path).isDirectory }
    }
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val chosen = candidates.filter { it.path in picked }

    Scaffold(
        topBar = { ReviewTopBar("Projects in shared storage", onBack) },
        bottomBar = {
            ActionBar(
                summary = "${chosen.sumOf { it.files }.let { String.format(java.util.Locale.ROOT, "%,d", it) }} files off shared storage",
                detail = "${chosen.size.plural("project")} picked",
                action = "Move",
                enabled = chosen.isNotEmpty() && ui.applying == null && termux.status == TermuxStatus.READY,
            ) { confirm = true }
        },
    ) { padding ->
        if (tree == null) {
            EmptyState(Icons.Rounded.DriveFileMove, "Scan first", "The scan finds projects and decompiled apps in shared storage.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                val files = candidates.sumOf { it.files }
                InlineNotice(
                    "Shared storage goes through Android's FUSE layer, which is slow with many small files. " +
                        (if (files > 0) "These projects hold ${String.format(java.util.Locale.ROOT, "%,d", files)} of your ${String.format(java.util.Locale.ROOT, "%,d", tree.root.totalFiles)} files; " else "") +
                        "in Termux's home, Git, jadx and apktool read them several times faster and every scan here is quicker. " +
                        "Move only what you work on from Termux.",
                )
            }
            if (termux.status != TermuxStatus.READY) item { InlineNotice("Connect Termux on the Termux screen first.", error = true) }
            if (ui.stale) item { InlineNotice("Files changed since the last scan; scan again before moving more.") }
            if (candidates.isEmpty()) {
                item { Text("No projects or decompiled apps of 200 files or more in shared storage.", modifier = Modifier.padding(20.dp)) }
            }
            items(candidates, key = { it.path }) { c ->
                SelectRow(
                    checked = c.path in picked,
                    onCheckedChange = { picked = if (c.path in picked) picked - c.path else picked + c.path },
                    title = c.path.substringAfterLast('/'),
                    subtitle = c.relPath + "\n${c.files.plural("file")}" + (c.newest.takeIf { it > 0 }?.let { " · last change ${ageText(it, now)}" } ?: ""),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    subtitleLines = 3,
                    trailing = { SizeText(c.bytes) },
                )
            }
        }
    }

    if (confirm) {
        MoveIntoTermuxDialog(chosen.map { it.path }, chosen.sumOf { it.bytes }, onDismiss = { confirm = false }) {
            confirm = false
            vm.moveIntoTermux(chosen.map { it.path })
            picked = emptySet()
        }
    }
}

/**
 * Identical files and near-copies of folders in Termux, from the last Termux scan: one card each, with a way to pick
 * copies to delete. Near-copies may pair a Termux folder with one in shared storage (from the last storage scan).
 */
@Composable
fun TermuxCopies(vm: StewardViewModel) {
    val state by vm.termux.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val report = state.report ?: return
    val near = remember(report, ui.report) { report.nearCopies(ui.report?.sketches.orEmpty()) }
    var review by remember { mutableStateOf<List<String>?>(null) }
    val now = remember { System.currentTimeMillis() }
    fun show(path: String) = if (path.startsWith(StorageAccess.rootPath + "/")) {
        "shared: " + path.removePrefix(StorageAccess.rootPath + "/")
    } else {
        TermuxProtocol.relative(path, report.home, report.prefix)
    }
    if (report.duplicates.isEmpty() && near.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Copies in Termux", style = MaterialTheme.typography.titleMedium)
        report.duplicates.take(8).forEach { d ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "${d.paths.size} identical copies of ${d.paths.first().substringAfterLast('/')} · ${d.size.humanBytes()} each",
                    style = MaterialTheme.typography.bodyMedium,
                )
                d.paths.forEach { Text(show(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                TextButton(onClick = { review = d.paths }) { Text("Pick copies to delete") }
            }
        }
        near.take(8).forEach { p ->
            val (older, newer) = listOf(p.a, p.b).sortedBy { report.entry(it)?.mtime ?: Long.MAX_VALUE }
            Column(Modifier.fillMaxWidth()) {
                Text("${p.percent}% the same files", style = MaterialTheme.typography.bodyMedium)
                listOf(older, newer).forEach { path ->
                    val t = report.entry(path)?.mtime ?: 0L
                    Text(
                        show(path) + (if (t > 0) " · last change ${ageText(t, now)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                val inTermux = listOf(older, newer).filterNot { it.startsWith(StorageAccess.rootPath + "/") }
                if (inTermux.isNotEmpty()) TextButton(onClick = { review = inTermux }) { Text("Pick one to delete") }
            }
        }
    }
    review?.let { paths ->
        var chosen by remember(paths) { mutableStateOf(setOf<String>()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { review = null },
            title = { Text("Delete which?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    paths.forEach { path ->
                        SelectRow(
                            checked = path in chosen,
                            onCheckedChange = { chosen = if (path in chosen) chosen - path else chosen + path },
                            title = path.substringAfterLast('/'),
                            subtitle = show(path),
                        )
                    }
                    Text(
                        "Deleted for good. Termux refuses package files, keys and what it needs; keep at least one copy.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    review = null
                    vm.deleteTermuxPaths(chosen.toList())
                }, enabled = chosen.isNotEmpty() && (chosen.size < paths.size || paths.size == 1)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Cancel") } },
        )
    }
}
