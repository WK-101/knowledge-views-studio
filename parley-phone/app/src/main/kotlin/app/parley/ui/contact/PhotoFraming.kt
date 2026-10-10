package app.parley.ui.contact

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.photo.FrameMath
import app.parley.common.photo.PhotoFrame
import app.parley.data.ContactPhotoProcessor
import app.parley.data.people.OriginalPhotos
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTooltip
import app.parley.ui.ParleyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import kotlin.math.min
import kotlin.math.roundToInt

/** Where "Frame photo" gets its picture: one just picked or taken, or the whole photo Parley keeps for the contact. */
sealed interface FramingSource {
    data class Picked(val uri: Uri) : FramingSource

    class Kept(val original: OriginalPhotos.Original) : FramingSource
}

/**
 * The editor's photo: [CompactPhoto] with its menu (choose, take, adjust framing, remove), the camera app and the
 * photo picker (neither needs a permission), and "Frame photo" after every new picture. The header shows the framed
 * square, as the lists will. A device contact's, a private one's and a temporary one's photo work the same way.
 */
@Composable
internal fun EditorPhoto(vm: AppViewModel, editor: EditorViewModel, name: String, shownPhoto: String?) {
    val context = LocalContext.current
    val photo = editor.photo
    val frame = editor.photoFrame
    val vaultId = editor.args.vaultId?.takeIf { it > 0 }
    // The whole photo Parley keeps for this contact: "Adjust framing" starts from it.
    val kept = if (vaultId != null) {
        rememberPrivateOriginalPhoto(vm, vaultId, shownPhoto != null)
    } else {
        rememberOriginalPhoto(vm, editor.original?.lookupKey, editor.original?.photoUri)
    }.takeIf { photo == null && !editor.removePhoto }
    // A picture to frame before it becomes the photo (picked, taken, or the photo itself), or the kept one.
    var framePick by rememberSaveable { mutableStateOf<String?>(null) }
    var frameKept by rememberSaveable { mutableStateOf(false) }
    // The system photo picker needs no storage permission (also for private contacts' encrypted photos).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) framePick = uri.toString() }
    val takePhoto = rememberTakePhoto(vm) {
        editor.tookPhoto(Uri.parse(it))
        framePick = it
    }
    val preview by produceState<ImageBitmap?>(null, photo, frame, kept?.id) {
        value = if (frame == null) null else framedPreview(vm, context, photo, kept, frame)
    }
    CompactPhoto(
        name, shownPhoto,
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onTake = takePhoto,
        onAdjust = {
            frameKept = false
            when {
                photo != null -> framePick = photo.toString()
                kept != null -> frameKept = true
                // A photo another app set (Parley keeps no original): framed from Android's copy.
                shownPhoto != null -> framePick = shownPhoto
            }
        },
        onRemove = editor::clearPhoto, inOtherApps = !editor.isVault && !editor.args.meCard, framed = preview,
    )
    framePick?.let { u ->
        val uri = Uri.parse(u)
        PhotoFramer(
            vm, FramingSource.Picked(uri), initial = frame.takeIf { photo == uri }, allowWhole = true,
            onDone = { f ->
                editor.pickPhoto(uri, f)
                framePick = null
            },
            onCancel = {
                if (photo != uri) ContactCamera.forget(context, uri)
                framePick = null
            },
        )
    }
    // The kept picture loads again after a rotation: the framing screen waits for it rather than closing.
    val o = kept
    if (frameKept && o != null) {
        // The kept picture is reframed as it is; "whole" isn't offered (its whole copy is already the original).
        // Done without moving the circle changes nothing: the photo Android has isn't rewritten (and synced) for it.
        PhotoFramer(
            vm, FramingSource.Kept(o), initial = frame ?: o.frame, allowWhole = false,
            onDone = { f ->
                editor.frame(f)
                frameKept = false
            },
            onCancel = { frameKept = false },
            onUnchanged = { frameKept = false },
        )
    }
}

