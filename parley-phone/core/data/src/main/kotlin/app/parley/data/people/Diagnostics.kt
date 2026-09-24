package app.parley.data.people

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import app.parley.common.AppSettings
import app.parley.common.people.Masking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Export diagnostics": a plain-text report the user can read before sharing. It has the app version, the
 * device, non-personal settings, permission states and recent errors. Numbers, e-mail addresses and content
 * URIs in error texts are masked unless the user turns masking off. No contacts, no call history.
 */
class Diagnostics(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    /** Remembers an error for the report (last [MAX] errors, on this phone only). */
    @Synchronized
    fun record(where: String, error: Throwable) {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        val start = maxOf(0, arr.length() - MAX + 1)
        for (i in start until arr.length()) next.put(arr.get(i))
        next.put(JSONObject().put("t", System.currentTimeMillis()).put("w", where).put("e", error.javaClass.simpleName + ": " + (error.message ?: "")))
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    fun report(settings: AppSettings, people: PeopleSettings, extra: Map<String, String>, mask: Boolean): String = buildString {
        fun m(s: String) = if (mask) Masking.mask(s) else s
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val pm = context.packageManager
        val pkg = runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
        appendLine("Parley diagnostics")
        appendLine("Created: ${fmt.format(Instant.now())}")
        appendLine("Numbers and e-mail addresses masked: ${if (mask) "yes" else "no"}")
        appendLine()
        appendLine("== App")
        appendLine("Version: ${pkg?.versionName} (${if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode else ""})")
        appendLine("Package: ${context.packageName}")
        appendLine("Debug build: ${context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        appendLine()
        appendLine("== Device")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Language: ${java.util.Locale.getDefault().toLanguageTag()}")
        appendLine()
        appendLine("== Permissions")
        pkg?.requestedPermissions.orEmpty().forEachIndexed { i, p ->
            val granted = (pkg?.requestedPermissionsFlags?.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            appendLine("${p.removePrefix("android.permission.")}: ${if (granted) "granted" else "not granted"}")
        }
        appendLine()
        appendLine("== Settings (no personal data)")
        appendLine("theme=${settings.themeMode} amoled=${settings.amoledBlack} dynamic=${settings.dynamicColor} density=${settings.density}")
        appendLine("answer=${settings.answerGesture} confirmBeforeCall=${settings.confirmBeforeCall} tones=${settings.dialpadTones} haptics=${settings.dialpadHaptics}")
        appendLine("startTab=${settings.startTab} sortByFirstName=${settings.sortByFirstName} simLabels=${settings.showSimLabels} rowActions=${settings.contactRowActions}")
        appendLine("defaultAccount=${settings.defaultAccountType ?: "phone"} (name ${if (settings.defaultAccountName != null) "set" else "not set"})")
        appendLine("quickReplies=${settings.quickReplies.size} customised=${settings.quickReplies != AppSettings.DEFAULT_QUICK_REPLIES}")
        appendLine("screening=${settings.screening}")
        appendLine("appLock=${settings.appLock} lockAfter=${settings.lockAfterMinutes} secureScreen=${settings.secureScreen} hideVault=${settings.hideVault} privateHistory=${settings.privateVaultHistory}")
        appendLine("unknownRingtone=${if (settings.unknownRingtone != null) "set" else "default"} repeatCaller=${settings.repeatCallerRingsThrough}")
        appendLine("birthdays=${settings.birthdayReminders}@${settings.birthdayReminderHour} nudges=${settings.reachOutNudges} retentionDays=${settings.callLogRetentionDays}")
        appendLine("secondLine=${people.secondLine} preferNickname=${people.preferNickname} favourites=${people.favoriteSort}/${people.favoriteColumns} privateByDefault=${people.privateByDefault} labelRingtones=${people.labelRingtones.size}")
        extra.forEach { (k, v) -> appendLine("$k=${m(v)}") }
        appendLine()
        appendLine("== Recent errors")
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        if (arr.length() == 0) appendLine("None recorded")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            appendLine("${fmt.format(Instant.ofEpochMilli(o.optLong("t")))} ${o.optString("w")}: ${m(o.optString("e"))}")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            appendLine()
            appendLine("== Recent app exits (from Android)")
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrDefault(emptyList())
            if (exits.isEmpty()) appendLine("None reported")
            exits.forEach { e ->
                appendLine("${fmt.format(Instant.ofEpochMilli(e.timestamp))} ${reason(e.reason)}" + (e.description?.let { " — " + m(it) } ?: ""))
            }
        }
    }

    private fun reason(r: Int): String = if (Build.VERSION.SDK_INT < 30) r.toString() else when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by the system"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        else -> "other ($r)"
    }

    private companion object {
        const val KEY = "errors"
        const val MAX = 30
    }
}
