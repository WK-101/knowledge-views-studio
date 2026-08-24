package com.todocompanion.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.todocompanion.app.ui.screens.DoNextScreen
import com.todocompanion.app.ui.screens.OutlineScreen
import com.todocompanion.app.ui.screens.PlannerScreen
import com.todocompanion.app.ui.screens.QuickAddSheet
import com.todocompanion.app.ui.screens.SettingsScreen
import com.todocompanion.app.ui.screens.TaskDetailScreen
import com.todocompanion.app.ui.theme.AppTheme

private data class Tab(val route: String, val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("outline", "Outline", Icons.AutoMirrored.Filled.FormatListBulleted),
    Tab("donext", "Do Next", Icons.Filled.Bolt),
    Tab("planner", "Planner", Icons.Filled.GridView),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val vm: AppViewModel = viewModel()
    val settings by vm.settings.collectAsState()

    AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        val nav = rememberNavController()
        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val onMainTab = TABS.any { it.route == route }
        var showQuickAdd by remember { mutableStateOf(false) }

        // Ask for notification permission once (Android 13+), so reminders can post.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) { permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }

        Scaffold(
            topBar = {
                if (onMainTab) {
                    TopAppBar(title = { Text(TABS.first { it.route == route }.title) })
                }
            },
            bottomBar = {
                if (onMainTab) {
                    NavigationBar {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.title) },
                                label = { Text(tab.title) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (onMainTab && route != "settings") {
                    FloatingActionButton(onClick = { showQuickAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add task")
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "outline", modifier = Modifier.padding(padding)) {
                composable("outline") { OutlineScreen(vm, onOpenTask = { nav.navigate("detail/$it") }) }
                composable("donext") { DoNextScreen(vm, onOpenTask = { nav.navigate("detail/$it") }) }
                composable("planner") { PlannerScreen(vm, onOpenTask = { nav.navigate("detail/$it") }) }
                composable("settings") { SettingsScreen(vm) }
                composable("detail/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    TaskDetailScreen(vm, id, onBack = { nav.popBackStack() })
                }
            }
        }

        if (showQuickAdd) {
            QuickAddSheet(vm, onDismiss = { showQuickAdd = false })
        }
    }
}
