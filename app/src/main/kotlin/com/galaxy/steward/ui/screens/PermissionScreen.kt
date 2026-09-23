package com.galaxy.steward.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.galaxy.steward.data.StorageAccess
import com.galaxy.steward.ui.components.IconBadge

@Composable
fun PermissionScreen(onLegacyResult: () -> Unit) {
    val context = LocalContext.current
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onLegacyResult() }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        IconBadge(Icons.Rounded.FolderSpecial, MaterialTheme.colorScheme.primary, size = 80)
        Text("Let Koa look after your storage", style = MaterialTheme.typography.headlineMedium)
        Text(
            "To find duplicates anywhere and file things into the right folders, Galaxy Steward needs Android's " +
                "\"All files access\". Here is what it does with it:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Promise(Icons.Rounded.Search, "Read-only until you approve", "Scans only look. Every change is shown to you first.")
        Promise(Icons.Rounded.VerifiedUser, "Verified byte for byte", "Duplicates are re-checked with SHA-256 immediately before removal; the kept copy is never touched.")
        Promise(Icons.AutoMirrored.Rounded.Undo, "Everything is undoable", "Each run is journaled. Clutter goes to a quarantine first.")
        Promise(Icons.Rounded.Lock, "Hands off what matters", "Android/data, app media, projects, Git repositories, keys and credentials are never moved or deleted.")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    legacyLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                } else {
                    try {
                        context.startActivity(StorageAccess.allFilesAccessIntent(context))
                    } catch (_: ActivityNotFoundException) {
                        StorageAccess.fallbackAccessIntent()?.let { context.startActivity(it) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant all files access") }
        Text(
            "Nothing leaves your phone: the app has no internet permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
