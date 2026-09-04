package com.cairn.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.cairn.reader.work.CairnWork

/**
 * A no-UI share target. Extracts a URL from an incoming share (or VIEW) intent, queues
 * it for saving + on-device extraction, shows a brief confirmation, and finishes.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url != null) {
            CairnWork.saveUrl(applicationContext, url)
            Toast.makeText(this, "Saving to Cairn…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No link found to save", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun extractUrl(intent: Intent?): String? {
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return URL_REGEX.find(raw)?.value
            ?: raw.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
