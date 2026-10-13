package app.parley.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.KitStrings
import app.parley.ui.LocalKitStrings
import app.parley.ui.R as KitR

/** Gives core/ui's shared components (top bar Back, dialog Cancel…) the app's translated words. */
@Composable
fun ProvideAppKit(content: @Composable () -> Unit) {
    val strings = KitStrings(
        back = stringResource(R.string.set_back),
        cancel = stringResource(R.string.dc_cancel),
        change = stringResource(R.string.action_change),
        close = stringResource(R.string.action_close),
        expanded = stringResource(KitR.string.ui_expanded),
        collapsed = stringResource(KitR.string.ui_collapsed),
        unfold = stringResource(R.string.action_unfold),
        fold = stringResource(R.string.action_fold),
        moreOptions = stringResource(R.string.label_more_options),
    )
    CompositionLocalProvider(LocalKitStrings provides strings, content = content)
}
