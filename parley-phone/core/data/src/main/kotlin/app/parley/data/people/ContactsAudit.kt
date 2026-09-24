package app.parley.data.people

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import app.parley.common.record.Messengers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app that holds the Contacts permission. */
data class ContactsAccessApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    /** Why it plausibly needs contacts ("Messaging"), or null. */
    val note: String?,
)

/**
 * "Who can see your contacts": lists launcher-visible apps that hold READ_CONTACTS. Parley can only list them
 * and link to Android's settings; it can't change another app's permission.
 *
 * Package visibility: the manifest declares a `<queries>` entry for launcher activities (and a few named
 * packages), never QUERY_ALL_PACKAGES, so apps without a launcher icon aren't listed.
 */
class ContactsAudit(private val context: Context) {
    private val pm = context.packageManager

    suspend fun appsWithAccess(): List<ContactsAccessApp> = withContext(Dispatchers.IO) {
        val launcher = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } catch (_: Exception) {
            emptyList()
        }
        launcher.mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .filter { pm.checkPermission(Manifest.permission.READ_CONTACTS, it.packageName) == PackageManager.PERMISSION_GRANTED }
            .map { ai ->
                ContactsAccessApp(
                    packageName = ai.packageName,
                    label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName),
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    note = noteFor(ai.packageName),
                )
            }
            .sortedWith(compareBy<ContactsAccessApp> { it.system }.thenBy { it.label.lowercase() })
    }

    /** GrapheneOS, detected by its own apps (declared in `<queries>`); build properties aren't reliable. */
    fun isGrapheneOs(): Boolean = GRAPHENE_PACKAGES.any { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun noteFor(pkg: String): String? = when {
        pkg in Messengers.PACKAGES || pkg in MESSAGING -> context.getString(app.parley.data.R.string.data_audit_messaging)
        pkg in EMAIL -> context.getString(app.parley.data.R.string.data_audit_email)
        pkg in DIALERS -> context.getString(app.parley.data.R.string.data_audit_phone)
        else -> null
    }

    companion object {
        val GRAPHENE_PACKAGES = listOf("app.grapheneos.apps", "app.grapheneos.info", "app.grapheneos.camera", "app.grapheneos.pdfviewer", "app.grapheneos.gmscompat")
        private val MESSAGING = setOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "org.fossify.messages", "com.simplemobiletools.smsmessenger",
            "org.smssecure.smssecure", "com.android.messaging", "com.textra",
        )
        private val EMAIL = setOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android", "com.fsck.k9", "net.thunderbird.android",
            "eu.faircode.email", "com.samsung.android.email.provider", "com.tutao.tutanota",
        )
        private val DIALERS = setOf("com.google.android.dialer", "com.samsung.android.dialer", "org.fossify.phone", "com.android.dialer")
    }
}
