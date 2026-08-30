package com.todocompanion.app.util

import android.content.Intent
import android.net.Uri

/**
 * R45 — the picker, rebuilt from the ground up the way the open-source Tasks.org does it: with NO
 * Jetpack Compose ActivityResult registry anywhere in the path.
 *
 * WHY (the real, final root cause). Every earlier attempt launched through
 * `rememberLauncherForActivityResult` / `ActivityResultContracts`. That registry launches via
 * `ActivityCompat.startActivityForResult(activity, intent, requestCode, optionsBundle)` — it always
 * hands the framework an `ActivityOptions` bundle. On the user's ROM that bundle is rejected and
 * `.launch()` throws `java.lang.IllegalArgumentException` for EVERY route (gallery, camera, document)
 * identically — which is exactly the symptom, and exactly why the intent itself was never the problem.
 * Tasks.org never touches that path: it calls the classic `Activity.startActivityForResult(intent,
 * requestCode)` (no options bundle) and reads the result in `onActivityResult`. That is what this does.
 *
 * The UI calls these functions; `MainActivity` owns `launcher` and performs the classic
 * startActivityForResult, then routes the result back through the request's callback. Every route is a
 * separate, independent intent (Tasks.org's menu-of-routes), and the gallery route uses
 * `ACTION_PICK` on the MediaStore, which the media provider serves on essentially every ROM — including
 * de-Googled ones with the document UI stripped. Bytes are copied in with `openInputStream`; no
 * INTERNET / storage / media / camera permission is ever needed.
 */
object SystemPicker {
    enum class Op { GALLERY, CAMERA, OPEN_FILE, CREATE_FILE, OPEN_TREE }

    class Request(
        val op: Op,
        val mimeTypes: Array<String>,
        val createName: String?,
        val onResult: (List<Uri>) -> Unit,
        val onError: (String) -> Unit,
    )

    /** Set by MainActivity.onCreate; runs the classic (no-options) startActivityForResult. */
    @Volatile
    var launcher: ((Request) -> Unit)? = null

    private fun go(req: Request) {
        val l = launcher
        if (l == null) req.onError("The picker isn't ready — reopen the screen and try again.") else l(req)
    }

    /** Pick one or more files of any/some type (ACTION_OPEN_DOCUMENT). */
    fun openFiles(mimeTypes: Array<String> = arrayOf("*/*"), onError: (String) -> Unit = {}, onUris: (List<Uri>) -> Unit) =
        go(Request(Op.OPEN_FILE, mimeTypes, null, onUris, onError))

    /** Pick a single file. */
    fun openFile(mimeTypes: Array<String> = arrayOf("*/*"), onError: (String) -> Unit = {}, onUri: (Uri) -> Unit) =
        go(Request(Op.OPEN_FILE, mimeTypes, null, { it.firstOrNull()?.let(onUri) }, onError))

    /** Pick photos from the gallery (ACTION_PICK on MediaStore) — works on ROMs with no document UI. */
    fun gallery(onError: (String) -> Unit = {}, onUris: (List<Uri>) -> Unit) =
        go(Request(Op.GALLERY, arrayOf("image/*"), null, onUris, onError))

    fun galleryOne(onError: (String) -> Unit = {}, onUri: (Uri) -> Unit) =
        go(Request(Op.GALLERY, arrayOf("image/*"), null, { it.firstOrNull()?.let(onUri) }, onError))

    /** Capture a photo with the system camera into a FileProvider URI (no CAMERA permission). */
    fun camera(onError: (String) -> Unit = {}, onUri: (Uri) -> Unit) =
        go(Request(Op.CAMERA, emptyArray(), null, { it.firstOrNull()?.let(onUri) }, onError))

    /** Create a new document to write into (ACTION_CREATE_DOCUMENT) — for exports. */
    fun createFile(mimeType: String, suggestedName: String, onError: (String) -> Unit = {}, onUri: (Uri) -> Unit) =
        go(Request(Op.CREATE_FILE, arrayOf(mimeType), suggestedName, { it.firstOrNull()?.let(onUri) }, onError))

    /** Pick a folder (ACTION_OPEN_DOCUMENT_TREE) — for the backup/sync destinations. */
    fun openTree(onError: (String) -> Unit = {}, onUri: (Uri) -> Unit) =
        go(Request(Op.OPEN_TREE, emptyArray(), null, { it.firstOrNull()?.let(onUri) }, onError))

    /** Read the URIs out of a picker result — multi in clipData, single in data. */
    fun extractUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out.add(it) }
        else data.data?.let { out.add(it) }
        return out
    }
}

/** Common mime-type sets — imports include the wildcard because many providers report files as
 *  application/octet-stream and won't surface under a narrow type. */
object PickTypes {
    val ANY = arrayOf("*/*")
    val IMAGES = arrayOf("image/*")
    val IMAGES_VIDEO = arrayOf("image/*", "video/*")
    val BACKUP = arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
    val ICS = arrayOf("text/calendar", "application/octet-stream", "*/*")
}
