package app.parley.ui.situations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.blocking.ScreeningPreset
import app.parley.common.situations.SituationArea
import app.parley.common.situations.Situations
import app.parley.data.DataContainer
import app.parley.situations.SituationTriggers
import app.parley.ui.Banner

/**
 * "Set by Meeting · Turn off" at the top of a page whose values the Situation on now sets ([Situations.overrides]):
 * Blocking & screening, Calls › Answering and During calls, SIMs and Driving › Your car. Nothing while none is on, or
 * while the one on leaves this page's values as they are.
 */
@Composable
fun SituationOverrideBanner(vm: AppViewModel, area: SituationArea) {
    val context = LocalContext.current
    val state by vm.situations.state.collectAsStateWithLifecycle()
    val list by vm.situations.list.collectAsStateWithLifecycle()
    val on = state.activeId?.let { id -> list.firstOrNull { it.id == id } }
    if (on == null || !Situations.overrides(on, area)) return
    Banner(
        stringResource(R.string.sit_set_by, SituationTriggers.name(context, on)),
        icon = Icons.Rounded.Tune,
        action = stringResource(R.string.action_turn_off),
        onAction = { vm.situations.turnOff() },
    )
}

/** Quiet nights is the Night Situation: what Blocking & screening's setups and the first run's basics do to it. */
object NightSituation {
    /** Applies [preset]'s part in the Night Situation ([ScreeningPreset.situation]); nothing for the setups that leave it alone. */
    suspend fun applyPreset(c: DataContainer, preset: ScreeningPreset) {
        val night = c.situations.list.value.firstOrNull { it.id == Situations.NIGHT } ?: return
        val next = preset.situation(night) ?: return
        if (next == night) return
        c.situations.save(next)
        // Inside its window already: on now, not only at the next call.
        c.situations.reconcile()
    }
}
