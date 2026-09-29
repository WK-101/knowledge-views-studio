package app.parley.ui.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.ContactVariants
import app.parley.common.people.VariantChip
import app.parley.ui.SegmentedGroup
import app.parley.ui.Spacing
import app.parley.ui.common.Format

/**
 * The small lock on a private contact's photo in lists (Contacts, Favourites, the Circle, keypad results): the one
 * sign in a list that other apps can't see them. TalkBack reads "Private contact".
 */
@Composable
fun PrivateBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Lock, stringResource(R.string.contact_private_badge),
            Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The header's status chips: "Private · hidden from other apps" and "Temporary · deletes itself on …". The only
 * place the page looks different for a variant; a tap opens the matching choice in "Settings for this contact".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariantChips(variants: ContactVariants, onClick: (VariantChip) -> Unit, modifier: Modifier = Modifier) {
    val chips = variants.chips
    if (chips.isEmpty()) return
    val context = LocalContext.current
    FlowRow(modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally)) {
        chips.forEach { chip ->
            val (icon, text) = when (chip) {
                VariantChip.Private -> Icons.Rounded.Lock to stringResource(R.string.contact_variant_private)
                is VariantChip.Temporary -> Icons.Rounded.Timer to stringResource(R.string.contact_variant_temporary, Format.fullDate(context, chip.expiresAt))
            }
            AssistChip(
                onClick = { onClick(chip) },
                label = { Text(text, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}

/**
 * A private contact whose details can't be read right now, in its page: [PrivateAccess.LOCKED] offers the vault's
 * unlock (VaultCrypto's locking is unchanged: names and numbers work without it, everything else waits),
 * [PrivateAccess.UNAVAILABLE] a retry, and [PrivateAccess.LOST] "Keep what's left".
 */
@Composable
fun PrivateAccessRow(access: PrivateAccess, onUnlock: () -> Unit, onRetry: () -> Unit, onKeep: () -> Unit) {
    if (access == PrivateAccess.OPEN) return
    SegmentedGroup {
        item {
            when (access) {
                PrivateAccess.LOCKED -> InfoRow(
                    modifier = Modifier.clickable(onClick = onUnlock),
                    leading = { Icon(Icons.Rounded.Lock, null) },
                    headline = { Text(stringResource(R.string.vault_unlock_all)) },
                    supporting = { Text(stringResource(R.string.vault_unlock_all_summary)) },
                )
                PrivateAccess.UNAVAILABLE -> InfoRow(
                    modifier = Modifier.clickable(onClick = onRetry),
                    leading = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    headline = { Text(stringResource(R.string.vault_details_unavailable)) },
                    supporting = { Text(stringResource(R.string.vault_details_unavailable_summary)) },
                )
                PrivateAccess.LOST -> InfoRow(
                    leading = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    headline = { Text(stringResource(R.string.vault_details_lost)) },
                    supporting = { Text(stringResource(R.string.vault_details_lost_summary)) },
                    trailing = { TextButton(onKeep) { Text(stringResource(R.string.vault_details_keep)) } },
                )
                PrivateAccess.OPEN -> Unit
            }
        }
    }
}
