package app.parley.ui.calls

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyTag

/**
 * "From the network": beside a name the mobile network sent with a call, so it never passes for a name you saved
 * (Recents and its menu, the number's page, Recall, the block list). The quiet outlined tag of Recents' Simple chips;
 * TalkBack reads its words.
 */
@Composable
fun NetworkNameTag(modifier: Modifier = Modifier) {
    ParleyTag(stringResource(R.string.network_name_tag), modifier, outlined = true)
}

/** [name] as TalkBack says it in a label ("Call %s"): "Ravi Kumar, name from the network" when [fromNetwork]. */
@Composable
fun networkNameSpoken(name: String, fromNetwork: Boolean): String =
    if (fromNetwork) stringResource(R.string.network_name_spoken, name) else name
