package com.cairn.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.cairn.reader.work.CairnWork

/**
 * A no-UI share target. When an incoming share (or VIEW) intent carries a URL, queues it for
 * saving + on-device extraction. When it carries only text — a forwarded newsletter, an email,
 * a highlighted passage — saves that text straight into Read Later. Shows a brief confirmation
 * and finishes.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        when {
            url != null -> {
                CairnWork.saveUrl(applicationContext, url)
                Toast.makeText(this, "Saving to Cairn…", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val text = sharedText(intent)?.trim()
                if (!text.isNullOrEmpty() && text.length >= MIN_TEXT_LEN) {
                    val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.ifEmpty { null }
                    CairnWork.saveText(applicationContext, subject, text)
                    Toast.makeText(this, "Saved to Read Later", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }

    private fun sharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
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
        // Below this, a share is almost certainly a stray token, not a passage worth keeping.
        const val MIN_TEXT_LEN = 40
    }
}
