package com.galaxy.steward.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.UiState
import com.galaxy.steward.ui.components.ActionBar
import com.galaxy.steward.ui.components.ConfirmDialog
import com.galaxy.steward.ui.components.EmptyState
import com.galaxy.steward.ui.components.GroupHeader
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SelectRow
import com.galaxy.steward.ui.components.relativeTo
import com.galaxy.steward.ui.components.toggleState

@Composable
fun JunkScreen(vm: StewardViewModel, state: UiState, onBack: () -> Unit) {
    val junk = state.report?.junk.orEmpty()
    val byCategory = remember(junk) { junk.groupBy { it.category }.toSortedMap(compareBy { it.ordinal }) }
    val selected = junk.filter { it.id in state.selected }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<List<PlanItem>?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ReviewTopBar("Clutter", onBack) {
                TextButton(onClick = { vm.setSelected(junk.map { it.id }, false) }) { Text("None") }
            }
        },
        bottomBar = {
            ActionBar(
                summary = "Frees ${selected.sumOf { it.bytes }.humanBytes()}",
                detail = "${selected.size.plural("item", "items")} selected",
                action = "Clean",
                enabled = selected.isNotEmpty(),
            ) { confirm = selected }
        },
    ) { padding ->
        if (junk.isEmpty()) {
            EmptyState(Icons.Rounded.CleaningServices, "Nothing to clean", "No stale downloads, old logs, empty folders or caches were found.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            item { InlineNotice("Files go to the quarantine first. The space is released when you empty it or after the retention period.") }
            for ((category, items) in byCategory) {
                item(key = category.name) {
                    val isOpen = category.name in expanded
                    ReviewCard {
                        GroupHeader(
                            modifier = Modifier.clickable { expanded = if (isOpen) expanded - category.name else expanded + category.name },
                            title = category.title,
                            subtitle = items.size.plural("item") + " · " + items.sumOf { it.bytes }.humanBytes() + "\n" + category.description,
                            state = toggleState(items.count { it.id in state.selected }, items.size),
                            onToggle = {
                                val all = items.all { it.id in state.selected }
                                vm.setSelected(items.map { it.id }, !all)
                            },
                        ) {
                            IconButton(onClick = { expanded = if (isOpen) expanded - category.name else expanded + category.name }) {
                                Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                            }
                        }
                        if (isOpen) {
                            items.take(300).forEach { item -> JunkRow(item, item.id in state.selected, vm.rootPath) { vm.toggle(item.id) } }
                            if (items.size > 300) {
                                Text(
                                    "…and ${items.size - 300} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 56.dp, bottom = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { items ->
        val junkItems = items.filterIsInstance<JunkItem>()
        val folders = junkItems.count { it.category == JunkCategory.EMPTY_FOLDERS }
        ConfirmDialog(
            title = "Clean clutter?",
            lines = listOfNotNull(
                (junkItems.size - folders).takeIf { it > 0 }?.let { "${it.plural("item", "items")} moved to quarantine (${junkItems.sumOf { i -> i.bytes }.humanBytes()})" },
                folders.takeIf { it > 0 }?.let { "${it.plural("empty folder tree", "empty folder trees")} removed" },
                "Quarantine is kept for ${settings.quarantineRetentionDays} days",
            ),
            confirmLabel = "Clean",
            onConfirm = {
                confirm = null
                vm.apply("Clutter cleanup", "junk", items)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun JunkRow(item: JunkItem, checked: Boolean, root: String, onToggle: () -> Unit) {
    SelectRow(
        checked = checked,
        onCheckedChange = { onToggle() },
        title = item.title,
        subtitle = item.path.substringBeforeLast('/').relativeTo(root) + " · " + item.note,
        trailing = { if (item.bytes > 0) Text(item.bytes.humanBytes(), style = MaterialTheme.typography.labelMedium) },
    )
}
