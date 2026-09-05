package com.cairn.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cairn.reader.data.prefs.ThemeMode
import com.cairn.reader.ui.discover.DiscoverScreen
import com.cairn.reader.ui.feeds.FeedsScreen
import com.cairn.reader.ui.notebook.NotebookScreen
import com.cairn.reader.ui.onboarding.OnboardingScreen
import com.cairn.reader.ui.reader.ReaderScreen
import com.cairn.reader.ui.search.SearchScreen
import com.cairn.reader.ui.settings.OfflineScreen
import com.cairn.reader.ui.theme.CairnTheme
import com.cairn.reader.ui.web.WebRoute
import com.cairn.reader.ui.web.WebScreen

/** Applies the user's theme preference, then hosts navigation. */
@Composable
fun CairnRoot() {
    val appViewModel: AppViewModel = hiltViewModel()
    val prefs by appViewModel.preferences.collectAsStateWithLifecycle()

    val dark = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    CairnTheme(darkTheme = dark, dynamicColor = prefs.dynamicColor) {
        if (!prefs.seenOnboarding) {
            OnboardingScreen(onGetStarted = { appViewModel.markOnboardingSeen() })
            return@CairnTheme
        }
        val navController = rememberNavController()
        val openWeb: (String) -> Unit = { url -> navController.navigate("web/${WebRoute.encode(url)}") }
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                CairnApp(
                    onOpenItem = { itemId -> navController.navigate("reader/$itemId") },
                    onOpenNotebook = { navController.navigate("notebook") },
                    onOpenWeb = openWeb,
                    onOpenSearch = { navController.navigate("search") },
                    onOpenFeeds = { navController.navigate("feeds") },
                    onOpenOffline = { navController.navigate("offline") },
                    onOpenDiscover = { navController.navigate("discover") },
                )
            }
            composable(
                route = "reader/{itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            ) {
                ReaderScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWeb = openWeb,
                )
            }
            composable("notebook") {
                NotebookScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { itemId -> navController.navigate("reader/$itemId") },
                )
            }
            composable("search") {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { itemId -> navController.navigate("reader/$itemId") },
                )
            }
            composable("feeds") {
                FeedsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWeb = openWeb,
                )
            }
            composable("offline") {
                OfflineScreen(onBack = { navController.popBackStack() })
            }
            composable("discover") {
                DiscoverScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "web/{data}",
                arguments = listOf(navArgument("data") { type = NavType.StringType }),
            ) { entry ->
                WebScreen(
                    url = WebRoute.decode(entry.arguments?.getString("data").orEmpty()),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
