package com.todocompanion.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * R44 — the picker rebuilt from the ACTUAL working reference: the open-source Tasks.org (`org.tasks`),
 * which attaches any file with zero storage/media/camera permission on stock AND de-Googled ROMs.
 *
 * THE REAL ROOT CAUSE (finally): it was never the launch plumbing and never one magic contract. Our
 * earlier "fixes" all launched the SAME family — ACTION_OPEN_DOCUMENT, ACTION_GET_CONTENT, and
 * GET_CONTENT-in-a-chooser. On a debloated ROM where the system DocumentsUI is stripped and nothing
 * claims GET_CONTENT, all three resolve to nothing and every attempt throws — a dead end with no route
 * left. Tasks.org survives because it offers a MENU OF INDEPENDENT ROUTES, and the decisive one is
 * `ACTION_PICK` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`: that is served by the MEDIA PROVIDER,
 * which is present on essentially every ROM — including ones with no DocumentsUI. So the gallery opens
 * where SAF cannot. (Tasks.org also delegates the camera to any camera app via IMAGE_CAPTURE +
 * FileProvider, and copies the picked bytes straight into app-private storage — which our attachment
 * layer already does via openInputStream.)
 *
 * THE FIX these helpers apply, mirroring Tasks.org:
 *   • PHOTO → lead with `ACTION_PICK` (media provider, works on debloated ROMs), then the modern Photo
 *     Picker, then a GET_CONTENT chooser. This is the route that was missing and the whole reason the
 *     picker "wouldn't open".
 *   • FILE  → ACTION_OPEN_DOCUMENT (SAF, any type) → GET_CONTENT chooser. When a ROM genuinely has no
 *     SAF at all, arbitrary-file picking cannot work permission-free (Tasks.org has the same limit); we
 *     surface a clear message pointing to the gallery/camera/"share into the app" routes instead.
 *   • CAMERA → IMAGE_CAPTURE into a FileProvider URI, feature-gated by hasSystemFeature.
 * Every route hands back exactly the picked item with a temporary read grant; the returned URI is read
 * immediately with openInputStream and copied in, so no storage/media/camera permission is ever needed.
 */

private const val TAG = "RobustPicker"

/** Pull the URIs out of a picker result — multi in clipData, single in data. */
private fun extractUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val out = ArrayList<Uri>()
    val clip = data.clipData
    if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out.add(it) }
    else data.data?.let { out.add(it) }
    return out
}

/** Runs each launch tier in order; the first that doesn't throw wins. Returns the last error if all fail. */
private inline fun launchChain(attempts: List<Pair<String, () -> Unit>>, kind: String): Throwable? {
    var last: Throwable? = null
    for ((name, attempt) in attempts) {
        val r = runCatching { attempt() }
        if (r.isSuccess) return null
        last = r.exceptionOrNull()
        Log.w(TAG, "$kind pick via $name failed -> ${last?.javaClass?.name}: ${last?.message}", last)
    }
    return last
}

/** The MediaStore gallery intent — the route that survives ROMs with no DocumentsUI (Tasks.org's core). */
private fun galleryPickIntent(): Intent = Intent(Intent.ACTION_PICK).apply {
    setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun getContentIntent(mimeTypes: Array<String>): Intent = Intent(Intent.ACTION_GET_CONTENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * Robust FILE picker (arbitrary types). Call at the top level of a screen composable; returns
 * `launch(mimeTypes)`. Handles single OR multi-select — the callback receives every URI picked.
 */
@Composable
fun rememberFilePicker(
    onError: (String) -> Unit = {},
    onPicked: (List<Uri>) -> Unit,
): (Array<String>) -> Unit {
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(listOf(it)) }
    }
    val raw = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) extractUris(res.data).takeIf { it.isNotEmpty() }?.let(onPicked)
    }
    return { mimeTypes ->
        val types = if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes
        val primary = if (types.size == 1) types[0] else "*/*"
        val err = launchChain(
            listOf(
                "OPEN_DOCUMENT" to { openDoc.launch(types) },
                "GET_CONTENT" to { getContent.launch(primary) },
                "CHOOSER" to { raw.launch(Intent.createChooser(getContentIntent(types), "Select a file")) },
            ),
            "file",
        )
        if (err != null) onError(
            "This device has no document picker (${err.javaClass.simpleName}). Tip: use the Photo or " +
                "Camera button for images, or open the file in another app and Share → ToDo Companion.",
        )
    }
}

/**
 * Robust PHOTO/media picker. Leads with the MediaStore gallery (`ACTION_PICK`) — the route that opens on
 * ROMs without DocumentsUI, exactly as Tasks.org does — then the modern Photo Picker, then a GET_CONTENT
 * chooser. Call at the top level; returns `launch()`.
 */
@Composable
fun rememberPhotoPicker(
    onError: (String) -> Unit = {},
    onPicked: (List<Uri>) -> Unit,
): () -> Unit {
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) extractUris(res.data).takeIf { it.isNotEmpty() }?.let(onPicked)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) extractUris(res.data).takeIf { it.isNotEmpty() }?.let(onPicked)
    }
    return {
        val err = launchChain(
            listOf(
                // Media provider first — present on virtually every ROM, DocumentsUI or not. THE fix.
                "MEDIA_PICK" to { gallery.launch(galleryPickIntent()) },
                "PHOTO_PICKER" to {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                "CHOOSER" to { chooser.launch(Intent.createChooser(getContentIntent(arrayOf("image/*", "video/*")), "Select photos")) },
            ),
            "photo",
        )
        if (err != null) onError(
            "Couldn't open the gallery (${err.javaClass.simpleName}). Try the Camera or File button, or " +
                "Share a photo into ToDo Companion from your gallery app.",
        )
    }
}

/**
 * Robust CAMERA capture into a FileProvider URI. Feature-gated like Tasks.org/TickTick. Call at the top
 * level; returns `launch()`. On capture, `onCaptured(uri)` fires with the photo's content URI.
 */
@Composable
fun rememberCameraCapture(
    onError: (String) -> Unit = {},
    onCaptured: (Uri) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pending.value?.let(onCaptured)
    }
    return {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            onError("No camera on this device.")
        } else {
            val res = runCatching {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val f = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                pending.value = uri
                takePhoto.launch(uri)
            }
            res.exceptionOrNull()?.let { e ->
                Log.w(TAG, "camera launch failed -> ${e.javaClass.name}: ${e.message}", e)
                onError("Couldn't open the camera: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}

/** Common mime-type sets. Imports include the wildcard because many providers report files as
 *  application/octet-stream and won't surface under a narrow type. */
object PickTypes {
    val ANY = arrayOf("*/*")
    val IMAGES = arrayOf("image/*")
    val IMAGES_VIDEO = arrayOf("image/*", "video/*")
    val BACKUP = arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
    val ICS = arrayOf("text/calendar", "application/octet-stream", "*/*")
}
