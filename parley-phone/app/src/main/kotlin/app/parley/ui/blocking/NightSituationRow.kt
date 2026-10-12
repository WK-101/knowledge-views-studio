package app.parley.ui.blocking

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.common.situations.Situations
import app.parley.situations.SituationTriggers
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.situations.SituationRoutes
import app.parley.ui.situations.whoName

/**
 * The one night, beside off hours: the Night Situation (what the Quiet nights setup became), its times and who may
 * ring, opening its editor. Off hours stays the engine on this screen; Night sets it while it is on.
 */
@Composable
internal fun NightSituationRow(st: BlockingState, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val list by st.vm.situations.list.collectAsStateWithLifecycle()
    val night = list.firstOrNull { it.id == Situations.NIGHT } ?: return
    val sub = night.schedule?.let { sch ->
        stringResource(R.string.sit_night_row_sub, BlockingText.schedule(context, sch), night.who?.let { whoName(it) } ?: stringResource(R.string.sit_as_is))
    } ?: stringResource(R.string.sit_night_row_off)
    BlockingCard {
        LinkRow(SituationTriggers.name(context, night), sub, Icons.Rounded.Bedtime) { open(SituationRoutes.Edit(night.id)) }
    }
}
