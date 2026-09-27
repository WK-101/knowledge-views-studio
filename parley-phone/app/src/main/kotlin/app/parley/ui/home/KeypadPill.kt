package app.parley.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.DockFold
import app.parley.common.SimAccount
import app.parley.common.calls.CallPill
import app.parley.ui.CallColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The pill's height; the bottom row around it keeps one height whatever the SIMs (C1: the keys never move). */
internal val CALL_PILL_HEIGHT = 56.dp

/** The side slots of the bottom row (keypad toggle, backspace), equal so the pill stays centred. */
private val SIDE_SLOT = 72.dp

/**
 * The keypad's bottom row, as on most phones: the keypad toggle, the green Call pill in the middle, and
 * backspace (a long press clears the number). The row's height and the pill's width depend only on the SIMs,
 * never on what is typed.
 */
@Composable
internal fun KeypadBottomRow(
    vm: AppViewModel,
    sims: List<SimAccount>,
    preferredSim: String?,
    hasInput: Boolean,
    keypadShown: Boolean,
    toggleLabel: String,
    onToggle: (() -> Unit)?,
    onCall: () -> Unit,
    onCallWith: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(CALL_PILL_HEIGHT + 16.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.width(SIDE_SLOT), contentAlignment = Alignment.Center) {
            if (onToggle != null) {
                RoundIconButton(toggleLabel, onClick = onToggle) {
                    Icon(Icons.Rounded.Dialpad, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (keypadShown) 1f else 0.8f))
                }
            }
        }
        CallPillView(vm, CallPill.segments(sims, preferredSim), onCall, onCallWith, Modifier.weight(1f, fill = false))
        Box(Modifier.width(SIDE_SLOT), contentAlignment = Alignment.Center) {
            BackspaceButton(hasInput, onDelete, onClear)
        }
    }
}

@Composable
private fun RoundIconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceButton(enabled: Boolean, onDelete: () -> Unit, onClear: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.main_delete)
    val clearLabel = stringResource(R.string.keypad_clear_number)
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = label,
                onLongClickLabel = clearLabel,
                onClick = onDelete,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClear() },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Always there (dimmed with nothing typed), so nothing in the row appears or moves while typing.
        Icon(
            Icons.AutoMirrored.Rounded.Backspace, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
        )
    }
}

/**
 * The green Call pill. No [segments]: one compact pill that follows the usual SIM rules. Otherwise one pill
 * split into a segment per SIM, each its own touch target ("Call with SIM 1 (Carrier)"), with a thin divider.
 */
@Composable
private fun CallPillView(
    vm: AppViewModel, segments: List<CallPill.Segment>, onCall: () -> Unit, onCallWith: (String) -> Unit, modifier: Modifier,
) {
    val shape = RoundedCornerShape(50)
    if (segments.isEmpty()) {
        val label = stringResource(R.string.main_call)
        Box(
            modifier.width(112.dp).height(CALL_PILL_HEIGHT).clip(shape).background(CallColors.Accept)
                .clickable(role = Role.Button, onClick = onCall)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        return
    }
    Row(
        modifier.widthIn(max = 124.dp * segments.size).height(CALL_PILL_HEIGHT).clip(shape).background(CallColors.Accept),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.55f)))
            PillSegment(vm, s, Modifier.weight(1f)) { onCallWith(s.simId) }
        }
    }
}

