package com.obliviate.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.obliviate.app.ui.screens.AboutScreen
import com.obliviate.app.ui.screens.CleanScreen
import com.obliviate.app.ui.screens.DisposeScreen
import com.obliviate.app.ui.screens.HomeScreen
import com.obliviate.app.ui.screens.ShredScreen
import com.obliviate.app.ui.screens.WipeScreen

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.SpaceDashboard),
    WIPE("wipe", "Wipe", Icons.Rounded.DeleteSweep),
    SHRED("shred", "Shred", Icons.Rounded.Shield),
    CLEAN("clean", "Clean", Icons.Rounded.CleaningServices),
    ABOUT("about", "About", Icons.Rounded.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObliviateRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val title = when (currentRoute) {
        Dest.WIPE.route -> "Wipe free space"
        Dest.SHRED.route -> "Shred files"
        Dest.CLEAN.route -> "Clean junk"
        Dest.ABOUT.route -> "How secure is this?"
        "dispose" -> "Prepare for disposal"
        else -> "Obliviate"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            NavigationBar {
                val current = backStackEntry?.destination
                Dest.entries.forEach { dest ->
                    val selected = current?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.HOME.route) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } }
                )
            }
            composable(Dest.WIPE.route) { WipeScreen() }
            composable(Dest.SHRED.route) { ShredScreen() }
            composable(Dest.CLEAN.route) { CleanScreen() }
            composable(Dest.ABOUT.route) { AboutScreen() }
            composable("dispose") {
                DisposeScreen(
                    onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } }
                )
            }
        }
    }
}
