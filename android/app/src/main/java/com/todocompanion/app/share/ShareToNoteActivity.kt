package com.todocompanion.app.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.todocompanion.app.App
import com.todocompanion.app.data.entity.NoteEntity
import kotlinx.coroutines.launch

/**
 * Wave 2 · Share-to-Kairo — the system share sheet (and the text-selection "Kairo note" action) can drop
 * any text straight into a new note, so the app is a capture target for the whole phone: an article, a
 * quote, a chat message, a snippet you highlighted. It writes a note into the current workspace's inbox
 * and finishes instantly — no window, no permission, entirely on-device. (Sharing text to *task* capture
 * stays on MainActivity; this is the notes counterpart, offered as a distinct share target.)
 */
class ShareToNoteActivity : Activity() {
    private companion object { const val MAX_SHARE_CHARS = 500_000 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)?.trim().orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        if (text.isBlank() && subject.isBlank()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        // Title: the share's subject (a page/message title) when present, else a short lead from the text.
        // The full shared text always lands in the body, so nothing is lost when a lead is derived.
        val title = if (subject.isNotBlank()) subject.take(120)
        else text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
        // SEC — an external app controls this text; cap it so a hostile/huge share can't spike memory or
        // wedge the editor. 500k chars is far beyond any real clipping while still bounding abuse.
        val body = if (text.length > MAX_SHARE_CHARS) text.take(MAX_SHARE_CHARS) else text

        val app = applicationContext as App
        app.appScope.launch {
            val ws = runCatching { app.repository.settingsSnapshot().activeWorkspaceId }
                .getOrDefault(com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID)
            app.repository.upsertNote(NoteEntity(id = "", kind = "note", workspaceId = ws, title = title, body = body))
        }
        Toast.makeText(this, "Saved to Kairo notes", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** The shared text — from ACTION_SEND (EXTRA_TEXT) or the selection menu's ACTION_PROCESS_TEXT. */
    private fun extractText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)?.toString()
            else -> intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}
