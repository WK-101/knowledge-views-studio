package app.parley.messaging

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.provider.Telephony
import app.parley.common.MessengerApp
import app.parley.common.MessengerLink
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.data.DataContainer

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
    fun open(context: Context, link: MessengerLink, app: MessengerApp?): String? {
        val i = intent(link)
        if (i.resolveActivity(context.packageManager) == null) {
            return app?.let { MessengerLinks.unavailableMessage(it) } ?: "No SMS app is set up"
        }
        return try {
            context.startActivity(i)
            null
        } catch (_: ActivityNotFoundException) {
            app?.let { MessengerLinks.unavailableMessage(it) } ?: "No SMS app is set up"
        } catch (_: SecurityException) {
            app?.let { MessengerLinks.unavailableMessage(it) } ?: "No SMS app is set up"
        }
    }
}

/**
 * "Save as a temporary contact": a contact that deletes itself (and its call history) after [DEFAULT_DAYS] days.
 * A thin wrapper over [app.parley.data.people.TemporaryContacts.create], the one API for temporary contacts.
 */
object TemporaryContact {
    const val DEFAULT_DAYS = app.parley.data.people.TemporaryContacts.DEFAULT_DAYS

    /**
     * Saves [number] as a temporary contact. Returns the phone contact's id, or, when [private] (a vault contact,
     * invisible to other apps), the negative vault id (`-vaultId`, as the editor does); null if nothing was saved.
     * Callers open `Routes.vault(-id)` for negative ids.
     */
    suspend fun save(c: DataContainer, number: String, name: String, days: Int = DEFAULT_DAYS, private: Boolean = false): Long? =
        c.temporaries.create(number, name, days, private)?.id

    /** Suggested name for a number met through a messenger ("WhatsApp · +92 300 1234567"). */
    fun suggestedName(number: String, via: String?, region: String): String {
        val shown = NumberText.toE164(number, region)?.let(NumberText::formatInternational) ?: number
        return if (via.isNullOrBlank()) shown else "$via · $shown"
    }
}
