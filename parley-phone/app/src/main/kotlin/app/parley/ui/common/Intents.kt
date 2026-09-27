package app.parley.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import app.parley.R
import app.parley.ui.showMessage

object Intents {
    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage(context, context.getString(R.string.main_no_app))
        }
    }

    fun sms(context: Context, number: String) = launch(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
    fun email(context: Context, address: String) = launch(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))
    fun map(context: Context, address: String) = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))
    fun web(context: Context, url: String) {
        val u = if (url.startsWith("http")) url else "https://$url"
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    fun shareVcard(context: Context, uri: Uri, name: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, Intent.createChooser(i, context.getString(R.string.main_share_contact)))
    }

    fun shareText(context: Context, text: String) {
        launch(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    /**
     * Copies [text]. [sensitive] (the default: numbers, keys, codes) keeps it out of the Android 13+ clipboard
     * preview, and keyboards that honour the flag leave it out of their history and cloud sync.
     */
    fun copy(context: Context, text: String, sensitive: Boolean = true) {
        val clip = ClipData.newPlainText("number", text)
        if (sensitive) {
            // Before 33 the constant doesn't exist, but keyboards already read the same key.
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
        }
        runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) showMessage(context, context.getString(R.string.main_copied))
    }
}
