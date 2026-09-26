package app.parley.ui.qr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import app.parley.R
import app.parley.common.qr.QrApp
import app.parley.common.qr.QrPayload
import app.parley.common.qr.WifiSecurity

/**
 * Q4: what the result sheet's buttons do. Every one runs only on the user's tap. Intents name their target app
 * wherever one is known (the messenger's package, the default browser, the SMS app), and nothing here uses the
 * network: a browser or another app does, after the user chose to leave Parley.
 */
object QrActions {
    /** Copies [text]; [sensitive] (a Wi-Fi password) keeps it out of clipboard previews on Android 13+. */
    fun copy(context: Context, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(context.getString(R.string.qs_clip_label), text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.qs_copied, Toast.LENGTH_SHORT).show()
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, context.getString(R.string.qs_share_chooser)))
    }

    /** Starts [intent]; false (with a message) when no app takes it. */
    fun start(context: Context, intent: Intent, missing: Int = R.string.qs_no_app): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
        false
    } catch (_: SecurityException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
        false
    }

    /** The first installed package of [app], or null. */
    fun installedPackage(context: Context, app: QrApp): String? = app.packages.firstOrNull { pkg ->
        try {
            context.packageManager.getApplicationInfo(pkg, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Opens a messenger link in the app itself: first with an explicit package (the app or a known fork), then on
     * Android 11+ with FLAG_ACTIVITY_REQUIRE_NON_BROWSER (any other non-browser app that claims the link). False when
     * no app took it: the sheet then offers Copy link, Open in browser and the store page.
     */
    fun openInApp(context: Context, m: QrPayload.Messenger): Boolean {
        if (m.app.pasteOnly) {
            // No link opens Session or Briar with the payload: copy it and open the app for pasting.
            val pkg = installedPackage(context, m.app) ?: return false
            copy(context, m.handle ?: m.uri)
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            return runCatching { context.startActivity(launch) }.isSuccess
        }
        val uri = Uri.parse(m.uri)
        val pm = context.packageManager
        for (pkg in m.app.packages) {
            val i = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
            if (i.resolveActivity(pm) != null && runCatching { context.startActivity(i) }.isSuccess) return true
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            try {
                context.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
                // Not installed.
            } catch (_: SecurityException) {
                // Not exported to us.
            }
        }
        // WeChat and KakaoTalk profile codes are meant for their in-app scanner: opening the app is the next best thing.
        if (m.app.scanInside) {
            val pkg = installedPackage(context, m.app) ?: return false
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return false
            return runCatching { context.startActivity(launch) }.isSuccess
        }
        return false
    }

    /**
     * Opens [url] in the default browser (named explicitly when there is one, so an app claiming the link doesn't
     * take it), only after the user chose "Open in browser".
     */
    fun openInBrowser(context: Context, url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
        if (browser != null) intent.setPackage(browser)
        if (!start(context, intent, R.string.qs_no_browser) && browser != null) {
            start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE), R.string.qs_no_browser)
        }
    }

    /** The app's page in the phone's store app (Play Store, F-Droid, Aurora…); nothing opens a web page. */
    fun storePage(context: Context, pkg: String) {
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")), R.string.qs_no_store)
    }

    fun email(context: Context, e: QrPayload.Email) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, e.to.toTypedArray())
        if (e.cc.isNotEmpty()) i.putExtra(Intent.EXTRA_CC, e.cc.toTypedArray())
        if (e.bcc.isNotEmpty()) i.putExtra(Intent.EXTRA_BCC, e.bcc.toTypedArray())
        e.subject?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
        e.body?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        start(context, i, R.string.qs_no_mail_app)
    }

    fun map(context: Context, g: QrPayload.Geo) {
        val q = g.query?.let { "(" + it + ")" }.orEmpty()
        val uri = Uri.parse("geo:${g.lat},${g.lon}?q=" + Uri.encode("${g.lat},${g.lon}$q"))
        start(context, Intent(Intent.ACTION_VIEW, uri), R.string.qs_no_maps)
    }

    /** The calendar app's "new event" screen, filled in. Needs no calendar permission: the user saves it there. */
    fun calendar(context: Context, e: QrPayload.Event) {
        val zone = java.time.ZoneId.systemDefault()
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, e.summary)
        e.start?.let { s ->
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s.toEpochMillis(zone))
            if (s.allDay) i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        e.end?.let { i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.toEpochMillis(zone)) }
        e.location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        listOfNotNull(e.description, e.url).joinToString("\n\n").takeIf { it.isNotEmpty() }?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        start(context, i, R.string.qs_no_calendar)
    }

    /** Whether Android can offer to save [w] itself (Android 11+, and a network type suggestions support). */
    fun canAddWifi(w: QrPayload.Wifi): Boolean = Build.VERSION.SDK_INT >= 30 && suggestion(w) != null

    /**
     * Android 11+: Android's own "Save this network?" screen (Settings.ACTION_WIFI_ADD_NETWORKS). It needs no
     * permission: the system asks the user and saves the network, Parley never touches Wi-Fi.
     */
    fun addWifi(context: Context, w: QrPayload.Wifi): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val s = suggestion(w) ?: return false
        val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(s))
        return start(context, i)
    }

    private fun suggestion(w: QrPayload.Wifi): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsHiddenSsid(w.hidden)
            when (w.security) {
                WifiSecurity.OPEN -> Unit
                WifiSecurity.WPA -> b.setWpa2Passphrase(w.password ?: return null)
                WifiSecurity.SAE -> b.setWpa3Passphrase(w.password ?: return null)
                // WEP can't be suggested any more, and enterprise networks need certificates: Settings it is.
                WifiSecurity.WEP, WifiSecurity.EAP -> return null
            }
            b.build()
        } catch (_: IllegalArgumentException) {
            // A passphrase Android won't take (under 8 or over 63 characters): the user types it in Settings.
            null
        }
    }

    fun wifiSettings(context: Context) {
        start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
    }
}
