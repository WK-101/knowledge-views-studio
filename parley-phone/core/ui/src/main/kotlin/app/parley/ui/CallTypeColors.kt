package app.parley.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.common.ux.CallHue

/**
 * U3: fixed call colours, not taken from the (dynamic) colour scheme, with a light and a dark value each so they
 * keep enough contrast on both. Shown as the icon colour on a circle tinted with the same hue ([CallTypeBadge]).
 */
object CallTypeColors {
    private val light = mapOf(
        CallHue.INCOMING to Color(0xFF1B7F4B),
        CallHue.OUTGOING to Color(0xFF1F5FBF),
        CallHue.MISSED to Color(0xFFC62828),
        CallHue.BLOCKED to Color(0xFF8A5300),
        CallHue.NEUTRAL to Color(0xFF5F6368),
    )
    private val dark = mapOf(
        CallHue.INCOMING to Color(0xFF72D69C),
        CallHue.OUTGOING to Color(0xFF8AB4F8),
        CallHue.MISSED to Color(0xFFFF8A80),
        CallHue.BLOCKED to Color(0xFFFFB95C),
        CallHue.NEUTRAL to Color(0xFFBDC1C6),
    )

    fun of(hue: CallHue, dark: Boolean): Color = (if (dark) this.dark else light).getValue(hue)

    /** The colour for [hue] in the current theme (light or dark, judged from the surface, so "Theme" is respected). */
    @Composable
    fun of(hue: CallHue): Color = of(hue, isDarkSurface())

    @Composable
    internal fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
}

/** U3: a call-direction icon in its fixed colour on a circle tinted with the same hue. */
@Composable
fun CallTypeBadge(icon: ImageVector, hue: CallHue, modifier: Modifier = Modifier, size: Dp = 32.dp, contentDescription: String? = null) {
    val dark = CallTypeColors.isDarkSurface()
    val color = CallTypeColors.of(hue, dark)
    Box(
        modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (dark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(size * 0.6f))
    }
}
