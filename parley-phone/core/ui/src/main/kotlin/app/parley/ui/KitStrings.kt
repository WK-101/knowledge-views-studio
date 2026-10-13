package app.parley.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource

/**
 * The generic words the shared components need ("Back", "Cancel", the state of a part that opens in place, the ⋮
 * button's name…). The app provides its translated texts; these
 * fall back to core/ui's own (English) strings in a window that doesn't.
 */
@Immutable
data class KitStrings(
    val back: String,
    val cancel: String,
    val change: String,
    val close: String,
    val expanded: String,
    val collapsed: String,
    val unfold: String,
    val fold: String,
    val moreOptions: String,
)

val LocalKitStrings = staticCompositionLocalOf<KitStrings?> { null }

@Composable
fun kitStrings(): KitStrings = LocalKitStrings.current ?: KitStrings(
    back = stringResource(R.string.ui_back),
    cancel = stringResource(R.string.ui_cancel),
    change = stringResource(R.string.ui_change),
    close = stringResource(R.string.ui_close),
    expanded = stringResource(R.string.ui_expanded),
    collapsed = stringResource(R.string.ui_collapsed),
    unfold = stringResource(R.string.ui_unfold),
    fold = stringResource(R.string.ui_fold),
    moreOptions = stringResource(R.string.ui_more_options),
)
