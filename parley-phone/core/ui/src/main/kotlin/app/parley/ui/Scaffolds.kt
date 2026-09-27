package app.parley.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Material's Scaffold with the app's snackbar in it; use it for every screen. */
@Composable
fun ParleyScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = { ScreenSnackbarHost() },
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier, topBar = topBar, bottomBar = bottomBar, snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton, floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor, contentColor = contentColor, contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

/**
 * A settings screen: large title that collapses as you scroll (with the scroll-linked tint), grouped content
 * with [Spacing.groupGap] between groups, and room for the navigation bar at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    back: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ParleyScaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { ParleyTopBar(title, onBack = back, actions = { actions() }, scrollBehavior = scroll, large = true) },
    ) { p ->
        val dir = LocalLayoutDirection.current
        Column(
            Modifier.fillMaxSize()
                .padding(top = p.calculateTopPadding(), start = p.calculateStartPadding(dir), end = p.calculateEndPadding(dir))
                .verticalScroll(rememberScrollState())
                // Scrolls behind the navigation bar, and the last row can still scroll above it.
                .padding(bottom = p.calculateBottomPadding() + Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.groupGap),
        ) {
            Spacer(Modifier.height(0.dp))
            content()
        }
    }
}
