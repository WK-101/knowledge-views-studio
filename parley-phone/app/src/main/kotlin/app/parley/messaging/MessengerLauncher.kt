package app.parley.messaging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.core.net.toUri
import android.provider.Telephony
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.MessengerLink
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.data.DataContainer
import app.parley.data.TemporaryContacts
import app.parley.ui.Clipboard
import app.parley.ui.startOrSay

/** Starts messenger links. Every link goes to its app directly; nothing is ever handed to a browser. */
object MessengerLauncher {
    /** Whether [app] is installed and enabled (needs its `<queries>` package entry). */
    fun isAvailable(context: Context, app: MessengerApp): Boolean = try {
        context.packageManager.getApplicationInfo(app.packageName, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installed(context: Context): List<MessengerApp> = MessengerApp.entries.filter { isAvailable(context, it) }

    /** The default SMS app, so the SMS link has an explicit package too. */
    fun smsPackage(context: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    fun intent(link: MessengerLink): Intent = Intent(link.action, link.uri.toUri()).apply {
        link.packageName?.let { setPackage(it) }
        link.extras.forEach { (k, v) -> putExtra(k, v) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Opens [link]. Returns null on success, or a message to show: "Install or enable WhatsApp" when the app can't
     * take the link. Never falls back to another app.
     */
    /**
     * Opens a chat with [e164] in [app] with [draft] prefilled; for apps that can't take text (Signal, Viber) the
     * draft is copied, marked sensitive, for pasting. Returns null on success or the message to show.
     */
    fun openChat(context: Context, app: MessengerApp, e164: String, draft: String?): String? {
        val link = MessengerLinks.build(app, e164, draft)
            ?: return MessagingText.unavailable(context.resources, e164) ?: context.getString(R.string.msg_cant_open_number)
        if (!draft.isNullOrBlank() && !app.takesText) Clipboard.copy(context, draft)
        return open(context, link, app)
    }

    fun open(context: Context, link: MessengerLink, app: MessengerApp?): String? {
        val i = intent(link)
        val cantOpen = { app?.let { MessagingText.installOrEnable(context.resources, it) } ?: context.getString(R.string.msg_no_sms_app) }
        if (i.resolveActivity(context.packageManager) == null) return cantOpen()
        return if (context.startOrSay(i)) null else cantOpen()
    }
}

/**
 * "Save as a temporary contact": deletes itself (and its call history) after [DEFAULT_DAYS]. Private (kept in
 * Parley's vault, invisible to WhatsApp and other apps) unless the user chooses "Save visible to other apps".
 */
object TemporaryContact {
    const val DEFAULT_DAYS = TemporaryContacts.DEFAULT_DAYS

    suspend fun save(c: DataContainer, number: String, name: String, private: Boolean = true, days: Int = DEFAULT_DAYS): TemporaryContacts.Saved? =
        TemporaryContacts.save(c, name, number, days, private = private, purgeHistory = true)

    /** What to tell the user after saving. */
    fun savedMessage(res: Resources, saved: TemporaryContacts.Saved?): String = when {
        saved == null -> res.getString(R.string.keypad_save_failed)
        saved.private -> res.getQuantityString(R.plurals.msg_saved_private_for, DEFAULT_DAYS, DEFAULT_DAYS)
        else -> res.getQuantityString(R.plurals.msg_saved_visible_for, DEFAULT_DAYS, DEFAULT_DAYS)
    }

    /** Suggested name for a number met through a messenger ("WhatsApp · +92 300 1234567"). */
    fun suggestedName(number: String, via: String?, region: String): String {
        val shown = NumberText.toE164(number, region)?.let(NumberText::formatInternational) ?: number
        return if (via.isNullOrBlank()) shown else "$via · $shown"
    }
}
