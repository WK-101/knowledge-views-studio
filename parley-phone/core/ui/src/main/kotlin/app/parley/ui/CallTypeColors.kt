package app.parley.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.automirrored.rounded.CallMissedOutgoing
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.common.ux.CallClass
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

/** R4 (v3.3): the glyph of a call class. */
fun callClassVector(cls: CallClass): ImageVector = when (cls.glyph) {
    CallClass.Glyph.ARROW_IN -> Icons.AutoMirrored.Rounded.CallReceived
    CallClass.Glyph.ARROW_OUT -> Icons.AutoMirrored.Rounded.CallMade
    CallClass.Glyph.ARROW_OUT_UNANSWERED -> Icons.AutoMirrored.Rounded.CallMissedOutgoing
    CallClass.Glyph.ARROW_MISSED -> Icons.AutoMirrored.Rounded.CallMissed
    CallClass.Glyph.HANG_UP -> Icons.Rounded.CallEnd
    CallClass.Glyph.BLOCK -> Icons.Rounded.Block
    CallClass.Glyph.VOICEMAIL -> Icons.Rounded.Voicemail
    CallClass.Glyph.OTHER_DEVICE -> Icons.Rounded.Devices
    CallClass.Glyph.PHONE -> Icons.Rounded.Call
}

/** The colour of text or an icon drawn on a solid badge of [color]. */
private fun onSolid(dark: Boolean): Color = if (dark) Color(0xFF1B1B1F) else Color.White

/**
 * R4 (v3.3): a call's badge, told apart by shape as well as colour ([CallClass]): solid for calls to notice (missed
 * round, declined square), tonal for calls taken, outlined for calls you made, dashed for calls that didn't connect
 * here (no answer, answered elsewhere), a crossed square for blocked ones.
 */
@Composable
fun CallClassBadge(cls: CallClass, modifier: Modifier = Modifier, size: Dp = 32.dp, contentDescription: String? = null) {
    val dark = CallTypeColors.isDarkSurface()
    val color = CallTypeColors.of(cls.hue, dark)
    val shape = if (cls.form == CallClass.Form.SQUARE) RoundedCornerShape(size * 0.28f) else CircleShape
    val base = modifier.size(size).clip(shape)
    val box = when (cls.fill) {
        CallClass.Fill.SOLID -> base.background(color)
        CallClass.Fill.TONAL -> base.background(color.copy(alpha = if (dark) 0.22f else 0.14f))
        CallClass.Fill.OUTLINE -> base.border(1.5.dp, color, shape)
        CallClass.Fill.DASHED -> base.drawBehind {
            val stroke = 1.5.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))
            drawCircle(color, radius = this.size.minDimension / 2 - stroke / 2, style = Stroke(stroke, pathEffect = dash))
        }
    }
    Box(box, contentAlignment = Alignment.Center) {
        Icon(callClassVector(cls), contentDescription, tint = if (cls.fill == CallClass.Fill.SOLID) onSolid(dark) else color, modifier = Modifier.size(size * 0.58f))
    }
}

/**
 * R4 (v3.3): the order of a row's calls as tiny marks, oldest first: solid dots for calls to notice, rings for calls
 * you made, tonal dots for the others, squares for declined and blocked calls (shape again, not only colour).
 */
@Composable
fun CallSequenceDots(classes: List<CallClass>, modifier: Modifier = Modifier, dot: Dp = 7.dp) {
    val dark = CallTypeColors.isDarkSurface()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        classes.forEach { cls ->
            val color = CallTypeColors.of(cls.hue, dark)
            val shape = if (cls.form == CallClass.Form.SQUARE) RoundedCornerShape(1.5.dp) else CircleShape
            val m = Modifier.size(dot).clip(shape)
            Box(
                when (cls.fill) {
                    CallClass.Fill.SOLID -> m.background(color)
                    CallClass.Fill.TONAL -> m.background(color.copy(alpha = 0.55f))
                    CallClass.Fill.OUTLINE, CallClass.Fill.DASHED -> m.border(1.5.dp, color, shape)
                },
            )
        }
    }
}

/** R4 (v3.3): a talk's length as a short bar ([fraction] 0..1 from `CallGlance.durationFraction`). */
@Composable
fun CallDurationBar(fraction: Float, cls: CallClass, modifier: Modifier = Modifier, width: Dp = 36.dp) {
    val color = CallTypeColors.of(cls.hue)
    Box(modifier.width(width).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(color))
    }
}
