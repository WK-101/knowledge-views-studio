package com.cairn.reader.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
fun CairnRoot(
    openItemId: String? = null,
    onOpenConsumed: () -> Unit = {},
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val prefs by appViewModel.preferences.collectAsStateWithLifecycle()

    val dark = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val accent = runCatching { com.cairn.reader.ui.theme.AppAccent.valueOf(prefs.appAccent) }
        .getOrDefault(com.cairn.reader.ui.theme.AppAccent.DEFAULT)
    CairnTheme(darkTheme = dark, dynamicColor = prefs.dynamicColor, accent = accent, trueBlack = prefs.trueBlack) {
        if (!prefs.seenOnboarding) {
            OnboardingScreen(onGetStarted = { appViewModel.markOnboardingSeen() })
            return@CairnTheme
        }
        val navController = rememberNavController()
        val openWeb: (String) -> Unit = { url -> navController.navigate("web/${WebRoute.encode(url)}") }
        // A notification tap arrives as openItemId — open that article once.
        androidx.compose.runtime.LaunchedEffect(openItemId) {
            openItemId?.let {
                navController.navigate("reader/$it")
                onOpenConsumed()
            }
        }
        // One consistent motion for every screen transition: a detail slides in from the end and
        // back out to it, cross-fading so the whole app feels of a piece rather than stitched together.
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = { fadeIn(tween(220)) + slideIntoContainer(SlideDirection.Start, tween(220)) },
            exitTransition = { fadeOut(tween(180)) + slideOutOfContainer(SlideDirection.Start, tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) + slideIntoContainer(SlideDirection.End, tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + slideOutOfContainer(SlideDirection.End, tween(180)) },
        ) {
            composable("home") {
                CairnApp(
                    onOpenItem = { itemId -> navController.navigate("reader/$itemId") },
                    onOpenWeb = openWeb,
                    onTeach = { url -> navController.navigate("picker/${WebRoute.encode(url)}") },
                )
            }
            composable(
                route = "reader/{itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            ) {
                ReaderScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWeb = openWeb,
                    // Flow to a neighbour article, replacing the current reader so Back still
                    // returns to the list rather than walking back through every article read.
                    onOpenItem = { neighbor ->
                        navController.navigate("reader/$neighbor") {
                            popUpTo("reader/{itemId}") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = "picker/{data}",
                arguments = listOf(navArgument("data") { type = NavType.StringType }),
            ) { entry ->
                com.cairn.reader.ui.picker.SelectorPickerScreen(
                    url = WebRoute.decode(entry.arguments?.getString("data").orEmpty()),
                    onBack = { navController.popBackStack() },
                    onCreated = { navController.popBackStack() },
                )
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
