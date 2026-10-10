package app.parley.ui

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

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
