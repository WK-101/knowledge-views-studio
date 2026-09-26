package com.wkhan.hexis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The Hexis brand mark — "four into one": four chevrons (tasks · habits · time · notes) converging on
 * a single centre, a compass of the four modules pointing to one bearing.
 *
 * Theme-aware by design: [stroke] and [core] default to the current Material theme, so the mark
 * follows the app's selected palette (light / dark / AMOLED) and accent colour everywhere it's shown.
 * Pass explicit colours when it sits on a coloured tile (e.g. onPrimary on a primary background).
 *
 * Mirrors the launcher icon's foreground exactly (108-unit design space, scaled to the given size).
 */
@Composable
fun HexisMark(
    modifier: Modifier = Modifier,
    stroke: Color = MaterialTheme.colorScheme.onSurface,
    core: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        val s = size.minDimension / 108f
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        // Faint halo behind the core (tinted from the core colour).
        drawCircle(core.copy(alpha = 0.18f), radius = 12f * s, center = o(54f, 54f))
        val st = Stroke(width = 7.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun chevron(ax: Float, ay: Float, apx: Float, apy: Float, bx: Float, by: Float) {
            val p = Path().apply {
                moveTo(ax * s, ay * s); lineTo(apx * s, apy * s); lineTo(bx * s, by * s)
            }
            drawPath(p, stroke, style = st)
        }
        chevron(41f, 26f, 54f, 39f, 67f, 26f) // N — tasks
        chevron(82f, 41f, 69f, 54f, 82f, 67f) // E — habits
        chevron(41f, 82f, 54f, 69f, 67f, 82f) // S — time
        chevron(26f, 41f, 39f, 54f, 26f, 67f) // W — notes
        // The core — the one point the four resolve to.
        drawCircle(core, radius = 6.5f * s, center = o(54f, 54f))
    }
}
