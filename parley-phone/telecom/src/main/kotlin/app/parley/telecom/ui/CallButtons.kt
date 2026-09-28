package app.parley.telecom.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.telecom.R
import app.parley.ui.CallColors
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.animatedCorners

/**
 * The call screen's button language (docs/CALL_SCREEN_DESIGN.md): every button is a filled container whose corners
 * morph on a spring, round at rest, squarer when pressed, and a rounded square while switched on, with the label
 * underneath. The sizes live here so the grid, the incoming screen and the call-waiting sheet stay in step.
 */
internal object CallButtonSize {
    /** A grid control: a 64 dp tall pill, up to 96 dp wide. */
    val controlHeight: Dp = 64.dp
    val controlMaxWidth: Dp = 96.dp

    /** The corners a control morphs between. */
    val restCorner: Dp = 32.dp
    val selectedCorner: Dp = 20.dp
    val pressedCorner: Dp = 14.dp

    /** Answer and decline on the incoming screen; the call-waiting sheet uses [small]. */
    val action: Dp = 80.dp
    val small: Dp = 64.dp

    /** End call: a wide pill, the biggest target on the screen. */
    val endWidth: Dp = 136.dp
    val endHeight: Dp = 72.dp

    /** Quiet secondary actions (Reply, Silence, ⋮): the minimum touch height. */
    val secondaryHeight: Dp = 48.dp
}

/**
 * One in-call control. [spoken] is the stable name TalkBack reads; toggles add their state, so TalkBack says
 * "Mute, on" rather than a label that flips between "Mute" and "Muted".
 */
internal data class ControlSpec(
    val icon: ImageVector,
    val label: String,
    val spoken: String,
    val toggle: Boolean = false,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** A grid control: pill container, morphing to a rounded square while on (filled with the primary colour). */
@Composable
internal fun CallControlButton(spec: ControlSpec, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        when {
            pressed && spec.enabled -> CallButtonSize.pressedCorner
            spec.active -> CallButtonSize.selectedCorner
            else -> CallButtonSize.restCorner
        },
        ParleyMotion.fastSpatial(), label = "corner",
    )
    val container by animateColorAsState(if (spec.active) scheme.primary else scheme.surfaceContainerHighest, ParleyMotion.effects(), label = "container")
    val content by animateColorAsState(
        when {
            !spec.enabled -> scheme.onSurface.copy(alpha = DISABLED)
            spec.active -> scheme.onPrimary
            else -> scheme.onSurface
        },
        ParleyMotion.effects(), label = "content",
    )
    val on = stringResource(R.string.tc_on)
    val off = stringResource(R.string.tc_off)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = CallButtonSize.controlMaxWidth)
                .height(CallButtonSize.controlHeight)
                .clip(animatedCorners(corner))
                .background(if (spec.enabled) container else scheme.surfaceContainerHigh)
                .clickable(
                    source, indication = LocalIndication.current, enabled = spec.enabled,
                    role = if (spec.toggle) Role.Switch else Role.Button, onClick = spec.onClick,
                )
                .semantics {
                    contentDescription = spec.spoken
                    if (spec.toggle) stateDescription = if (spec.active) on else off
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(spec.icon, null, tint = content, modifier = Modifier.size(28.dp))
        }
        Text(
            spec.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (spec.enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

/** Controls laid out [columns] to a row, every cell the same width, rows [Spacing.l] apart. */
@Composable
internal fun ControlRows(specs: List<ControlSpec>, columns: Int, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        specs.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { CallControlButton(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A round, coloured call action (Answer, Decline, Hold & answer…): a circle that squares off a little while pressed.
 * [halo] draws a soft breathing ring behind it (the answer button while it rings; still with animations off).
 */
@Composable
internal fun CallActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = CallButtonSize.action,
    spoken: String = label,
    halo: (() -> Float)? = null,
    ink: Color = Color.White,
    below: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) size * 0.3f else size / 2, ParleyMotion.fastSpatial(), label = "corner")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, ParleyMotion.fastSpatial(), label = "scale")
    Column(modifier.widthIn(min = size + Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    val h = halo?.invoke() ?: 0f
                    if (h > 0f) drawCircle(color.copy(alpha = 0.28f * (1f - h)), radius = this.size.minDimension / 2 * (1f + 0.35f * h))
                }
                .scale(scale)
                .clip(animatedCorners(corner))
                .background(color)
                .clickable(source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = spoken },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = ink, modifier = Modifier.size(size * 0.42f)) }
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
            maxLines = 2, modifier = Modifier.padding(top = Spacing.s),
        )
        below?.invoke()
    }
}

/** End call: the wide red pill, centred at the bottom within thumb reach. */
@Composable
internal fun EndCallButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val endCall = stringResource(R.string.incall_end_call)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 22.dp else CallButtonSize.endHeight / 2, ParleyMotion.fastSpatial(), label = "corner")
    val width by animateDpAsState(if (pressed) CallButtonSize.endWidth + 12.dp else CallButtonSize.endWidth, ParleyMotion.fastSpatial(), label = "width")
    Box(
        modifier
            .size(width = width, height = CallButtonSize.endHeight)
            .clip(animatedCorners(corner))
            .background(CallColors.Decline)
            .clickable(source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = endCall },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

/** A quiet secondary action under the caller (Reply, Silence): a tonal pill with an icon and a short label. */
@Composable
internal fun SecondaryAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, spoken: String = label) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = CallButtonSize.secondaryHeight)
            .clip(ParleyShapes.pill)
            .background(scheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (spoken != label) contentDescription = spoken }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val DISABLED = 0.38f
