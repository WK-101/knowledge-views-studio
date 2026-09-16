package com.cairn.reader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The wrapping row of filter / scope chips that sits above a list on every list surface
 * (Read Later, Trash, Offline, Library). Before this each screen hand-rolled its own `FlowRow`
 * with a drifting gutter (12 vs 16) and vertical pad (4 vs 6); this fixes both to the standard
 * [Dimens.gutter] / [Dimens.xs] rhythm so the chips line up with the rows beneath them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipRow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = Dimens.xs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        content = content,
    )
}

/**
 * The title header at the top of an item's long-press action sheet (Read Later, Trash, Offline).
 * Was three near-identical copies of `Text(titleMedium) [+ subtitle] + HorizontalDivider + Spacer`.
 */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(start = Dimens.xl, end = Dimens.xl, top = Dimens.sm, bottom = if (subtitle == null) Dimens.sm else Dimens.xxs)
            .semantics { heading() },
    )
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.xl, end = Dimens.xl, bottom = Dimens.sm),
        )
    }
    HorizontalDivider()
    Spacer(Modifier.height(Dimens.sm))
}

/** The three section-label looks that used to be six private copies (see [SectionLabel]). */
enum class SectionLabelVariant { Group, Menu, Sheet }

/**
 * A small section heading, in one of three [variant]s. Before this, six screens each carried a
 * private label composable ([SectionLabelVariant.Group]: drawer/library/insights groupings, primary
 * + SemiBold; [SectionLabelVariant.Menu]: the inbox overflow menu, inset + onSurfaceVariant;
 * [SectionLabelVariant.Sheet]: reader/discover sheets, letter-spaced). All render as `labelMedium`
 * and expose a heading to accessibility. Callers pass any extra padding via [modifier].
 */
@Composable
internal fun SectionLabel(
    text: String,
    variant: SectionLabelVariant = SectionLabelVariant.Group,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when (variant) {
        SectionLabelVariant.Group -> Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier.semantics { heading() },
        )
        SectionLabelVariant.Menu -> Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = modifier
                .padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                .semantics { heading() },
        )
        SectionLabelVariant.Sheet -> Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            modifier = modifier.semantics { heading() },
        )
    }
}

/**
 * One row in a long-press action sheet: an icon + label, tappable across the full width. The list
 * screens each hand-rolled a private `ActionRow`/`OfflineAction` that was byte-for-byte this; they
 * now share it. Pass [destructive] for an error-colored (delete/remove) action.
 */
@Composable
fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val textColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconTint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.xl, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Dimens.iconInline))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = textColor)
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
