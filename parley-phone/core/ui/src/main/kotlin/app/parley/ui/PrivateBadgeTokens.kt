package app.parley.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The private-contact mark's look, in one place: every list draws [PrivateBadge] from these. */
object PrivateBadgeTokens {
    /** The round badge, on the bottom end of the avatar. */
    val size: Dp = 18.dp

    /** The lock inside it. */
    val iconSize: Dp = 12.dp

    val icon: ImageVector get() = Icons.Rounded.Lock

    /** Where it sits on the avatar it marks. */
    val position: Alignment = Alignment.BottomEnd
}

/**
 * The small lock on a private contact's photo, the one sign in a list that other apps can't see them: Contacts,
 * Favourites, the Circle, Recents, the To call list and the keypad's results. Put it in a [Box] with the avatar,
 * aligned to [PrivateBadgeTokens.position]. TalkBack reads "Private contact".
 */
@Composable
fun PrivateBadge(modifier: Modifier = Modifier, contentDescription: String? = stringResource(R.string.ui_private_contact)) {
    Box(
        modifier.size(PrivateBadgeTokens.size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PrivateBadgeTokens.icon, contentDescription, Modifier.size(PrivateBadgeTokens.iconSize), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** [avatar] with the private-contact lock on it when [private]: the same mark in every list. */
@Composable
fun PrivateMarked(private: Boolean, modifier: Modifier = Modifier, avatar: @Composable () -> Unit) {
    Box(modifier) {
        avatar()
        if (private) PrivateBadge(Modifier.align(PrivateBadgeTokens.position))
    }
}
