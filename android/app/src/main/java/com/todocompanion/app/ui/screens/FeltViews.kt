package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.FeltState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Track 1 (Unify) — the shared "how it felt" chart primitives. Lifted here from the Day Review so every
 * achievement surface (Momentum, Statistics, The Record, the Day Review itself) draws the felt lane with
 * the same, theme-correct code rather than reinventing chart drawing. Everything uses MaterialTheme
 * tokens only, so it reads correctly in light / dark / amoled.
 */

/** A per-day mood strip (1–5 → low…high), one height-scaled bar per day, muted where nothing was logged. */
@Composable
internal fun MoodStrip(moods: List<Int?>, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.tertiary
    val off = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.height(26.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        moods.forEach { s ->
            val frac = if (s != null) (s.coerceIn(1, 5) / 5f) else 0f
            Box(
                Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.14f)).clip(RoundedCornerShape(2.dp))
                    .background(if (s != null) on else off.copy(alpha = .5f)),
            )
        }
    }
}

/** A thin trend of recent 1–5 scores (day ratings, question scores): one bar per day, height-scaled to
 *  score/5, muted where no score was logged. */
@Composable
internal fun ScoreSparkline(scores: List<Int?>, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.height(26.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        scores.forEach { s ->
            val frac = if (s != null) (s.coerceIn(1, 5) / 5f) else 0f
            Box(
                Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.14f)).clip(RoundedCornerShape(2.dp))
                    .background(if (s != null) on else off.copy(alpha = .5f)),
            )
        }
    }
}

/** Track 1.1 — a compact "how it felt" readout: avg day-rating (stars + sparkline), evening mood (face +
 *  strip) and the emotion named most, from a [FeltState.FeltSummary]. Rendered as inner content — the
 *  caller supplies the card/heading.
 *
 *  [ratingTrend] / [moodTrend] default to the summary's own per-day trends but can be overridden — the year
 *  view passes its per-calendar-month roll-up so the shared readout draws the same sparkline it always did.
 *  [emotionMinCount] gates the dominant-emotion line (2 for a week/month, 3 for a year); [emotionShowDayCount]
 *  appends "(N days named it)" as the year view does. */
@Composable
internal fun FeltReadout(
    summary: FeltState.FeltSummary,
    modifier: Modifier = Modifier,
    ratingTrend: List<Int?> = summary.ratingTrend,
    moodTrend: List<Int?> = summary.moodTrend,
    emotionMinCount: Int = 2,
    emotionShowDayCount: Boolean = false,
) {
    if (!summary.hasData) {
        Text(
            "Rate a day or log your mood and this lane fills in — how the same stretch actually felt.",
            modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(modifier) {
        if (summary.ratedDays > 0) {
            val r = summary.avgRating.roundToInt().coerceIn(1, 5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("★".repeat(r) + "☆".repeat(5 - r), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("${feltOneDp(summary.avgRating)} avg · ${summary.ratedDays} rated", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            ScoreSparkline(ratingTrend, Modifier.fillMaxWidth())
        }
        if (summary.moodDays > 0) {
            if (summary.ratedDays > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(feltMoodFace(summary.avgMood.roundToInt()), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text("mood ${feltOneDp(summary.avgMood)} avg · ${summary.moodDays} day${if (summary.moodDays == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            MoodStrip(moodTrend, Modifier.fillMaxWidth())
        }
        if (summary.dominantEmotion.isNotBlank() && summary.dominantEmotionCount >= emotionMinCount) {
            Spacer(Modifier.height(8.dp))
            val word = summary.dominantEmotion.lowercase(Locale.getDefault())
            Text(
                if (emotionShowDayCount) "Most often, you felt $word (${summary.dominantEmotionCount} days named it)."
                else "Most often you felt $word.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun feltOneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun feltMoodFace(mood: Int): String = when (mood.coerceIn(0, 5)) {
    1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "•"
}