@Composable
private fun PillSegment(vm: AppViewModel, s: CallPill.Segment, modifier: Modifier, onClick: () -> Unit) {
    val slot = s.slot
    val carrier = s.carrier
    val slotName = slot?.let { stringResource(R.string.keypad_sim_slot, it) }
    val shown = s.label ?: slotName ?: carrier.orEmpty()
    val spoken = when {
        slot == null -> stringResource(R.string.keypad_call_with, carrier ?: shown)
        carrier != null -> stringResource(R.string.keypad_call_with_sim_carrier, slot, carrier)
        else -> stringResource(R.string.keypad_call_with_sim, slot)
    }
    val usual = stringResource(R.string.keypad_sim_usual)
    Row(
        modifier.fillMaxHeight()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = spoken
                if (s.preferred) stateDescription = usual
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // The plan badge stays a small dot on the icon.
        app.parley.ui.history.SimPlanBadge(vm, s.simId) { Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(6.dp))
        Text(
            shown, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (s.preferred) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------- Docked keypad fold

/** The spring the docked keypad folds and unfolds with (Material 3 Expressive's default spatial spring). */
private fun foldSpring() = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/**
 * The docked keypad's fold, 1 = unfolded, 0 = folded. It follows the finger while dragged (panel,
 * handle, folded button, list scroll) and otherwise springs to the state the home screen keeps ([open]).
 */
@Stable
internal class DockFoldState(open: Boolean, private val scope: CoroutineScope, private val density: Density) {
    var value by mutableFloatStateOf(if (open) 1f else 0f)
        private set

    /** The panel's full height (or width, side by side), as last measured. */
    var fullPx by mutableFloatStateOf(0f)

    /** The state it is heading to; the home screen's [open] follows it. */
    var target by mutableStateOf(open)
        private set

    var dragging by mutableStateOf(false)
        private set
    private var startedOpen = open
    private var job: Job? = null

    /** Called when a drag settles, with whether the panel ends up unfolded (the home screen keeps that). */
    var onSettle: (Boolean) -> Unit = {}

    /** Before the first measure (unfolding from the button), a typical panel height. */
    private val full get() = if (fullPx > 0f) fullPx else with(density) { 420.dp.toPx() }

    fun drag(deltaPx: Float) {
        if (!dragging) {
            dragging = true
            startedOpen = target
            job?.cancel()
        }
        value = DockFold.dragged(value, deltaPx, full)
    }

    /** Ends a drag with [velocityPx] (down is positive); returns whether the panel settles unfolded. */
    fun release(velocityPx: Float): Boolean {
        val wasDragging = dragging
        dragging = false
        if (!wasDragging) return target
        val open = DockFold.settle(value, with(density) { velocityPx / 1.dp.toPx() }, startedOpen)
        animateTo(open, velocityPx)
        onSettle(open)
        return open
    }

    fun animateTo(open: Boolean, velocityPx: Float = 0f) {
        target = open
        if (dragging) return
        val to = if (open) 1f else 0f
        if (value == to) return
        job?.cancel()
        job = scope.launch {
            animate(value, to, initialVelocity = -velocityPx / full, animationSpec = foldSpring()) { v, _ -> value = v }
        }
    }

    /**
     * The list's scroll: scrolling on through the calls folds the keypad first (following the finger), then the
     * list scrolls; the fold never grows back from a list scroll, so the two never fight.
     */
    val listConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            val take = DockFold.preScroll(value, available.y, full)
            if (take == 0f) return Offset.Zero
            drag(-take)
            return Offset(0f, take)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            // A fling on through the list folds the keypad and keeps its speed for the list; a small nudge springs back.
            val open = release(-available.y)
            return if (open) available else Velocity.Zero
        }
    }

    /** Drags on the panel (its keys, number and handle) move the fold once the panel's own scroll has had its turn. */
    val panelConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Moving back up during a fold drag unfolds again before the panel's content scrolls.
            if (source != NestedScrollSource.UserInput || !dragging || available.y >= 0f || value >= 1f) return Offset.Zero
            val before = value
            drag(available.y)
            return Offset(0f, -(value - before) * full)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            drag(available.y)
            return available.copy(x = 0f)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            release(available.y)
            return available
        }
    }
}

/**
 * Lays the panel out at its full size and shows [DockFoldState.value] of it, clipped, so the panel slides down
 * (or aside, [horizontal]) behind the edge instead of squeezing its keys; the list beside it grows smoothly.
 */
internal fun Modifier.foldable(state: DockFoldState, horizontal: Boolean): Modifier = this.clipToBounds().layout { m, c ->
    val p = m.measure(if (horizontal) c.copy(minWidth = 0) else c.copy(minHeight = 0))
    state.fullPx = (if (horizontal) p.width else p.height).toFloat()
    val f = state.value.coerceIn(0f, 1f)
    if (horizontal) {
        val w = (p.width * f).roundToInt()
        layout(w, p.height) { p.placeRelative(0, 0) }
    } else {
        val h = (p.height * f).roundToInt()
        layout(p.width, h) { p.place(0, 0) }
    }
}