/**
 * "Take photo": a function that asks the camera app for one picture (no camera permission) and hands its content URI
 * to [onTaken]. The file waits in Parley's cache ([ContactCamera]); a cancelled or empty shot is deleted at once.
 */
@Composable
private fun rememberTakePhoto(vm: AppViewModel, onTaken: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    // path and URI of the shot in progress (kept across the process being stopped while the camera is open).
    var shot by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val (path, uri) = shot ?: return@rememberLauncherForActivityResult
        shot = null
        val file = File(path)
        if (ok && file.length() > 0) onTaken(uri) else file.delete()
    }
    val noCamera = stringResource(R.string.editor_no_camera)
    return {
        val created = runCatching { ContactCamera.newPhoto(context) }.getOrNull()
        if (created == null) {
            vm.toast(noCamera)
        } else {
            val (file, uri) = created
            shot = listOf(file.absolutePath, uri.toString())
            runCatching { camera.launch(uri) }.onFailure {
                // No camera app (ActivityNotFoundException) or one that refused.
                shot = null
                file.delete()
                vm.toast(noCamera)
            }
        }
    }
}

/** The square [frame] cuts from the picked photo or the kept one, small, for the editor's header. */
private suspend fun framedPreview(vm: AppViewModel, context: Context, photo: Uri?, kept: OriginalPhotos.Original?, frame: PhotoFrame): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val b = when {
                photo != null -> ContactPhotoProcessor.decodeBounded(context.contentResolver, photo, PREVIEW_PX)
                kept != null -> vm.c.people.originals.decode(kept, PREVIEW_PX)
                else -> null
            } ?: return@runCatching null
            val c = FrameMath.toCrop(frame, b.width, b.height)
            Bitmap.createBitmap(b, c.left, c.top, c.width, c.height).asImageBitmap()
        }.getOrNull()
    }

/** The header preview's decoded size: enough for a 64 dp circle at any density. */
private const val PREVIEW_PX = 768

/** Decoded size of the picture on the framing screen: sharp on any phone, small enough for any memory. */
private const val FRAMING_PX = 2048

/**
 * "Frame photo": the whole picture behind a circle, dragged and pinched until the right part shows in it. The square
 * around the circle is what Android and Parley's lists use as the avatar; the picture itself is kept whole. Every
 * gesture has a button too (zoom, move, centre on a face, reset), for TalkBack and switch access. The picture's
 * area is laid out left to right in every language, so "left" is always the screen's left.
 *
 * [initial]: the frame to start from (null: centred, or on a face when one is found). [onDone] gets the chosen
 * square, or null for "Use whole photo" (offered with [allowWhole]). With [onUnchanged], Done when the user never
 * moved the circle (or moved it back to where it started) calls that instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoFramer(
    vm: AppViewModel,
    source: FramingSource,
    initial: PhotoFrame?,
    allowWhole: Boolean,
    onDone: (PhotoFrame?) -> Unit,
    onCancel: () -> Unit,
    onUnchanged: (() -> Unit)? = null,
) {
    val loaded = rememberFramingPicture(vm, source)
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap = loaded?.getOrNull()
        var frame by remember(bitmap) { mutableStateOf(bitmap?.let { startFrame(initial, it) }) }
        var touched by remember(bitmap) { mutableStateOf(initial != null) }
        // Whether the user moved the circle at all (a face-centred start isn't a change of theirs).
        var moved by remember(bitmap) { mutableStateOf(false) }
        val start = remember(bitmap) { frame }
        val face by produceState<Pair<Double, Double>?>(null, bitmap) { value = bitmap?.let { b -> withContext(Dispatchers.Default) { FaceFinder.find(b) } } }
        // A new photo starts centred on the face, unless the user has already moved it.
        LaunchedEffect(face) {
            val b = bitmap ?: return@LaunchedEffect
            val f = face ?: return@LaunchedEffect
            if (!touched) frame = FrameMath.centreOn(FrameMath.centred(b.width, b.height), b.width, b.height, f.first, f.second)
        }
        ParleyScaffold(
            topBar = {
                ParleyTopBar(
                    title = { Text(stringResource(R.string.frame_title), maxLines = 1) },
                    navigationIcon = { IconButton(onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.main_cancel)) } },
                    actions = {
                        Button(
                            onClick = { FramingDone.finish(if (bitmap == null) null else frame, moved, start, bitmap, onDone, onUnchanged) },
                            enabled = loaded != null && (bitmap != null || allowWhole),
                            modifier = Modifier.padding(end = 8.dp).heightIn(min = 40.dp),
                        ) { Text(stringResource(R.string.main_done)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    FramingPicture(loaded != null, bitmap, frame) { next ->
                        touched = true
                        moved = true
                        frame = next
                    }
                }
                if (bitmap != null) {
                    Text(
                        stringResource(R.string.frame_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    FramingButtons(hasFace = face != null) { change ->
                        touched = true
                        moved = true
                        frame = frame?.let { f -> change(f, bitmap.width, bitmap.height, face) }
                    }
                }
                if (allowWhole) {
                    TextButton(
                        onClick = { onDone(null) }, enabled = loaded != null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.frame_whole)) }
                }
            }
        }
    }
}

/** [source] decoded for framing: null while loading, then the picture or a failure (it is then used whole). */
@Composable
private fun rememberFramingPicture(vm: AppViewModel, source: FramingSource): Result<Bitmap>? {
    val context = LocalContext.current
    val originals = vm.c.people.originals
    if (source is FramingSource.Kept) DisposableEffect(source.original) { onDispose { originals.release(source.original) } }
    val loaded by produceState<Result<Bitmap>?>(null, source) {
        value = runCatching {
            when (source) {
                is FramingSource.Picked -> withContext(Dispatchers.IO) { ContactPhotoProcessor.decodeBounded(context.contentResolver, source.uri, FRAMING_PX) }
                is FramingSource.Kept -> originals.decode(source.original, FRAMING_PX)
            } ?: error("unreadable")
        }
    }
    return loaded
}

