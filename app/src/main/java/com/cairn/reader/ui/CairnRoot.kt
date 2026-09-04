package com.cairn.reader.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cairn.reader.ui.reader.ReaderScreen

/** Top-level navigation: the tabbed home shell and the full-screen reader. */
@Composable
fun CairnRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            CairnApp(onOpenItem = { itemId -> navController.navigate("reader/$itemId") })
        }
        composable(
            route = "reader/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) {
            ReaderScreen(onBack = { navController.popBackStack() })
        }
    }
}
