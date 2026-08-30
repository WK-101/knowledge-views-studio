package com.todocompanion.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 * R43 — the ROM-proof picker system, rebuilt from a forensic decompile of TickTick + Todoist.
 *
 * ROOT CAUSE of the six rounds of "could not open the file picker / no camera or gallery / no photo
 * picker" toasts: it was NEVER the intent. It was the launch plumbing. Our old launchers were
 * `rememberLauncherForActivityResult` calls buried inside a `when{}` arm / section content lambda —
 * a conditionally-composed slot. A launcher registered there unregisters the moment that slot leaves
 * composition, so `.launch()` throws `IllegalStateException` ("unregistered ActivityResultLauncher"
 * / "Launcher has not been initialized") for EVERY intent, on EVERY ROM. That is exactly why the same
 * device runs TickTick fine: TickTick never touches the Compose registry — it launches through the
 * Activity/Fragment's own `startActivityForResult`, which is always "registered" because it IS the
 * Activity. (Decompiled proof: TickTick file pick = `Fragment.startActivityForResult(OPEN_DOCUMENT,108)`
 * with no try/catch; camera = IMAGE_CAPTURE gated by `hasSystemFeature` + try/catch; gallery = its own
 * in-app ImageGridActivity.)
 *
 * THE FIX, applied by these helpers:
 *   1. Register unconditionally at the TOP LEVEL of a screen composable. Callers invoke these
 *      remember* helpers once, before any `if`/`when`/`return`, and pass only the returned lambda down
 *      into sections/sheets. NEVER call them inside a when-arm, DetailSection body, dialog or lazy item.
 *   2. A layered launch chain that cannot dead-end: OPEN_DOCUMENT (SAF) → GET_CONTENT → GET_CONTENT in a
 *      system chooser. The photo path leads with the Android Photo Picker (PickVisualMedia), which itself
 *      falls back to GET_CONTENT on devices with no picker module.
 *   3. If every tier throws, surface the REAL exception (class + message) instead of a generic toast, so
 *      there is nothing left to guess, and point the user at the always-available "share a file into the
 *      app" path (the ACTION_SEND inbox in the manifest).
 *   4. Camera is feature-gated with `hasSystemFeature(FEATURE_CAMERA_ANY)` and writes to a FileProvider
 *      URI — mirrors both reference apps.
 * All permission-free: SAF / GET_CONTENT / the photo picker each hand back exactly the item the user
 * picks with a temporary read grant; no INTERNET, storage, media or camera permission is ever declared.
 */

private const val TAG = "RobustPicker"

/** Pull the URIs out of an ACTION_GET_CONTENT / chooser result — multi in clipData, single in data. */
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

private fun fatal(kind: String, e: Throwable?): String =
    "Couldn't open the $kind picker: ${e?.javaClass?.simpleName ?: "unknown"}: ${e?.message ?: ""}. " +
        "Tip: open the file in another app and Share → ToDo Companion instead."

/**
 * Robust FILE picker. Call at the top level of a screen composable; returns `launch(mimeTypes)`.
 * Handles single OR multi-select — the callback receives every URI the user picked.
 */
@Composable
fun rememberFilePicker(
    onError: (String) -> Unit = {},
    onPicked: (List<Uri>) -> Unit,
): (Array<String>) -> Unit {
    val context = LocalContext.current
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(listOf(it)) }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) extractUris(res.data).takeIf { it.isNotEmpty() }?.let(onPicked)
    }
    return { mimeTypes ->
        val types = if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes
        val primary = if (types.size == 1) types[0] else "*/*"
        val getIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primary
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val err = launchChain(
            listOf(
                "OPEN_DOCUMENT" to { openDoc.launch(types) },
                "GET_CONTENT" to { getContent.launch(primary) },
                "CHOOSER" to { chooser.launch(Intent.createChooser(getIntent, "Select a file")) },
            ),
            "file",
        )
        if (err != null) onError(fatal("file", err))
    }
}

/**
 * Robust PHOTO/VIDEO picker. Leads with the Android Photo Picker (no permission, built-in GET_CONTENT
 * fallback), then GET_CONTENT, then a chooser. Call at the top level; returns `launch()`.
 */
@Composable
fun rememberPhotoPicker(
    onError: (String) -> Unit = {},
    onPicked: (List<Uri>) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(listOf(it)) }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) extractUris(res.data).takeIf { it.isNotEmpty() }?.let(onPicked)
    }
    return {
        val getIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val err = launchChain(
            listOf(
                "PHOTO_PICKER" to {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                "GET_CONTENT" to { getContent.launch("image/*") },
                "CHOOSER" to { chooser.launch(Intent.createChooser(getIntent, "Select photos")) },
            ),
            "photo",
        )
        if (err != null) onError(fatal("photo", err))
    }
}

/**
 * Robust CAMERA capture into a FileProvider URI. Feature-gated like TickTick/Todoist. Call at the top
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
