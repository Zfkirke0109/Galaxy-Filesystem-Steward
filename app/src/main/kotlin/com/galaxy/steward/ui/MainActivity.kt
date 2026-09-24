package com.galaxy.steward.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.galaxy.steward.ui.components.ApplyProgressDialog
import com.galaxy.steward.ui.components.OutcomeDialog
import com.galaxy.steward.ui.screens.AppFolderBrowserScreen
import com.galaxy.steward.ui.screens.AppFoldersScreen
import com.galaxy.steward.ui.screens.AppStorageScreen
import com.galaxy.steward.ui.screens.AppsScreen
import com.galaxy.steward.ui.screens.DuplicatesScreen
import com.galaxy.steward.ui.screens.ExplorerScreen
import com.galaxy.steward.ui.screens.GoalScreen
import com.galaxy.steward.ui.screens.HistoryScreen
import com.galaxy.steward.ui.screens.HomeScreen
import com.galaxy.steward.ui.screens.JunkScreen
import com.galaxy.steward.ui.screens.OptimizeScreen
import com.galaxy.steward.ui.screens.OrganizeScreen
import com.galaxy.steward.ui.screens.PermissionScreen
import com.galaxy.steward.ui.screens.SettingsScreen
import com.galaxy.steward.ui.screens.TermuxBrowserScreen
import com.galaxy.steward.ui.screens.TermuxPackagesScreen
import com.galaxy.steward.ui.screens.TermuxProjectsScreen
import com.galaxy.steward.ui.screens.TermuxReposScreen
import com.galaxy.steward.ui.screens.TermuxScreen
import com.galaxy.steward.ui.theme.StewardTheme

object Routes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DUPLICATES = "duplicates"
    const val JUNK = "junk"
    const val ORGANIZE = "organize"
    const val OPTIMIZE = "optimize"
    const val APPS = "apps"
    const val APP_STORAGE = "app-storage"
    const val APP_FOLDERS = "app-folders"
    const val APP_BROWSER = "app-browser"
    const val TERMUX = "termux"
    const val TERMUX_BROWSER = "termux-browser"
    const val TERMUX_PACKAGES = "termux-packages"
    const val TERMUX_REPOS = "termux-repos"
    const val TERMUX_PROJECTS = "termux-projects"
    const val GOAL = "goal"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.EXPLORE, "Map", Icons.Rounded.Explore),
    Tab(Routes.APPS, "Apps", Icons.Rounded.Apps),
    Tab(Routes.HISTORY, "History", Icons.Rounded.History),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: StewardViewModel = viewModel()
            val prefs by vm.preferences.collectAsStateWithLifecycle()
            StewardTheme(dynamicColor = prefs.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StewardRoot(vm)
                }
            }
        }
    }
}

@Composable
private fun StewardRoot(vm: StewardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refreshAccess()
        vm.refreshHistory()
        onPauseOrDispose { }
    }

    if (!state.hasAccess) {
        PermissionScreen(onLegacyResult = vm::refreshAccess)
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showTabs = tabs.any { it.route == current }

    Scaffold(
        // Screens handle their own system-bar insets; this scaffold only reserves room for the tab bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.HOME) { HomeScreen(vm, state) { nav.navigate(it) } }
            composable(Routes.EXPLORE) { ExplorerScreen(vm, state) }
            composable(Routes.HISTORY) { HistoryScreen(vm, state) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, state) }
            composable(Routes.DUPLICATES) { DuplicatesScreen(vm, state) { nav.popBackStack() } }
            composable(Routes.JUNK) { JunkScreen(vm, state) { nav.popBackStack() } }
            composable(Routes.ORGANIZE) { OrganizeScreen(vm, state) { nav.popBackStack() } }
            composable(Routes.OPTIMIZE) { OptimizeScreen(vm, state) { nav.popBackStack() } }
            composable(Routes.APPS) { AppsScreen(vm) { nav.navigate(it) } }
            composable(Routes.APP_STORAGE) { AppStorageScreen(vm) { nav.popBackStack() } }
            composable(Routes.APP_FOLDERS) { AppFoldersScreen(vm, { nav.navigate(Routes.APP_BROWSER) }) { nav.popBackStack() } }
            composable(Routes.APP_BROWSER) { AppFolderBrowserScreen(vm) { nav.popBackStack() } }
            composable(Routes.TERMUX) { TermuxScreen(vm, { nav.navigate(it) }) { nav.popBackStack() } }
            composable(Routes.TERMUX_BROWSER) { TermuxBrowserScreen(vm) { nav.popBackStack() } }
            composable(Routes.TERMUX_PACKAGES) { TermuxPackagesScreen(vm) { nav.popBackStack() } }
            composable(Routes.TERMUX_REPOS) { TermuxReposScreen(vm) { nav.popBackStack() } }
            composable(Routes.TERMUX_PROJECTS) { TermuxProjectsScreen(vm) { nav.popBackStack() } }
            composable(Routes.GOAL) { GoalScreen(vm, state) { nav.popBackStack() } }
        }
    }

    state.applying?.let { ApplyProgressDialog(it, vm::cancelApply) }
    state.outcome?.let { outcome ->
        OutcomeDialog(
            outcome = outcome,
            onUndo = { runId, title ->
                vm.dismissOutcome()
                vm.rollback(runId, title)
            },
            onDismiss = vm::dismissOutcome,
        )
    }
}
