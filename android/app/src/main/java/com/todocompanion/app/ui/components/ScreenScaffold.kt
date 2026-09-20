package com.todocompanion.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * U1 — the single screen chrome for the app. Before this, ~26 screens hand-rolled a byte-identical
 * `Scaffold { TopAppBar(expandedHeight = 52.dp, navigationIcon = back-arrow, title = ellipsis) }` block
 * (the `expandedHeight = 52.dp` literal alone appeared 36×, and the back-icon `IconButton` was duplicated
 * in 24 places), so any change to app chrome — its density, the back-button a11y label, insets, title
 * overflow — was a 26-file edit and one missed copy silently drifted. The abstraction already existed as a
 * private `LSScaffold` trapped in LifeSystemsScreens.kt; this promotes it to the shared library.
 *
 * All feature screens with a back-arrow top bar should use this. Pass [actions] for trailing icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KairoScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { KairoTopBar(title = title, onBack = onBack, actions = actions) },
        content = content,
    )
}

/** Just the app-standard top bar (52dp, back arrow, ellipsised title), for screens that own their own
 *  Scaffold body/FAB but still want the one canonical chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KairoTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        expandedHeight = 52.dp,
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { actions() },
    )
}
