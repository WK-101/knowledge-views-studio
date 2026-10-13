package app.parley.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable

/** How much a destination grows from as it fades through. */
internal const val FADE_THROUGH_SCALE = 0.92f

/**
 * Material's fade-through, for switching between destinations at the same level (Home's tabs): the old one fades out
 * quickly, the new one fades in while growing slightly into place. Read it outside `AnimatedContent`'s transition
 * lambda (which isn't composable) and return it from there.
 */
@Composable
fun fadeThrough(): ContentTransform =
    (fadeIn(ParleyMotion.effects()) + scaleIn(ParleyMotion.spatial(), initialScale = FADE_THROUGH_SCALE)) togetherWith
        fadeOut(ParleyMotion.fastEffects())
