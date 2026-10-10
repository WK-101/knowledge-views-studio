package app.parley.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.common.ListDensity

/** Avatar size in lists of people and calls, by the list density setting (rows shrink with [listDensity]). */
@Composable
fun avatarSize(): Dp = if (LocalDensityPref.current == ListDensity.COMPACT) 36.dp else 44.dp

/**
 * An empty list. Say whether nothing matches a search ("No matches for …") or nothing is there yet, and offer
 * one clear way on ([action], [onAction]).
 */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.l), textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.s), textAlign = TextAlign.Center,
            )
        }
        if (action != null && onAction != null) {
            FilledTonalButton(onAction, Modifier.padding(top = Spacing.l)) { Text(action) }
        }
    }
}

/** Bold-highlights [ranges] of [text] (T9 / search matches). */
fun highlight(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { r ->
        val start = r.first.coerceIn(0, text.length)
        val end = (r.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(style, start, end)
    }
}

val MatchStyle = SpanStyle(fontWeight = FontWeight.Bold)

/**
 * A strength bar for a passphrase ([level] 0–4, from `PassphraseStrength`) with its word ([label]) and an optional
 * [hint] below. Weak levels use the error colour; the label carries the meaning, not only the colour.
 */
@Composable
fun StrengthMeter(level: Int, label: String, hint: String? = null, modifier: Modifier = Modifier) {
    val color = when {
        level <= 1 -> MaterialTheme.colorScheme.error
        level == 2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LinearProgressIndicator(
            progress = { (level.coerceIn(0, 4) + 1) / 5f },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
