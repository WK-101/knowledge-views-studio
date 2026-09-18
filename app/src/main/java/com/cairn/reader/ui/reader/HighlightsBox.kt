package com.cairn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cairn.reader.R
import com.cairn.reader.data.db.HighlightEntity

/**
 * A foldable "Highlights" panel shown at the top of the reader (below the title/meta/divider), only
 * when the page has highlights. Lists every highlight on the page — article passages and transcript
 * excerpts alike — each with its own actions (recolour, copy, share, delete, and tap-to-manage for the
 * note). It reads offline: transcript excerpts show here without keeping the whole transcript. Styled
 * from the reader [palette] so it matches the current reading theme (sepia, dark, true-black, …).
 *
 * [defaultExpanded] seeds the fold state from the user's setting; they can still fold/unfold per read.
 */
@Composable
internal fun HighlightsBox(
    highlights: List<HighlightEntity>,
    palette: ReaderPalette,
    defaultExpanded: Boolean,
    onManage: (HighlightEntity) -> Unit,
    onSetColor: (HighlightEntity, Int) -> Unit,
    onCopy: (HighlightEntity) -> Unit,
    onShare: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
) {
    if (highlights.isEmpty()) return
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    // Stable order: as they occur (transcript excerpts by time, article by anchor) via creation time.
    val ordered = remember(highlights) { highlights.sortedBy { it.createdAt } }
    val hair = palette.secondary.copy(alpha = 0.22f)

    Column(
        Modifier
            .padding(horizontal = ReaderHPad)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.secondary.copy(alpha = 0.06f))
            .border(1.dp, hair, RoundedCornerShape(14.dp)),
    ) {
        // Header — tap anywhere to fold/unfold.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.FormatQuote, contentDescription = null, tint = palette.secondary, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.highlights),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
            )
            Text(
                pluralStringResource(R.plurals.annotation_count, ordered.size, ordered.size),
                style = MaterialTheme.typography.labelMedium,
                color = palette.secondary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = palette.secondary,
            )
        }
        if (expanded) {
            HorizontalDivider(color = hair)
            ordered.forEachIndexed { i, h ->
                if (i > 0) HorizontalDivider(color = hair, modifier = Modifier.padding(start = 40.dp))
                HighlightRow(h, palette, onManage, onSetColor, onCopy, onShare, onDelete)
            }
        }
    }
}

@Composable
private fun HighlightRow(
    h: HighlightEntity,
    palette: ReaderPalette,
    onManage: (HighlightEntity) -> Unit,
    onSetColor: (HighlightEntity, Int) -> Unit,
    onCopy: (HighlightEntity) -> Unit,
    onShare: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
) {
    var colorMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    // Transcript excerpts carry a "[m:ss]" note that reads as a timestamp; show it as a subtitle.
    val subtitle = h.note?.takeIf { it.isNotBlank() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Colour dot — tap to recolour.
        Box {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(h.color))
                    .border(1.dp, palette.secondary.copy(alpha = 0.4f), CircleShape)
                    .clickable { colorMenu = true },
            )
            DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HighlightColors.all.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (c == h.color) 2.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { onSetColor(h, c); colorMenu = false },
                        )
                    }
                }
            }
        }
        // Quote (+ optional note/timestamp) — tap to manage (edit note / recolour / remove).
        Column(Modifier.weight(1f).clickable { onManage(h) }) {
            Text(
                h.quote.trim(),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = palette.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(0.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        // Overflow actions.
        Box {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = palette.secondary,
                modifier = Modifier.size(22.dp).clip(CircleShape).clickable { overflow = true },
            )
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = { overflow = false; onCopy(h) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                    onClick = { overflow = false; onShare(h) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { overflow = false; onDelete(h) },
                )
            }
        }
    }
}
