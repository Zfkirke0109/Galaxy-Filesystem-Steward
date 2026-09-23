package com.galaxy.steward.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.steward.apps.AppStorage
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural
import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.shizuku.ShizukuStatus
import com.galaxy.steward.termux.TermuxBridge
import com.galaxy.steward.termux.TermuxStatus
import com.galaxy.steward.ui.Routes
import com.galaxy.steward.ui.StewardViewModel
import com.galaxy.steward.ui.components.IconBadge
import com.galaxy.steward.ui.components.InlineNotice
import com.galaxy.steward.ui.components.ReviewCard
import com.galaxy.steward.ui.components.SectionHeader
import com.galaxy.steward.ui.components.UsageBar
import kotlinx.coroutines.launch

/** Everything apps keep outside your own folders: per-app storage, Android/data|obb|media and Termux. */
@Composable
fun AppsScreen(vm: StewardViewModel, navigate: (String) -> Unit) {
    val apps by vm.apps.state.collectAsStateWithLifecycle()
    val shizuku by vm.apps.shizuku.status.collectAsStateWithLifecycle()
    val termux by vm.termux.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.termux.refresh() }

    LifecycleResumeEffect(Unit) {
        vm.apps.refreshAccess()
        vm.termux.refresh()
        if (vm.apps.state.value.usageAccess && vm.apps.state.value.apps.isEmpty()) vm.apps.loadStats()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { ReviewTopBar("Apps & Termux", null) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                InlineNotice(
                    "Apps also keep data outside your folders: caches, logs, game downloads and leftovers of apps you removed. " +
                        "Nothing here changes until you review and confirm it.",
                )
            }

            item { SectionHeader("Access") }
            item {
                ReviewCard {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        AccessRow(
                            icon = Icons.Rounded.Key,
                            title = "Shizuku",
                            status = shizuku.label + if (shizuku == ShizukuStatus.READY) "" else " - needed for Android/data, obb and cache clearing",
                            ok = shizuku == ShizukuStatus.READY,
                            action = when (shizuku) {
                                ShizukuStatus.NOT_INSTALLED -> "Get"
                                ShizukuStatus.NOT_RUNNING -> "Open"
                                ShizukuStatus.NO_PERMISSION -> "Allow"
                                ShizukuStatus.READY -> null
                            },
                        ) {
                            when (shizuku) {
                                ShizukuStatus.NOT_INSTALLED -> openUrl(context, "https://shizuku.rikka.app/download/")
                                ShizukuStatus.NOT_RUNNING -> vm.apps.shizuku.launchManager()
                                ShizukuStatus.NO_PERMISSION -> vm.apps.shizuku.requestPermission()
                                ShizukuStatus.READY -> Unit
                            }
                        }
                        AccessRow(
                            icon = Icons.Rounded.QueryStats,
                            title = "Usage access",
                            status = if (apps.usageAccess) "Granted - app sizes are available" else "Needed to read how much each app stores",
                            ok = apps.usageAccess,
                            action = if (apps.usageAccess) null else "Grant",
                        ) {
                            scope.launch {
                                if (!vm.apps.grantUsageAccessWithShizuku()) {
                                    try {
                                        context.startActivity(AppStorage.usageAccessIntent(context))
                                    } catch (_: ActivityNotFoundException) {
                                        context.startActivity(AppStorage.usageAccessFallbackIntent())
                                    }
                                }
                            }
                        }
                        AccessRow(
                            icon = Icons.Rounded.Terminal,
                            title = "Termux",
                            status = termux.status.label,
                            ok = termux.status == TermuxStatus.READY,
                            action = when (termux.status) {
                                TermuxStatus.NOT_INSTALLED -> "Get"
                                TermuxStatus.NO_PERMISSION -> "Allow"
                                TermuxStatus.READY -> null
                            },
                        ) {
                            when (termux.status) {
                                TermuxStatus.NOT_INSTALLED -> openUrl(context, "https://f-droid.org/packages/com.termux/")
                                TermuxStatus.NO_PERMISSION -> termuxPermission.launch(TermuxBridge.PERMISSION)
                                TermuxStatus.READY -> Unit
                            }
                        }
                    }
                }
            }

            item { SectionHeader("App storage") }
            item {
                val total = apps.apps.sumOf { it.totalBytes }
                val data = apps.apps.sumOf { it.dataBytes }
                val cache = apps.apps.sumOf { it.cacheBytes }
                OverviewCard(
                    icon = Icons.Rounded.Apps,
                    title = "Installed apps",
                    text = when {
                        !apps.usageAccess -> "Grant usage access to see what every app stores, split into app data and cache."
                        apps.statsLoading && apps.apps.isEmpty() -> "Measuring apps…"
                        apps.statsError != null -> apps.statsError!!
                        else -> "${apps.apps.size.plural("app")} use ${total.humanBytes()}: ${data.humanBytes()} app data, ${cache.humanBytes()} cache"
                    },
                    fraction = if (total > 0) cache.toFloat() / total else null,
                    busy = apps.statsLoading,
                    action = if (apps.usageAccess) "Review" else null,
                ) { navigate(Routes.APP_STORAGE) }
            }

            item { SectionHeader("App folders") }
            item {
                val report = apps.folders
                val mediaOnly = report != null && report.areas == setOf(AppArea.MEDIA)
                OverviewCard(
                    icon = Icons.Rounded.FolderSpecial,
                    title = "Android/data, obb and media",
                    text = when {
                        apps.foldersScanning -> "Looking through app folders…" + (apps.foldersProgress?.let { " $it" } ?: "")
                        apps.foldersError != null -> apps.foldersError!!
                        report == null -> "Finds caches, logs, temp files, outdated game data and leftovers of removed apps."
                        else -> "${report.reclaimableBytes.humanBytes()} to reclaim in ${report.items.size.plural("place")}" +
                            if (mediaOnly) " (Android/media only - connect Shizuku for data and obb)" else ""
                    },
                    fraction = null,
                    busy = apps.foldersScanning,
                    action = when {
                        apps.foldersScanning -> null
                        report == null -> "Scan"
                        else -> "Review"
                    },
                    secondary = if (report != null && !apps.foldersScanning) "Rescan" else null,
                    onSecondary = { vm.apps.scanFolders() },
                ) {
                    if (report == null) vm.apps.scanFolders()
                    navigate(Routes.APP_FOLDERS)
                }
            }

            item { SectionHeader("Termux") }
            item {
                val report = termux.report
                OverviewCard(
                    icon = Icons.Rounded.Terminal,
                    title = "Termux home and packages",
                    text = when {
                        termux.auditing -> "Termux is measuring its folders…"
                        termux.error != null -> termux.error!!
                        report == null -> "Package downloads, pip/npm/Cargo/Go caches, proot distros and build outputs inside Termux."
                        else -> "Termux uses ${report.totalBytes.humanBytes()} · ${report.reclaimableBytes.humanBytes()} can be cleaned"
                    },
                    fraction = report?.let { if (it.totalBytes > 0) it.reclaimableBytes.toFloat() / it.totalBytes else null },
                    busy = termux.auditing,
                    action = "Open",
                ) { navigate(Routes.TERMUX) }
            }
        }
    }
}

@Composable
private fun AccessRow(icon: ImageVector, title: String, status: String, ok: Boolean, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(if (ok) Icons.Rounded.CheckCircle else icon, if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun OverviewCard(
    icon: ImageVector,
    title: String,
    text: String,
    fraction: Float?,
    busy: Boolean,
    action: String?,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit,
) {
    ReviewCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                fraction != null -> UsageBar(fraction, color = MaterialTheme.colorScheme.tertiary)
            }
            if (action != null || secondary != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (secondary != null) OutlinedButton(onClick = onSecondary) { Text(secondary) }
                    if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
internal fun SizeText(bytes: Long) {
    Text(bytes.humanBytes(), style = MaterialTheme.typography.labelMedium)
}