/** The picture to frame once [loaded]: a spinner before, a note when it can't be read, else the framing area. */
@Composable
private fun FramingPicture(loaded: Boolean, bitmap: Bitmap?, frame: PhotoFrame?, onFrame: (PhotoFrame) -> Unit) {
    when {
        !loaded -> {
            val desc = stringResource(R.string.frame_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = desc })
        }
        bitmap == null || frame == null -> Text(
            stringResource(R.string.frame_unreadable), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp),
        )
        else -> {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            FramingArea(image, frame, onFrame)
        }
    }
}

/** Whether Done on the framing screen leaves the photo as it was. */
internal object FramingDone {
    /**
     * Unchanged when the user never moved the circle ([moved]), or brought it back to [start] (the same pixels of a
     * [width]×[height] picture). An unreadable picture ([done] null) changes nothing either.
     */
    fun unchanged(moved: Boolean, start: PhotoFrame?, done: PhotoFrame?, width: Int, height: Int): Boolean = when {
        done == null -> true
        !moved -> true
        start == null || width <= 0 || height <= 0 -> false
        else -> FrameMath.same(start, done, width, height)
    }

    /** Done on the framing screen: [onUnchanged] when given and nothing changed, else [onDone] with [frame]. */
    @Suppress("LongParameterList") // The framing screen's state at Done, passed as it is.
    fun finish(frame: PhotoFrame?, moved: Boolean, start: PhotoFrame?, bitmap: Bitmap?, onDone: (PhotoFrame?) -> Unit, onUnchanged: (() -> Unit)?) {
        if (onUnchanged != null && unchanged(moved, start, frame, bitmap?.width ?: 0, bitmap?.height ?: 0)) onUnchanged() else onDone(frame)
    }
}

/** [initial] fitted to [b], or the largest centred square. */
private fun startFrame(initial: PhotoFrame?, b: Bitmap): PhotoFrame =
    initial?.let { FrameMath.clamp(it, b.width, b.height) } ?: FrameMath.centred(b.width, b.height)

