package com.galaxy.steward.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OptimizeKind
import com.galaxy.steward.core.plural
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.InsightRow
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.SelectRow

@Composable
fun OptimizeScreen(vm: StewardViewModel, state: UiState, onBack: () -> Unit) {
    val items = state.report?.optimize.orEmpty()
    val insights = remember(state.report) {
        state.report?.insights.orEmpty().filterNot { it.title.startsWith("Left in place") }.sortedByDescending { it.severity.ordinal }
    }
    val selected = items.filter { it.id in state.selected }
    var confirm by remember { mutableStateOf<List<OptimizeItem>?>(null) }

    Scaffold(
        topBar = { ReviewTopBar("Optimize", onBack) },
        bottomBar = {
            if (items.isNotEmpty()) {
                ActionBar(
                    summary = "${selected.size.plural("fix", "fixes")} selected",
                    detail = "${selected.sumOf { it.fileCount }.plural("file", "files")} re-shelved",
                    action = "Apply",
                    enabled = selected.isNotEmpty(),
                ) { confirm = selected }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                InlineNotice(
                    "Shallow, well-bucketed folders list and index faster, and free space keeps flash writes fast. " +
                        "App-owned folders are only reported, never restructured.",
                )
            }
            if (items.isNotEmpty()) {
                item { SectionHeader("Layout fixes") }
                items(items, key = { it.id }) { item ->
                    ReviewCard {
                        SelectRow(
                            checked = item.id in state.selected,
                            onCheckedChange = { vm.toggle(item.id) },
                            title = item.title,
                            subtitle = "${item.kind.title}: ${item.detail}" + (state.learned[item.id]?.let { "\nLearned: ${it.note}" } ?: ""),
                            leading = {
                                IconBadge(
                                    when (item.kind) {
                                        OptimizeKind.BUCKET_FLAT_DIR -> Icons.Rounded.CalendarMonth
                                        OptimizeKind.LIFT_BUILD_OUTPUTS -> Icons.Rounded.Android
                                        OptimizeKind.FLATTEN_WRAPPER, OptimizeKind.COLLAPSE_CHAIN -> Icons.Rounded.UnfoldLess
                                        OptimizeKind.REPAIR_DATE_FOLDERS -> Icons.Rounded.Build
                                    },
                                    MaterialTheme.colorScheme.primary,
                                    size = 32,
                                )
                            },
                        )
                    }
                }
            }
            item { SectionHeader("Storage health") }
            if (insights.isEmpty()) {
                item {
                    Text(
                        "No problems found. Free space, folder depth and folder sizes all look healthy.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(insights) { InsightRow(it, vm.rootPath) }
        }
    }

    confirm?.let { list ->
        ConfirmDialog(
            title = "Apply ${list.size.plural("fix", "fixes")}?",
            lines = listOfNotNull(
                list.count { it.kind == OptimizeKind.FLATTEN_WRAPPER }.takeIf { it > 0 }?.let { "${it.plural("redundant nested folder", "redundant nested folders")} collapsed" },
                list.count { it.kind == OptimizeKind.BUCKET_FLAT_DIR }.takeIf { it > 0 }?.let { "${it.plural("large folder", "large folders")} sorted into date buckets" },
                list.count { it.kind == OptimizeKind.LIFT_BUILD_OUTPUTS }.takeIf { it > 0 }?.let {
                    "Installers in ${it.plural("downloaded build", "downloaded builds")} moved up to the top folder, Gradle's metadata quarantined"
                },
                list.count { it.kind == OptimizeKind.COLLAPSE_CHAIN }.takeIf { it > 0 }?.let { "${it.plural("chain", "chains")} of empty folders collapsed" },
                list.filter { it.kind == OptimizeKind.REPAIR_DATE_FOLDERS }.takeIf { it.isNotEmpty() }?.let { r ->
                    "${r.sumOf { it.fileCount }} source files moved out of date folders, back into their packages"
                },
            ),
            confirmLabel = "Apply",
            onConfirm = {
                confirm = null
                vm.apply("Layout optimization", "optimize", list)
            },
            onDismiss = { confirm = null },
        )
    }
}
