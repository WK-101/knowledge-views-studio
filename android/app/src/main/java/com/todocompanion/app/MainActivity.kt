package com.todocompanion.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.todocompanion.app.ui.AppRoot
import com.todocompanion.app.util.SystemPicker
import kotlinx.coroutines.launch
import java.io.File

// FragmentActivity (a ComponentActivity subclass) so androidx.biometric's BiometricPrompt can attach.
class MainActivity : FragmentActivity() {
    // Read by AppRoot; carries a one-shot launch action (e.g. from the home-screen widget).
    private val launchAction = mutableStateOf<String?>(null)
    // E9: a backup file URI handed to us by the user's file manager ("Open with"/"Share").
    private val importUri = mutableStateOf<Uri?>(null)

    // R45 — the ground-up picker. The whole app picks files/photos/documents through SystemPicker, which
    // calls THIS classic startActivityForResult (no ActivityOptions bundle — the thing the ROM rejected
    // with IllegalArgumentException through the Compose registry). Mirrors how Tasks.org launches.
    private var pendingPick: SystemPicker.Request? = null
    private var cameraOutputUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the branded splash window for the plain (transparent) theme before the first frame,
        // so the launch icon shows instantly but doesn't linger behind Compose.
        setTheme(R.style.Theme_ToDoCompanion)
        super.onCreate(savedInstanceState)
        launchAction.value = resolveAction(intent)
        importUri.value = resolveImport(intent)
        enableEdgeToEdge()
        // Security: apply the "secure screen" flag reactively — when on, it blocks screenshots, screen
        // recording, and the recents-thumbnail from capturing task content. Off by default; fully local.
        lifecycleScope.launch {
            (application as App).repository.allSettings.collect { rows ->
                fun flag(key: String) = rows.firstOrNull { it.key == key }?.value?.toBooleanStrictOrNull() ?: false
                if (flag(com.todocompanion.app.domain.AppSettings.Keys.SECURE_SCREEN)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                com.todocompanion.app.reminders.Notifications.lockscreenPrivate = flag(com.todocompanion.app.domain.AppSettings.Keys.LOCKSCREEN_PRIVACY)
            }
        }
        // R45 — route every file/photo/document pick through the classic Activity result API.
        SystemPicker.launcher = { req -> launchPicker(req) }
        setContent { AppRoot(launchAction = launchAction, importUri = importUri) }
    }

