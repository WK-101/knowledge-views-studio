package app.parley.data

import app.parley.common.PhoneIdentity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.PhoneLookup
import android.provider.ContactsContract.RawContacts
import app.parley.common.MessengerMimes
import app.parley.common.MessengerRowMatch
import app.parley.common.PhoneNumbers
import app.parley.common.ReachApp
import app.parley.common.ReachKind
import app.parley.common.ReachRow

/** One action a messenger app registered on a contact (e.g. "Signal Voice Call +1 555…"). */
data class MessengerAction(
    val dataId: Long,
    val mimeType: String,
    val accountType: String,
    val appName: String,
    val label: String,
    /** A call of any kind (voice or video), as before V34. */
    val isCall: Boolean,
    val isVideo: Boolean,
    /** What the row does, from its mimetype. */
    val kind: ReachKind = if (isVideo) ReachKind.VIDEO else if (isCall) ReachKind.VOICE else ReachKind.MESSAGE,
    /** The number the row is for, when the app said. */
    val number: String? = null,
    val app: ReachApp? = null,
    /** The installed app to send the intent to (Signal and Molly share mimetypes); null lets Android pick. */
    val packageName: String? = null,
) {
    val row: ReachRow get() = ReachRow(dataId, mimeType, accountType, app, appName, kind, number, label)

    /** What Google Contacts does for a third-party row: VIEW on the row's Data URI with its mimetype. */
    fun intent(): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(ContentUris.withAppendedId(Data.CONTENT_URI, dataId), mimeType)
        .apply { packageName?.let { setPackage(it) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Reads the rows WhatsApp, Signal, Telegram, Threema… add to contacts, so Parley can offer "Call on…/Message on…"
 * without network access and without listing installed apps.
 *
 * Rows are recognised by mimetype ([MessengerMimes]), and also found when the app's raw contact didn't join the
 * person's contact. That was why Signal went missing: WhatsApp's raw contacts are merged by the usual name and
 * number matching, but Signal pins its raw contact to one sibling raw contact with an aggregation exception when it
 * syncs, and copies that raw contact's name. When that sibling is later replaced (moved to another account, edited
 * into a new raw contact, re-synced by Google) the exception is gone and Signal's raw contact becomes a separate,
 * Signal-only contact, which Parley hides; reading only the opened contact's rows then found nothing. Messenger-only
 * contacts that share one of the person's numbers are now read too.
 */
object Messengers {
    fun actions(context: Context, contactId: Long): List<MessengerAction> = actionsAndPhones(context, contactId).first

    /** The contact's messenger rows, and its own phone numbers. */
    private fun actionsAndPhones(context: Context, contactId: Long): Pair<List<MessengerAction>, List<String>> {
        val cr = context.contentResolver
        val own = readRows(context, listOf(contactId))
        val ownPhones = own.phones
        val region = PhoneEnv.countryIso(context)

        // Messenger-only contacts on the same numbers (an app's raw contact that didn't join this person).
        val others = LinkedHashSet<Long>()
        for (p in ownPhones.distinctBy { PhoneIdentity.key(it, region) }) {
            cr.safeQuery(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(p)), arrayOf(PhoneLookup._ID))?.use { c ->
                while (c.moveToNext()) c.getLong(0).takeIf { it != contactId }?.let { others += it }
            }
        }
        val messengerOnly = others.filter { id -> onlyMessengerRaws(context, id) }
        // Exact number match only, and only rows that carry a number.
        val extra = if (messengerOnly.isEmpty()) emptyList() else readRows(context, messengerOnly).actions.filter { a ->
            MessengerRowMatch.extraRow(a.number, ownPhones, region)
        }
        val all = (own.actions + extra).distinctBy { it.dataId }
            .sortedWith(compareBy({ it.app?.ordinal ?: Int.MAX_VALUE }, { it.appName }, { it.kind.ordinal }))
        return all to ownPhones
    }

    /** The messenger rows for [number] (a saved contact's, or a temporary visible contact's once apps synced). */
    fun actionsForNumber(context: Context, number: String): List<MessengerAction> {
        if (number.isBlank()) return emptyList()
        val ids = LinkedHashSet<Long>()
        context.contentResolver.safeQuery(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(PhoneLookup._ID))?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        val region = PhoneEnv.countryIso(context)
        // Rows without a number only from a contact that itself has this exact number.
        return ids.flatMap { id ->
            val (actions, phones) = actionsAndPhones(context, id)
            actions.filter { a -> MessengerRowMatch.forNumber(a.number, phones, number, region) }
        }.distinctBy { it.dataId }
    }

    private class Rows(val actions: List<MessengerAction>, val phones: List<String>)

    private fun readRows(context: Context, contactIds: List<Long>): Rows {
        val region = PhoneEnv.countryIso(context)
        if (contactIds.isEmpty()) return Rows(emptyList(), emptyList())
        class Raw(val id: Long, val mime: String, val type: String?, val rawId: Long, val d1: String?, val d2: String?, val d3: String?)
        val raws = ArrayList<Raw>()
        context.contentResolver.safeQuery(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.MIMETYPE, RawContacts.ACCOUNT_TYPE, Data.RAW_CONTACT_ID, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.CONTACT_ID} IN (${contactIds.joinToString(",")})",
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                raws += Raw(c.getLong(0), c.getString(1) ?: continue, c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6))
            }
        }
        val phonesByRaw = raws.filter { it.mime == Phone.CONTENT_ITEM_TYPE && !it.d1.isNullOrBlank() }.groupBy({ it.rawId }, { it.d1!! })
        // The person's own numbers: from their own raw contacts, not the apps' copies.
        val ownPhones = raws.filter { it.mime == Phone.CONTENT_ITEM_TYPE && !it.d1.isNullOrBlank() && !app.parley.common.record.Messengers.isMessengerAccount(it.type) }
            .map { it.d1!! }
        val installed = HashMap<String, Boolean>()
        fun installedPkg(type: String): String? = type.takeIf { t ->
            installed.getOrPut(t) {
                try {
                    context.packageManager.getApplicationInfo(t, 0).enabled
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
        }
        val actions = raws.mapNotNull { r ->
            val single = phonesByRaw[r.rawId]?.distinctBy { PhoneIdentity.key(it, region) }?.singleOrNull()
            val row = MessengerMimes.classify(r.id, r.mime, r.type, r.d1, r.d2, r.d3, single) ?: return@mapNotNull null
            MessengerAction(
                dataId = row.dataId, mimeType = row.mimeType, accountType = row.appKey, appName = row.appLabel, label = row.label,
                isCall = row.kind == ReachKind.VOICE || row.kind == ReachKind.VIDEO,
                isVideo = row.kind == ReachKind.VIDEO,
                kind = row.kind, number = row.number, app = row.app, packageName = installedPkg(row.appKey),
            )
        }
        return Rows(actions, ownPhones.ifEmpty { phonesByRaw.values.flatten() })
    }

    /** Whether every raw contact of [contactId] belongs to a messenger (a contact only an app made). */
    private fun onlyMessengerRaws(context: Context, contactId: Long): Boolean {
        var any = false
        context.contentResolver.safeQuery(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE), "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()))?.use { c ->
            while (c.moveToNext()) {
                val t = c.getString(0)
                if (!app.parley.common.record.Messengers.isMessengerAccount(t) && ReachApp.forAccountType(t) == null) return false
                any = true
            }
        }
        return any
    }
}
