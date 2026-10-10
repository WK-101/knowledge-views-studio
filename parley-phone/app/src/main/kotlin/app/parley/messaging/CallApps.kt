package app.parley.messaging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.MessengerCatalog
import app.parley.common.MessengerLink
import app.parley.common.MessengerLinks
import app.parley.common.NumberText
import app.parley.common.ReachKind
import app.parley.common.calls.InternetCalls
import app.parley.common.catching
import app.parley.data.MessengerAction
import app.parley.data.Messengers
import app.parley.data.PhoneEnv
import app.parley.ui.showMessage
import app.parley.ui.startOrSay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The apps internet calls in the call log went through: their names, and calling back in them. */
object CallApps {
    private val labels = ConcurrentHashMap<String, String>()

    /** [pkg]'s name: the one Parley knows ("WhatsApp"), else Android's (launcher apps are visible), else the package. */
    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        InternetCalls.knownLabel(pkg) ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.ifBlank { null } ?: pkg
    }

    fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Calls [number] back in [pkg]: the call row the app added to the contact (the call starts in the app), else a
     * chat with the number (its call button is at the top), else the app itself. Never the phone network. Returns
     * false when nothing could be opened.
     */
    suspend fun callBack(context: Context, pkg: String, number: String, accountId: String?): Boolean {
        val e164 = NumberText.toE164(number, PhoneEnv.countryIso(context, accountId))
        val row = voiceRow(context, pkg, e164 ?: number)
        val app = MessengerApp.forPackage(pkg)
        val chat = if (app != null && e164 != null) MessengerLinks.build(app, e164) else null
        return when (InternetCalls.route(pkg, installed(context, pkg), hasCallRow = row != null, canChat = chat != null)) {
            InternetCalls.Route.CALL_ROW -> row != null && context.startOrSay(row.intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            InternetCalls.Route.CHAT -> chat != null && openChat(context, chat, app)
            InternetCalls.Route.OPEN_APP -> openApp(context, pkg)
            InternetCalls.Route.NONE -> false
        }
    }

    /** The voice-call row [pkg] added for [number] to a saved contact, if any. */
    private suspend fun voiceRow(context: Context, pkg: String, number: String): MessengerAction? = withContext(Dispatchers.IO) {
        val accountTypes = MessengerCatalog.forPackage(pkg)?.accountTypes.orEmpty() + pkg
        catching { Messengers.actionsForNumber(context, number) }.getOrDefault(emptyList())
            .firstOrNull { it.kind == ReachKind.VOICE && it.accountType in accountTypes }
    }

    private fun openChat(context: Context, link: MessengerLink, app: MessengerApp?): Boolean {
        val opened = MessengerLauncher.open(context, link, app) == null
        if (opened) showMessage(context, context.getString(R.string.reach_call_via_chat_hint), long = true)
        return opened
    }

    private fun openApp(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startOrSay(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: false
}