/** A change one button makes: (frame, width, height, face) → frame. */
private typealias FrameChange = (PhotoFrame, Int, Int, Pair<Double, Double>?) -> PhotoFrame

/**
 * The picture with the circle cut out of a dimmed cover, and the square's corners around it. Drag to move, pinch
 * to zoom; the point under the fingers stays under them ([FrameMath.gesture]).
 */
@Composable
private fun FramingArea(image: ImageBitmap, frame: PhotoFrame, onFrame: (PhotoFrame) -> Unit) {
    val w = image.width
    val h = image.height
    val zoomText = remember(frame.side) { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(FrameMath.zoomOf(frame)) + "×" }
    val area = stringResource(R.string.frame_area)
    val state = stringResource(R.string.frame_zoom_state, zoomText)
    val outline = MaterialTheme.colorScheme.primary
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    // Picture coordinates have no reading direction: keep the area left to right in every language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val viewW = with(density) { maxWidth.toPx() }
            val viewH = with(density) { maxHeight.toPx() }
            val d = (min(viewW, viewH) - 2 * margin).coerceAtLeast(1f)
            val circleLeft = (viewW - d) / 2
            val circleTop = (viewH - d) / 2
            val current by rememberUpdatedState(frame)
            Canvas(
                Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = area
                        stateDescription = state
                    }
                    .pointerInput(w, h, d) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            onFrame(
                                FrameMath.gesture(
                                    current, w, h, d.toDouble(), pan.x.toDouble(), pan.y.toDouble(), zoom.toDouble(),
                                    (centroid.x - circleLeft).toDouble(), (centroid.y - circleTop).toDouble(),
                                ),
                            )
                        }
                    },
            ) {
                val shorter = min(w, h)
                val scale = d / (frame.side * shorter).toFloat()
                val left = circleLeft - (frame.left * w).toFloat() * scale
                val top = circleTop - (frame.top * h).toFloat() * scale
                drawImage(
                    image, srcOffset = IntOffset.Zero, srcSize = IntSize(w, h),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1)),
                )
                val circle = Rect(Offset(circleLeft, circleTop), Size(d, d))
                val cover = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(circle)
                }
                drawPath(cover, Color.Black.copy(alpha = 0.6f))
                // The square Android's thumbnail keeps, faintly; the circle Parley's lists show, clearly.
                drawRect(Color.White.copy(alpha = 0.35f), circle.topLeft, circle.size, style = Stroke(1.dp.toPx()))
                drawOval(outline, circle.topLeft, circle.size, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** The gestures as buttons: zoom, move (in screen directions, whatever the language), centre on a face, reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FramingButtons(hasFace: Boolean, onChange: (FrameChange) -> Unit) {
    val step = FrameMath.MOVE_STEP
    val buttons = buildList<Triple<ImageVector, Int, FrameChange>> {
        add(Triple(Icons.Rounded.ZoomOut, R.string.frame_zoom_out, { f, w, h, _ -> FrameMath.zoomBy(f, w, h, 1 / FrameMath.ZOOM_STEP) }))
        add(Triple(Icons.Rounded.ZoomIn, R.string.frame_zoom_in, { f, w, h, _ -> FrameMath.zoomBy(f, w, h, FrameMath.ZOOM_STEP) }))
        add(Triple(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, R.string.frame_move_left, { f, w, h, _ -> FrameMath.move(f, w, h, -step, 0.0) }))
        add(Triple(Icons.Rounded.KeyboardArrowUp, R.string.frame_move_up, { f, w, h, _ -> FrameMath.move(f, w, h, 0.0, -step) }))
        add(Triple(Icons.Rounded.KeyboardArrowDown, R.string.frame_move_down, { f, w, h, _ -> FrameMath.move(f, w, h, 0.0, step) }))
        add(Triple(Icons.AutoMirrored.Rounded.KeyboardArrowRight, R.string.frame_move_right, { f, w, h, _ -> FrameMath.move(f, w, h, step, 0.0) }))
        if (hasFace) add(Triple(Icons.Rounded.Face, R.string.frame_face, { f, w, h, face -> face?.let { (x, y) -> FrameMath.centreOn(f, w, h, x, y) } ?: f }))
        add(Triple(Icons.Rounded.RestartAlt, R.string.frame_reset, { _, w, h, _ -> FrameMath.centred(w, h) }))
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            buttons.forEach { (icon, label, change) ->
                val text = stringResource(label)
                ParleyTooltip(text) {
                    FilledTonalIconButton({ onChange(change) }, Modifier.size(48.dp)) { Icon(icon, text) }
                }
            }
        }
    }
}

