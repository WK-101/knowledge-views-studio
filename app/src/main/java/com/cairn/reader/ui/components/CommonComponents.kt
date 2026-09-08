package com.cairn.reader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cairn.reader.ui.theme.Dimens

/**
 * The centered empty / placeholder state shared by every list surface. Before this the same
 * icon-title-body Column was hand-written in ~13 screens with drifting icon sizes, title styles
 * and spacers; this is the single source of truth for how "nothing here" looks.
 *
 * @param action optional trailing control (e.g. a "Clear filters" TextButton) shown below the body.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.iconEmpty),
            )
            Spacer(Modifier.height(Dimens.md))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Dimens.lg))
            action()
        }
    }
}

/**
 * The thin, start-inset rule drawn between list entries app-wide. Replaces six near-identical
 * hand-written `HorizontalDivider(padding(start=16.dp), 0.5–0.6.dp, outlineVariant@0.5)` copies.
 */
@Composable
fun EntryDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = Dimens.gutter),
        thickness = Dimens.entryDivider,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * One row in a long-press action sheet: an icon + label, tappable across the full width. The four
 * list screens each hand-rolled a private `ActionRow`/`OfflineAction`/`NotebookActionRow` that was
 * byte-for-byte this; they now share it. Pass [tint] for a destructive (error-colored) action.
 */
@Composable
fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.xl, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.iconInline))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/**
 * The single monogram / letter-avatar palette. Feeds' `MONOGRAM_COLORS` and Library's `COVER_TINTS`
 * were two overlapping hand-maintained lists of the same colors; both now derive from this one.
 */
val MonogramPalette: List<Color> = listOf(
    Color(0xFF3F5E7A), Color(0xFF3E8E5A), Color(0xFFB98A2E), Color(0xFFB0553F),
    Color(0xFF6A5A8E), Color(0xFF2E8B94), Color(0xFF8E5A6A), Color(0xFF5A7A4E),
)

/** Deterministic accent color for [key] (a feed/item title), stable across recompositions. */
fun monogramColor(key: String): Color = MonogramPalette[(key.hashCode() and 0x7fffffff) % MonogramPalette.size]
