package app.parley.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.KitStrings
import app.parley.ui.LocalKitStrings

/** Gives core/ui's shared components (top bar Back, dialog Cancel…) the app's translated words. */
@Composable
fun ProvideAppKit(content: @Composable () -> Unit) {
    val strings = KitStrings(
        back = stringResource(R.string.set_back),
        cancel = stringResource(R.string.set_cancel),
        change = stringResource(R.string.set_action_change),
        close = stringResource(R.string.main_close),
    )
    CompositionLocalProvider(LocalKitStrings provides strings, content = content)
}
