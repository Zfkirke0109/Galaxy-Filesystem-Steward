package com.galaxy.steward.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.galaxy.steward.ui.StewardViewModel

/**
 * Starts a scan. On Android 13+ the first scan also asks once for the notification permission, so a scan's
 * progress shows in the notification shade while you use other apps. The scan starts either way.
 */
@Composable
fun rememberScanStarter(vm: StewardViewModel): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vm.shouldAskForNotifications() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            vm.markAskedForNotifications()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.startScan()
    }
}
