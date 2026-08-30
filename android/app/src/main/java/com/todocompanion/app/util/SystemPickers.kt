package com.todocompanion.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * R42 — the ROM-proof pickers. Diagnosis (confirmed against the decompiled TickTick APK and the Android
 * docs): raw ACTION_OPEN_DOCUMENT / CREATE_DOCUMENT and the Photo Picker are serviced ONLY by the system
 * DocumentsUI / picker module. On debloated or de-Googled ROMs (MIUI/HyperOS "uninstall for user 0",
 * LineageOS, /e/OS, GrapheneOS, Android Go) those components are absent, so every raw launcher throws
 * ActivityNotFoundException — exactly the "no file picker / no photo picker / .ics import & export stuck"
 * the user hit. TickTick survives because it uses ACTION_GET_CONTENT wrapped in Intent.createChooser:
 *   • GET_CONTENT resolves to ANY gallery / file-manager / cloud app, not DocumentsUI alone; and
 *   • the chooser is a framework component (ResolverActivity) that always resolves and enumerates
 *     handlers in the SYSTEM process, so package-visibility never hides them and it never throws.
 * These contracts adopt exactly that path for every read, and the camera goes through the chooser too.
 * No network, no storage/media/camera permission — all local IPC.
 */

private fun getContentChooser(mimeTypes: Array<String>, multiple: Boolean, title: String): Intent {
    val get = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
        if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // The chooser is the OS itself — always present, exempt from this app's package-visibility filter.
    return Intent.createChooser(get, title)
}

/** Reads BOTH result shapes: multi-select populates clipData; single-select populates data. */
private fun parseUris(result: Intent?): List<Uri> {
    if (result == null) return emptyList()
    val out = ArrayList<Uri>()
    val clip = result.clipData
    if (clip != null) {
        for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out.add(it) }
    } else {
        result.data?.let { out.add(it) }
    }
    return out
}

/** Multi-select file/media reader. Drop-in for ActivityResultContracts.OpenMultipleDocuments(). */
class PickContentMultiple(private val title: String = "Select") : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent = getContentChooser(input, true, title)
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        if (resultCode == Activity.RESULT_OK) parseUris(intent) else emptyList()
}

/** Single-select file reader. Drop-in for ActivityResultContracts.OpenDocument(). */
class PickContentSingle(private val title: String = "Select") : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent = getContentChooser(input, false, title)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) parseUris(intent).firstOrNull() else null
}

/**
 * Camera capture into a caller-supplied FileProvider URI, wrapped in the system chooser so it never
 * throws "no camera app" from this app's visibility filter (Android 11 restricts IMAGE_CAPTURE to the
 * pre-installed camera; the chooser can still reach it). Drop-in for ActivityResultContracts.TakePicture().
 */
class CaptureImageChooser : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent {
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(capture, "Take photo").apply {
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
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
