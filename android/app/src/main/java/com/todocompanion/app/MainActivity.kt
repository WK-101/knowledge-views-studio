package com.todocompanion.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.todocompanion.app.ui.AppRoot

// FragmentActivity (a ComponentActivity subclass) so androidx.biometric's BiometricPrompt can attach.
class MainActivity : FragmentActivity() {
    // Read by AppRoot; carries a one-shot launch action (e.g. from the home-screen widget).
    private val launchAction = mutableStateOf<String?>(null)
    // E9: a backup file URI handed to us by the user's file manager ("Open with"/"Share").
    private val importUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the branded splash window for the plain (transparent) theme before the first frame,
        // so the launch icon shows instantly but doesn't linger behind Compose.
        setTheme(R.style.Theme_ToDoCompanion)
        super.onCreate(savedInstanceState)
        launchAction.value = resolveAction(intent)
        importUri.value = resolveImport(intent)
        enableEdgeToEdge()
        setContent { AppRoot(launchAction = launchAction, importUri = importUri) }
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
        const val EXTRA_ACTION = "com.todocompanion.app.action"
        const val ACTION_QUICK_ADD = "quick_add"
        // Prefix carrying shared/selected text into quick-add: "quick_add_text:<text>".
        const val ACTION_QUICK_ADD_TEXT = "quick_add_text:"
    }
}
