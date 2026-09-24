package app.parley.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/** One action a messenger app registered on a contact (e.g. "Signal call +1 555…"). */
data class MessengerAction(
    val dataId: Long,
    val mimeType: String,
    val accountType: String,
    val appName: String,
    val label: String,
    val isCall: Boolean,
    val isVideo: Boolean,
) {
    fun intent(): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(ContentUris.withAppendedId(Data.CONTENT_URI, dataId), mimeType)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Reads the rows WhatsApp, Signal, Telegram, Threema… add to contacts, so Parley can offer
 * "Call with…/Message with…" without network access and without listing installed apps.
 */
object Messengers {
    val KNOWN = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.thoughtcrime.securesms" to "Signal",
        "im.molly.app" to "Molly",
        "org.telegram.messenger" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "ch.threema.app" to "Threema",
        "com.viber.voip" to "Viber",
        "com.wire" to "Wire",
        "im.vector.app" to "Element",
        "com.google.android.apps.tachyon" to "Google Meet",
        "org.briarproject.briar.android" to "Briar",
        "chat.simplex.app" to "SimpleX",
    )

    fun actions(context: Context, contactId: Long): List<MessengerAction> {
        val out = ArrayList<MessengerAction>()
        context.contentResolver.safeQuery(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.MIMETYPE, RawContacts.ACCOUNT_TYPE, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.CONTACT_ID}=? AND ${RawContacts.ACCOUNT_TYPE} IS NOT NULL",
            arrayOf(contactId.toString()),
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                val type = c.getString(2) ?: continue
                if (type !in KNOWN && !mime.contains("vnd.")) continue
                if (!mime.startsWith("vnd.android.cursor.item/vnd.")) continue
                val app = KNOWN[type] ?: continue
                val label = c.getString(5)?.takeIf { it.isNotBlank() } ?: c.getString(4)?.takeIf { it.isNotBlank() } ?: app
                val lower = mime.lowercase() + " " + label.lowercase()
                out += MessengerAction(
                    dataId = c.getLong(0), mimeType = mime, accountType = type, appName = app, label = label,
                    isCall = lower.contains("call") || lower.contains("voip") || lower.contains("audio"),
                    isVideo = lower.contains("video"),
                )
            }
        }
        return out.sortedWith(compareBy({ it.appName }, { !it.isCall && !it.isVideo }, { it.isVideo }))
    }
}
