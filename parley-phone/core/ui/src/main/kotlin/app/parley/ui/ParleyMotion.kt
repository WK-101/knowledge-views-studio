package app.parley.ui

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Motion read from the theme's [androidx.compose.material3.MotionScheme] (expressive springs, set in [ParleyTheme]),
 * so every expanding card, sliding row and fading label moves the same way. "Spatial" specs move and resize
 * things (they may overshoot a little); "effects" specs fade and recolour (they never overshoot).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ParleyMotion {
    @Composable @ReadOnlyComposable
    fun <T> spatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    @Composable @ReadOnlyComposable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    @Composable @ReadOnlyComposable
    fun <T> effects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

    @Composable @ReadOnlyComposable
    fun <T> fastEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

    @Composable @ReadOnlyComposable
    fun <T> slowEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowEffectsSpec()

    /** A section or card opening in place. */
    @Composable
    fun expandIn(): EnterTransition =
        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

    /** A section or card closing in place. */
    @Composable
    fun collapseOut(): ExitTransition =
        shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

    /** One piece of content replacing another in place (a label, a status line, a search field and a title). */
    @Composable
    fun swap(): ContentTransform = fadeIn(effects()) togetherWith fadeOut(fastEffects())

    /**
     * A panel of controls replacing another (the call screen's keypad and its controls, ringing and talking): the
     * new one grows in slightly from 92 % as the old one fades.
     */
    @Composable
    fun swapScaled(): ContentTransform =
        fadeIn(fastEffects()) + scaleIn(spatial(), initialScale = SWAP_SCALE) togetherWith fadeOut(fastEffects())

    /** Moving between top-level places (Home's tabs): Material's fade-through. */
    @Composable
    fun fadeThrough(): ContentTransform =
        fadeIn(effects()) + scaleIn(spatial(), initialScale = SWAP_SCALE) togetherWith fadeOut(fastEffects())

    // Page transitions for the navigation host. They are read in composition, outside the host's transition lambdas
    // (which aren't composable), and moved by the theme's springs like everything else.

    /** A page opening: it slides a short way in from the end side (the right, or the left in Arabic and Urdu). */
    @Composable
    fun navEnter(): EnterTransition {
        val shift = navShiftPx()
        return slideInHorizontally(spatial()) { shift } + fadeIn(effects())
    }

    /** The page underneath a newly opened one: it drifts toward the start side as it fades. */
    @Composable
    fun navExit(): ExitTransition {
        val shift = navShiftPx()
        return slideOutHorizontally(spatial()) { -shift } + fadeOut(fastEffects())
    }

    /** The page revealed by Back: it grows back from just under full size. Back's gesture scrubs it. */
    @Composable
    fun navPopEnter(): EnterTransition = fadeIn(effects()) + scaleIn(spatial(), initialScale = POP_REVEAL_SCALE)

    /** The page Back leaves: it shrinks away and fades, so the swipe previews what lies beneath. */
    @Composable
    fun navPopExit(): ExitTransition = scaleOut(spatial(), targetScale = POP_LEAVE_SCALE) + fadeOut(fastEffects())

    @Composable
    private fun navShiftPx(): Int {
        val px = with(LocalDensity.current) { NAV_SHIFT.toPx() }.roundToInt()
        return navShift(px, LocalLayoutDirection.current == LayoutDirection.Rtl)
    }

    /** [px] toward the end side: positive (right) left to right, negative (left) right to left. */
    fun navShift(px: Int, rtl: Boolean): Int = if (rtl) -px else px

    private val NAV_SHIFT = 30.dp
    private const val SWAP_SCALE = 0.92f
    private const val POP_REVEAL_SCALE = 0.95f
    private const val POP_LEAVE_SCALE = 0.9f

    /** Spec for a row sliding back into place (swipe actions, the fast-scroll bubble). */
    @Composable @ReadOnlyComposable
    fun offset(): FiniteAnimationSpec<IntOffset> = MaterialTheme.motionScheme.fastSpatialSpec()

    /**
     * True when the user turned animations off (Settings › Accessibility › Remove animations): endless animations
     * (pulses, hints) show a still state instead.
     */
    @Composable
    fun reducedMotion(): Boolean {
        val resolver = LocalContext.current.contentResolver
        return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    }
}
