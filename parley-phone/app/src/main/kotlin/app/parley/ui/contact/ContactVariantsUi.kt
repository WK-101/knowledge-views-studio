package app.parley.ui.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.parley.ui.ParleyShapes
import app.parley.ui.temporary.timeLeft
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
import app.parley.common.ux.Tips
import app.parley.ui.common.CoachMark
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
 * The header's status chips, each one short line: "Private" and "Temporary · 5 days left". The long form ("hidden from
 * other apps", "deletes itself on 3 May") is what TalkBack reads and what the options a tap opens say; squeezed into
 * the chip it wrapped onto two cramped lines. The chips sit side by side and wrap to a second row only when the
 * header is too narrow for both. The only place the page looks different for a variant; a tap opens the matching
 * choice in "Settings for this contact".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariantChips(variants: ContactVariants, onClick: (VariantChip) -> Unit, modifier: Modifier = Modifier) {
    val chips = variants.chips
    if (chips.isEmpty()) return
    val context = LocalContext.current
    val res = LocalResources.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            Modifier.padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            chips.forEach { chip ->
                when (chip) {
                    VariantChip.Private -> VariantChipView(
                        Icons.Rounded.Lock, stringResource(R.string.contact_variant_private_short), stringResource(R.string.contact_variant_private),
                    ) { onClick(chip) }
                    is VariantChip.Temporary -> VariantChipView(
                        Icons.Rounded.Timer,
                        stringResource(R.string.contact_variant_temporary_short, timeLeft(res, chip.expiresAt)),
                        stringResource(R.string.contact_variant_temporary, Format.fullDate(context, chip.expiresAt)),
                    ) { onClick(chip) }
                }
            }
        }
        // P18: what the chip means, where it first appears; private first when a contact is both.
        if (VariantChip.Private in chips) CoachMark(Tips.CONCEPT_PRIVATE, stringResource(R.string.tip_concept_private))
        else CoachMark(Tips.CONCEPT_TEMPORARY, stringResource(R.string.tip_concept_temporary))
    }
}

/** One compact tonal chip: icon and one line of text, 32 dp tall inside a 48 dp target. */
@Composable
private fun VariantChipView(icon: ImageVector, text: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ParleyShapes.pill,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.minimumInteractiveComponentSize().semantics { contentDescription = description },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = Spacing.s, end = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    if (access == PrivateAccess.OPEN || access == PrivateAccess.OPENING) return
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
                PrivateAccess.OPEN, PrivateAccess.OPENING -> Unit
            }
        }
    }
}
