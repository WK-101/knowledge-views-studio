package app.parley.ui.contact

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.ui.Banner

/**
 * "Calls to Ana drop less on SIM 2 · Use SIM 2": sets the SIM remembered for their numbers in one tap. Closing it
 * answers it too, so the same suggestion never comes back.
 */
@Composable
internal fun SimAdviceBanner(ctx: ContactPageContext) {
    val advice by ctx.page.numberAdvice.collectAsStateWithLifecycle()
    val tip = advice.simTip ?: return
    val name = ctx.d.given.ifBlank { ctx.d.displayName }
    val done = stringResource(R.string.sim_advice_done, tip.simLabel, name)
    Banner(
        text = stringResource(R.string.sim_advice_text, name, tip.simLabel),
        icon = Icons.Rounded.SimCard,
        action = stringResource(R.string.edit_native_language_use, tip.simLabel),
        onAction = {
            ctx.page.answerSimTip(tip, accept = true)
            ctx.vm.toast(done)
        },
        onDismiss = { ctx.page.answerSimTip(tip, accept = false) },
        dismissLabel = stringResource(R.string.blk_no_thanks),
    )
}
