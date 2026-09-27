package app.parley.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

/**
 * The generic words the shared components need ("Back", "Cancel"…). The app provides its translated texts; these
 * fall back to core/ui's own (English) strings in a window that doesn't.
 */
@Immutable
data class KitStrings(val back: String, val cancel: String, val change: String, val close: String)

val LocalKitStrings = staticCompositionLocalOf<KitStrings?> { null }

@Composable
fun kitStrings(): KitStrings = LocalKitStrings.current ?: KitStrings(
    back = stringResource(R.string.ui_back),
    cancel = stringResource(R.string.ui_cancel),
    change = stringResource(R.string.ui_change),
    close = stringResource(R.string.ui_close),
)

/** The top bar's navigation button: Back (mirrored in right-to-left languages) or another [icon] such as Close. */
@Composable
fun BackButton(onBack: () -> Unit, label: String = kitStrings().back, icon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack) {
    IconButton(onBack) { Icon(icon, label) }
}

/**
 * Parley's top bar with a plain title: one line, Back on the start side when [onBack] is set, actions on the end.
 * [large] gives the collapsing large title of settings-like screens (pass an exit-until-collapsed [scrollBehavior]
 * and connect it to the Scaffold with `nestedScroll`); a pinned [scrollBehavior] tints the bar while content scrolls
 * under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParleyTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    large: Boolean = false,
    colors: TopAppBarColors? = null,
    backLabel: String? = null,
) {
    ParleyTopBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = { if (onBack != null) BackButton(onBack, backLabel ?: kitStrings().back) },
        actions = actions,
        scrollBehavior = scrollBehavior,
        large = large,
        colors = colors,
    )
}

/** [ParleyTopBar] with any [title] content (a search field, an avatar and name) and any [navigationIcon]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParleyTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    large: Boolean = false,
    colors: TopAppBarColors? = null,
) {
    if (large) {
        LargeTopAppBar(
            title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
            colors = colors ?: TopAppBarDefaults.largeTopAppBarColors(), scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(
            title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
            colors = colors ?: TopAppBarDefaults.topAppBarColors(), scrollBehavior = scrollBehavior,
        )
    }
}