/** On-device face finding for "Centre on face" (Android's own detector: no network, no extra library). */
internal object FaceFinder {
    private const val SCAN_PX = 512
    private const val MIN_CONFIDENCE = 0.3f

    /** The middle of the largest face in [bitmap], in its pixels, or null when none is found. */
    fun find(bitmap: Bitmap): Pair<Double, Double>? = runCatching {
        val scale = min(1.0, SCAN_PX.toDouble() / maxOf(bitmap.width, bitmap.height))
        // The detector needs an even width and 565 pixels.
        val w = ((bitmap.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(4)
        val n = FaceDetector(w, h, faces.size).findFaces(small, faces)
        small.recycle()
        val best = faces.take(n).filterNotNull().filter { it.confidence() >= MIN_CONFIDENCE }.maxByOrNull { it.eyesDistance() } ?: return null
        val p = PointF()
        best.getMidPoint(p)
        // The midpoint is between the eyes; the whole face's middle is a little lower.
        (p.x / scale) to ((p.y + best.eyesDistance() * 0.5f) / scale)
    }.getOrNull()
}

/**
 * Photos the camera app takes for a contact ("Take photo"): ACTION_IMAGE_CAPTURE into a file of Parley's cache shared
 * through its FileProvider, as "Scan QR" does, so Parley needs no camera permission. The cache isn't sealed, so a shot
 * (perhaps a private contact's face) is kept only while the editor needs it: it is deleted once the contact is saved,
 * when its framing is cancelled, when another photo replaces it or it is removed, and when the editor is left without
 * saving. A shot left behind by a process that ended meanwhile goes in [sweep] (at start and in the daily upkeep).
 */
object ContactCamera {
    private const val DIR = "contact_camera"

    /** The framed avatar written for Android's contacts provider during a save (deleted once written). */
    internal const val FRAMED_DIR = "framed"

    /**
     * A shot this old is no longer in any editor: kept this long only so that an editor restored after the process
     * was stopped in the background (the camera app open, or Parley left for a while) still finds its photo.
     */
    internal const val STALE_MS = 24 * 60 * 60 * 1000L

    /** A save takes seconds; a framed file older than this was left by a save that never finished. */
    internal const val FRAMED_STALE_MS = 10 * 60 * 1000L

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun authority(context: Context) = context.packageName + ".files"

    /** A new, empty file for the camera app to write to, and its content URI; older leftovers are cleared first. */
    fun newPhoto(context: Context): Pair<File, Uri> {
        val now = System.currentTimeMillis()
        sweep(context, now)
        val f = File(dir(context), "photo-$now.jpg")
        return f to FileProvider.getUriForFile(context, authority(context), f)
    }

    /** Deletes [uri] when it is one of these photos (anything else, such as a picked photo, is left alone). */
    fun forget(context: Context, uri: Uri?) {
        if (uri == null || uri.authority != authority(context) || uri.pathSegments.firstOrNull() != DIR) return
        val name = uri.lastPathSegment ?: return
        runCatching { File(dir(context), name).takeIf { it.parentFile == dir(context) }?.delete() }
    }

    /** Clears shots and framed files that no editor or save can still be using (see [STALE_MS], [FRAMED_STALE_MS]). */
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) {
        fun clear(folder: File, age: Long) = runCatching { folder.listFiles()?.forEach { if (now - it.lastModified() > age) it.delete() } }
        clear(File(context.cacheDir, DIR), STALE_MS)
        clear(File(context.cacheDir, FRAMED_DIR), FRAMED_STALE_MS)
    }
}
