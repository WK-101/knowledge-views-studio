package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.MessengerApp
import app.parley.common.MessengerCatalog

/**
 * Calls an app made over the internet (WhatsApp, Signal…) and logged in the system call log, which Android 14 allows
 * and One UI 9 shows. A row is told apart by the phone account that logged it (`PHONE_ACCOUNT_COMPONENT_NAME`, a
 * flattened component "package/class"): the phone network's accounts belong to the telephony packages; any other
 * package is the app the call went through. Such a call never touched a SIM, so calling back goes through that app,
 * and the phone network's own features (SIM advice, the out-of-service check, ring facts, missed-call follow-up)
 * leave it out.
 */
object InternetCalls {
    /**
     * Packages whose phone accounts are the phone network's: the SIM and Wi-Fi calling ([TELEPHONY_PACKAGE]) and
     * Telecom itself (emergency and test accounts). The packages of the phone's SIM accounts are added at run time,
     * for phones whose maker moved them.
     */
    const val TELEPHONY_PACKAGE = "com.android.phone"
    val TELEPHONY_PACKAGES: Set<String> = setOf(TELEPHONY_PACKAGE, "com.android.server.telecom", "android")

    /**
     * The package of the app that logged a call with [component], or null for a phone call: no account (rows from
     * before Android kept one, restored rows), an unreadable one, or one of the [telephony] packages.
     */
    fun appPackage(component: String?, telephony: Set<String> = TELEPHONY_PACKAGES): String? {
        val pkg = component?.substringBefore('/', missingDelimiterValue = "")?.trim().orEmpty()
        if (pkg.isEmpty() || pkg in telephony) return null
        return pkg
    }

    /** The call went through an app, not the phone network. */
    fun isInternet(e: CallEntry): Boolean = e.appPackage != null

    /** The app's name when Parley knows it ("WhatsApp"), else null (the caller asks Android for its label). */
    fun knownLabel(pkg: String): String? = MessengerCatalog.forPackage(pkg)?.label

    /** How Call back reaches the person in the app the call went through, best first. */
    enum class Route {
        /** The app added a call row for this number to the contact: opening it starts the call in the app. */
        CALL_ROW,

        /** A chat with the number in the app (its call button is one tap away). */
        CHAT,

        /** The app itself, at its start screen. */
        OPEN_APP,

        /** The app is gone: only "Call by phone" is left. */
        NONE,
    }

    /**
     * The route for calling back through [pkg]. [hasCallRow]: the app linked this number with a voice row;
     * [installed]: the app is installed and enabled; [canChat]: a chat link can be built for the number.
     */
    fun route(pkg: String, installed: Boolean, hasCallRow: Boolean, canChat: Boolean): Route = when {
        !installed -> Route.NONE
        hasCallRow -> Route.CALL_ROW
        canChat && MessengerApp.forPackage(pkg) != null -> Route.CHAT
        else -> Route.OPEN_APP
    }
}
