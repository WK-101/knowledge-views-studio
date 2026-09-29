package app.parley.ui.contact

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.photo.OriginalPhoto
import app.parley.common.photo.PhotoMath
import app.parley.data.people.OriginalPhotos
import app.parley.ui.Avatar
import app.parley.ui.ParleyShapes
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

/** Decoded originals at the sizes shown, so returning to a contact page shows its photo at once. */
private object OriginalBitmaps {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun peek(o: OriginalPhotos.Original, px: Int): Bitmap? = cache.get("${o.id}@$px")

    suspend fun load(originals: OriginalPhotos, o: OriginalPhotos.Original, px: Int): Bitmap? =
        peek(o, px) ?: originals.decode(o, px)?.also { cache.put("${o.id}@$px", it) }
}

/**
 * The photo as picked for a phone contact, while it still matches Android's photo ([photoUri]); null when Parley
 * keeps none (a photo set by another app, or from before Parley kept originals).
 */
@Composable
fun rememberOriginalPhoto(vm: AppViewModel, lookupKey: String?, photoUri: String?): OriginalPhotos.Original? {
    val originals = vm.c.people.originals
    val version by originals.version.collectAsStateWithLifecycle()
    val o by produceState<OriginalPhotos.Original?>(null, lookupKey, photoUri, version) {
        value = if (photoUri == null) null else originals.forContact(lookupKey, photoUri)
    }
    return o
}

/** The photo as picked for private contact [vaultId], or null. */
@Composable
fun rememberPrivateOriginalPhoto(vm: AppViewModel, vaultId: Long, hasPhoto: Boolean): OriginalPhotos.Original? {
    val originals = vm.c.people.originals
    val version by originals.version.collectAsStateWithLifecycle()
    val o by produceState<OriginalPhotos.Original?>(null, vaultId, hasPhoto, version) {
        value = if (hasPhoto) originals.forPrivate(vaultId) else null
    }
    return o
}

/**
 * The contact page's big photo. With a kept [original] it is shown whole in its own shape: a circle when it is about
 * square, else a rounded rectangle up to 1.6 × [size] wide or 1.25 × tall ([OriginalPhoto.hero]). Otherwise the
 * usual round [Avatar] with Android's photo or the monogram. [onClick] opens the photo viewer (when there's a photo).
 */
@Composable
fun HeroPhoto(
    vm: AppViewModel,
    name: String,
    photoUri: String?,
    original: OriginalPhotos.Original?,
    size: Dp,
    modifier: Modifier = Modifier,
    isCompany: Boolean = false,
    onClick: () -> Unit,
) {
    val viewLabel = stringResource(R.string.detail_view_photo)
    if (original == null) {
        Avatar(name, photoUri, size, modifier.clickable(enabled = photoUri != null, onClickLabel = viewLabel, onClick = onClick), isCompany = isCompany)
        return
    }
    val frame = remember(original.width, original.height) { OriginalPhoto.hero(original.width, original.height) }
    val shape = if (frame.round) CircleShape else ParleyShapes.panel
    val w = size * frame.width
    val h = size * frame.height
    val px = with(LocalDensity.current) { maxOf(w, h).roundToPx() }
    val originals = vm.c.people.originals
    val image by produceState<ImageBitmap?>(OriginalBitmaps.peek(original, px)?.asImageBitmap(), original.id, px) {
        if (value == null) value = OriginalBitmaps.load(originals, original, px)?.asImageBitmap()
    }
    val desc = stringResource(R.string.detail_contact_photo)
    Box(
        modifier.size(w, h).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = desc }
            .clickable(onClickLabel = viewLabel, onClick = onClick),
    ) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Full-screen viewer for a kept original: the whole picture fitted to the screen, pinch or double-tap to zoom (up to
 * 8×), and once zoomed the part in view is decoded again at full detail, only that part (so a 50 MP photo never sits
 * in memory whole). Tap to close.
 */
@OptIn(FlowPreview::class)
@Composable
fun OriginalPhotoViewer(vm: AppViewModel, original: OriginalPhotos.Original, onDismiss: () -> Unit) {
    val originals = vm.c.people.originals
    // A private contact's original stays opened while shown (see OriginalPhotos.Original), and no longer.
    DisposableEffect(original) { onDispose { originals.release(original) } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val viewW = with(density) { maxWidth.toPx() }
            val viewH = with(density) { maxHeight.toPx() }
            val screenLong = maxOf(viewW, viewH).roundToInt().coerceAtLeast(1)
            val base by produceState<ImageBitmap?>(null, original.id, screenLong) {
                value = OriginalBitmaps.load(originals, original, screenLong)?.asImageBitmap()
            }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            // The detailed part in view: the picture's region (upright pixels) and its pixels.
            var tile by remember(original.id) { mutableStateOf<Pair<PhotoMath.Crop, ImageBitmap>?>(null) }
            val state = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
            // Where the fitted picture sits on screen (before zooming), in screen pixels per picture pixel.
            val fit = minOf(viewW / original.width, viewH / original.height)
            val left = (viewW - original.width * fit) / 2
            val top = (viewH - original.height * fit) / 2
            LaunchedEffect(original.id, viewW, viewH) {
                snapshotFlow { scale to offset }.debounce(180).collect { (s, t) ->
                    // Detail is worth it once zoomed into a picture larger than the screen (a smaller one is shown whole).
                    if (s < 1.2f || fit >= 1f) {
                        tile = null
                        return@collect
                    }
                    val cx = viewW / 2
                    val cy = viewH / 2
                    fun toImage(x: Float, y: Float) = ((cx + (x - t.x - cx) / s - left) / fit) to ((cy + (y - t.y - cy) / s - top) / fit)
                    val (l, tp) = toImage(0f, 0f)
                    val (r, b) = toImage(viewW, viewH)
                    val region = PhotoMath.Crop(
                        l.toInt().coerceIn(0, original.width - 1), tp.toInt().coerceIn(0, original.height - 1),
                        r.roundToInt().coerceIn(1, original.width), b.roundToInt().coerceIn(1, original.height),
                    )
                    if (region.width <= 0 || region.height <= 0) return@collect
                    val shownW = (region.width * fit * s).roundToInt().coerceAtLeast(1)
                    val shownH = (region.height * fit * s).roundToInt().coerceAtLeast(1)
                    tile = originals.decodeRegion(original, region, shownW, shownH)?.let { region to it.asImageBitmap() }
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onDismiss() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                    }
                    .transformable(state)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            ) {
                base?.let { Image(it, stringResource(R.string.detail_contact_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                tile?.let { (region, bmp) ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(
                            bmp,
                            dstOffset = IntOffset((left + region.left * fit).roundToInt(), (top + region.top * fit).roundToInt()),
                            dstSize = IntSize((region.width * fit).roundToInt().coerceAtLeast(1), (region.height * fit).roundToInt().coerceAtLeast(1)),
                        )
                    }
                }
            }
        }
    }
}
