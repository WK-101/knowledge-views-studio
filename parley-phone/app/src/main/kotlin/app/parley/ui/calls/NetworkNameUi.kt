package app.parley.ui.calls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.ui.ParleyShapes

/**
 * "From the network": beside a name the mobile network sent with a call, so it never passes for a name you saved
 * (Recents and its menu, the number's page, Recall, the block list). The quiet outlined tag of Recents' Simple chips;
 * TalkBack reads its words.
 */
@Composable
fun NetworkNameTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = ParleyShapes.tag,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            stringResource(R.string.network_name_tag),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** [name] as TalkBack says it in a label ("Call %s"): "Ravi Kumar, name from the network" when [fromNetwork]. */
@Composable
fun networkNameSpoken(name: String, fromNetwork: Boolean): String =
    if (fromNetwork) stringResource(R.string.network_name_spoken, name) else name
