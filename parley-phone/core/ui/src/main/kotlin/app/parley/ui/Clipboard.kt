package app.parley.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Parley's one way to copy and paste: one sensitive-flag policy, and a clipboard that refuses never crashes a screen. */
object Clipboard {
    /**
     * Copies [text]. [sensitive] (the default: numbers, names, drafts, keys) keeps it out of the Android 13+
     * clipboard preview, and keyboards that honour the flag leave it out of their history and cloud sync; before 13
     * the constant doesn't exist, but keyboards already read the same key. Android 13+ confirms copies itself, so
     * [confirm] (core/ui's "Copied" when null) shows only before that.
     */
    fun copy(context: Context, text: CharSequence, sensitive: Boolean = true, confirm: CharSequence? = null, label: String = "text") {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
        }
        val copied = try {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) != null
        } catch (_: RuntimeException) {
            false
        }
        if (copied && Build.VERSION.SDK_INT < 33) showMessage(context, confirm ?: context.getString(R.string.ui_copied))
    }

    /** The clipboard's text, at most [max] characters; null when empty or unreadable. Read only on the user's tap. */
    fun readText(context: Context, max: Int = Int.MAX_VALUE): String? = try {
        context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (_: RuntimeException) {
        null
    }?.takeIf { it.isNotBlank() }?.take(max)
}
