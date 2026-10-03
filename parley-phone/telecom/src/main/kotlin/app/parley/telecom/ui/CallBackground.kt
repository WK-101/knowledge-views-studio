package app.parley.telecom.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.parley.common.ux.CallBackdrop
import app.parley.common.ux.CallScreenBackground
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.ui.ParleyMotion
import app.parley.ui.avatarColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The call screen's background: the theme's surface tinted at the top with the caller's own colour (the hue of their
 * avatar; the theme's, or dynamic, primary for unknown numbers; the error colour for a likely spam call), fading to
 * the plain surface behind the controls. With Settings › Calls › "Call screen background" on Plain, the surface
 * stays plain (a likely spam call keeps its red warning). A caller's call-screen picture fills the screen either way,
 * under a scrim of the surface. The tint and the scrim are as strong as [CallBackdrop] allows while onSurface and
 * onSurfaceVariant text keep 4.5:1, in light, dark and black themes. The picture-in-picture window takes the tint
 * only ([picture] off).
 *
 * [image] is the picture as [rememberCallPicture] decoded it (null: none yet, or it can't be read; then only the
 * tint shows). With [poster] (the Poster style laid out as one, see [CallBackdrop.posterLayout]), the picture stays
 * clear above the caller's text, whose top edge [textTop] gives in pixels from the top of this background (negative
 * while not yet measured); the readable scrim starts just above it.
 */
@Suppress("LongParameterList")
@Composable
internal fun CallBackground(
    call: CallUi?,
    style: CallScreenBackground,
    picture: Boolean = true,
    image: ImageBitmap? = null,
    poster: Boolean = false,
    textTop: () -> Float = { -1f },
) {
    val scheme = MaterialTheme.colorScheme
    val plan = callBackdropPlan(call, style, picture)
    val surface = scheme.surface
    val accent = when (plan.tint) {
        CallBackdrop.Tint.WARNING -> scheme.error
        CallBackdrop.Tint.CALLER -> if (call?.name != null) avatarColor(call.title) else scheme.primary
        CallBackdrop.Tint.NONE -> surface
    }
    val inks = remember(scheme.onSurface, scheme.onSurfaceVariant) { intArrayOf(scheme.onSurface.toArgb(), scheme.onSurfaceVariant.toArgb()) }
    val tint = remember(surface, accent, inks) {
        val s = CallBackdrop.tintStrength(surface.toArgb(), accent.toArgb(), inks)
        Color(CallBackdrop.blend(accent.toArgb(), surface.toArgb(), s))
    }
    val top by animateColorAsState(tint, ParleyMotion.slowEffects(), label = "tint")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.6f to surface, 1f to surface)))
    if (plan.picture && image != null) CallPicture(image, surface, inks, poster && plan.poster, textTop)
}

/** What the call screen draws behind [call] with [style] (see [CallBackdrop.plan]). */
internal fun callBackdropPlan(call: CallUi?, style: CallScreenBackground, picture: Boolean = true): CallBackdrop.Plan = CallBackdrop.plan(
    style,
    warn = call != null && call.verdictWarn && call.state == CallState.RINGING,
    hasPicture = call?.backgroundUri != null,
    allowPicture = picture,
    masked = call?.lockMasked == true,
)

/**
 * The caller's call-screen picture at [uri], decoded off the main thread and scaled down to at most about a screen's
 * size, so the ring path never waits for it: the tinted background shows first and the picture fades in over it.
 * Null while it decodes, and for good when it can't be read (deleted, moved, a permission gone): the screen then
 * lays out as if there were no picture.
 */
@Composable
internal fun rememberCallPicture(uri: String?): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        // Another caller's picture never stays up while this one decodes (or if it can't be read).
        value = null
        if (uri == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val u = Uri.parse(uri)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / (sample * 2) >= MAX_PICTURE_PX && opts.outHeight / (sample * 2) >= MAX_PICTURE_PX) sample *= 2
                context.contentResolver.openInputStream(u)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

/** [bmp] over the whole screen, faded in once, under the readable scrim (see [CallBackground]). */
@Composable
private fun CallPicture(bmp: ImageBitmap, surface: Color, inks: IntArray, poster: Boolean, textTop: () -> Float) {
    val appeared = remember { Animatable(0f) }
    val fadeIn = ParleyMotion.slowEffects<Float>()
    LaunchedEffect(Unit) { appeared.animateTo(1f, fadeIn) }
    // How clear the poster's picture is above the text; it closes to the classic scrim when the keypad opens.
    val open = animateFloatAsState(if (poster) 1f else 0f, ParleyMotion.slowEffects(), label = "poster")
    val scrim = remember(surface, inks) { CallBackdrop.scrimAlpha(surface.toArgb(), inks) }
    val statusBars = WindowInsets.statusBars
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = appeared.value }) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // The readable minimum over the picture (from the poster's text down), then fully opaque behind the controls.
        // The text's position and the opening are read while drawing, so a moving header never recomposes this.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val h = size.height.coerceAtLeast(1f)
                // The classic layout deepens from a fixed point; the poster (and its closing) from the text.
                val top = textTop().takeIf { it >= 0f && open.value > 0f } ?: (h * CLASSIC_SCRIM_FROM)
                val guard = statusBars.getTop(this) + POSTER_GUARD.toPx()
                val stops = CallBackdrop.posterStops(top / h, guard / h, POSTER_FADE.toPx() / h, scrim, open.value)

                // A handful of stops, built only when the text moves.
                @Suppress("SpreadOperator")
                val brush = Brush.verticalGradient(*stops.map { (at, a) -> at to surface.copy(alpha = a) }.toTypedArray())
                onDrawBehind { drawRect(brush) }
            },
        )
    }
}

private const val MAX_PICTURE_PX = 1080

/** Where the classic layout's scrim starts to deepen towards the controls. */
private const val CLASSIC_SCRIM_FROM = 0.45f

/** Below the status bar, the poster's picture is clear from this far down (the bar's icons stay readable)... */
private val POSTER_GUARD = 32.dp

/** ...and the scrim fades back in over this much above the caller's text. */
private val POSTER_FADE = 48.dp