    /**
     * The one place a picker is launched — a classic startActivityForResult with NO options bundle, the
     * exact mechanism Tasks.org uses. Each op is an independent intent (menu of routes); the gallery
     * route is ACTION_PICK on the MediaStore, which opens on ROMs with no document UI.
     */
    private fun launchPicker(req: SystemPicker.Request) {
        pendingPick = req
        // A picker op can offer more than one route; we try them in order until one actually launches, so a
        // ROM missing the first handler still gets the next. OPEN_FILE deliberately leads with GET_CONTENT.
        val candidates: List<Intent> = try {
            when (req.op) {
                SystemPicker.Op.GALLERY -> listOf(Intent(Intent.ACTION_PICK).apply {
                    setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                SystemPicker.Op.OPEN_FILE -> openFileCandidates(req.mimeTypes)
                SystemPicker.Op.CREATE_FILE -> listOf(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addFlags(DOC_GRANT_FLAGS)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = req.mimeTypes.firstOrNull() ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, req.createName ?: "file")
                })
                SystemPicker.Op.OPEN_TREE -> listOf(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(DOC_GRANT_FLAGS)
                    putExtra("android.content.extra.SHOW_ADVANCED", true)
                })
                SystemPicker.Op.CAMERA -> {
                    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                        pendingPick = null; req.onError("No camera on this device."); return
                    }
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    val f = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                    cameraOutputUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                    listOf(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
        } catch (e: Exception) {
            pendingPick = null; req.onError("Couldn't build the picker: ${e.javaClass.simpleName}"); return
        }
        var lastErr: String? = null
        for (intent in candidates) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, RC_PICK)
                return
            } catch (e: Exception) {
                lastErr = e.javaClass.simpleName
            }
        }
        pendingPick = null
        req.onError("This device has no app for that (${lastErr ?: "none"}). Try the Photo or Camera button, or Share a file into the app.")
    }

    /**
     * The file-open routes, richest first. THE FIX for "I can only see the files our app exported":
     * ACTION_OPEN_DOCUMENT is served ONLY by DocumentsUI. On a de-Googled ROM whose DocumentsUI is
     * stripped, that surfaces almost no roots, so the picker collapses to "Recent" — which shows only
     * the json/csv/ics our own app just wrote. ACTION_GET_CONTENT is instead served by the ROM's real
     * file-manager and gallery apps (and DocumentsUI too), so a chooser over it reaches the whole
     * filesystem. We copy the bytes immediately and release the grant, so GET_CONTENT's non-persistable
     * URI is fine here (only the backup/sync FOLDER, via OPEN_TREE, needs a durable grant). Both routes
     * use a wildcard type so every file type is offered; OPEN_DOCUMENT stays as the fallback.
     */
    private fun openFileCandidates(mimeTypes: Array<String>): List<Intent> {
        val mime = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
        fun Intent.withTypes() = apply {
            type = mime
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE) }.withTypes()
        val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addFlags(DOC_GRANT_FLAGS)
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra("android.content.extra.FANCY", true)
            putExtra("android.content.extra.SHOW_FILESIZE", true)
        }.withTypes()
        // A chooser over GET_CONTENT lists every file source on the device — the ROM's file manager,
        // gallery, Downloads and DocumentsUI — instead of only DocumentsUI's crippled "Recent". But a
        // chooser always "launches" even with zero targets, so only lead with it when something actually
        // handles GET_CONTENT; otherwise go straight to the OPEN_DOCUMENT (DocumentsUI) route.
        val hasGetContent = packageManager.queryIntentActivities(getContent, 0).isNotEmpty()
        return if (hasGetContent) listOf(Intent.createChooser(getContent, "Choose a file"), openDoc)
        else listOf(openDoc, getContent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_PICK) return
        val req = pendingPick ?: return
        pendingPick = null
        if (resultCode != RESULT_OK) return
        val uris = when (req.op) {
            SystemPicker.Op.CAMERA -> listOfNotNull(cameraOutputUri)
            SystemPicker.Op.CREATE_FILE, SystemPicker.Op.OPEN_TREE -> listOfNotNull(data?.data)
            else -> SystemPicker.extractUris(data)
        }
        // R47 — take a persistable READ|WRITE grant on document/tree results, exactly as Tasks.org does.
        // Because the request intent asked for a persistable grant (DOC_GRANT_FLAGS), this now succeeds on
        // the user's ROM and makes the grant durable — so the copy coroutine reads without SecurityException.
        // MediaStore ACTION_PICK URIs aren't persistable, so it's scoped to the document routes and wrapped.
        if (req.op == SystemPicker.Op.OPEN_FILE || req.op == SystemPicker.Op.OPEN_TREE) {
            val mode = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            for (u in uris) runCatching { contentResolver.takePersistableUriPermission(u, mode) }
        }
        if (uris.isNotEmpty()) req.onResult(uris)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchAction.value = resolveAction(intent)
        importUri.value = resolveImport(intent)
    }

    /**
     * E9 — extract a backup file URI from an incoming intent: a VIEW ("Open with…") carries it in
     * intent.data; a non-text SEND ("Share…") in EXTRA_STREAM. Text SEND stays with quick-add
     * (resolveAction), so we exclude text/plain here. The content:// URI grants a temporary read,
     * so no storage permission is needed — the file manager already opened the door.
     */
    private fun resolveImport(intent: Intent?): Uri? {
        if (intent == null) return null
        // U13: our own track deep link is not a file to import — it's handled in resolveAction.
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "todocompanion") return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (intent.type != "text/plain")
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) else null
            else -> null
        }
    }

    /**
     * Maps the launch intent to a one-shot action. Besides our own EXTRA_ACTION (from widgets and
     * shortcuts), we honour the system share sheet (ACTION_SEND) and the text-selection menu
     * (ACTION_PROCESS_TEXT) so any app can hand text straight into quick-add — capture without
     * ever leaving what you're doing. Still fully offline: the text arrives in the intent.
     */
    private fun resolveAction(intent: Intent?): String? {
        if (intent == null) return null
        // U13: an NFC tag or QR encoding todocompanion://track?activity=<id> (or ?name=<name>) starts a timer.
        if (intent.action == Intent.ACTION_VIEW) {
            val d = intent.data
            if (d?.scheme == "todocompanion" && d.host == "track") {
                d.getQueryParameter("activity")?.let { return ACTION_TRACK_ACTIVITY + it }
                d.getQueryParameter("name")?.let { return ACTION_TRACK_NAME + it }
            }
            // W6: a routine tag launches a whole ritual.
            if (d?.scheme == "todocompanion" && d.host == "routine") {
                d.getQueryParameter("name")?.let { return ACTION_RUN_ROUTINE + it }
            }
            // R17: static launcher shortcuts route through deep links so they carry reliably.
            if (d?.scheme == "todocompanion") when (d.host) {
                "today" -> return "open_today"
                "donext" -> return "open_donext"
                "focus" -> return "open_focus"
            }
        }
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val body = intent.getStringExtra(Intent.EXTRA_TEXT)
                listOfNotNull(subject?.takeIf { it.isNotBlank() }, body?.takeIf { it.isNotBlank() })
                    .firstOrNull()
            } else null
            "android.intent.action.PROCESS_TEXT" ->
                intent.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT")?.toString()
            else -> null
        }
        if (!shared.isNullOrBlank()) return ACTION_QUICK_ADD_TEXT + shared.trim().take(500)
        return intent.getStringExtra(EXTRA_ACTION)
    }

    companion object {
        private const val RC_PICK = 0x9A01   // R45 — the one request code for every SystemPicker launch
        // R47 — the exact grant flags Tasks.org puts on its document-picker requests. Asking for a
        // persistable READ+WRITE (plus PREFIX) grant up-front is what makes de-Googled document providers
        // return a durable, readable URI — without it the read threw SecurityException.
        private const val DOC_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        const val EXTRA_ACTION = "com.todocompanion.app.action"
        const val ACTION_QUICK_ADD = "quick_add"
        // Prefix carrying shared/selected text into quick-add: "quick_add_text:<text>".
        const val ACTION_QUICK_ADD_TEXT = "quick_add_text:"
        // U13: NFC/QR/shortcut "start tracking" — by activity id or by activity name.
        const val ACTION_TRACK_ACTIVITY = "track_activity:"
        const val ACTION_TRACK_NAME = "track_name:"
        // W6: run a routine bundle by name.
        const val ACTION_RUN_ROUTINE = "run_routine:"
    }
}
