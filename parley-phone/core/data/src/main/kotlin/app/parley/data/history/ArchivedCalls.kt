package app.parley.data.history

import android.content.ContentValues
import android.provider.CallLog.Calls
import app.parley.common.CallEntry
import app.parley.common.backup.CallLogRecord
import app.parley.common.history.ProviderColumns
import app.parley.data.CallLogRepository
import org.json.JSONObject

/**
 * How [CallHistory] turns a call between its forms: a system call-log row ([CallLogRecord]), a Recents row
 * ([CallEntry]), the values written back to the call log, and the small JSON sealed into the archive.
 */
internal object ArchivedCalls {
    fun entry(r: CallLogRecord, id: Long) = CallEntry(
        id = id,
        number = r.number.orEmpty(),
        cachedName = r.name,
        type = CallLogRepository.mapType(r.type),
        date = r.date,
        durationSec = r.duration,
        accountId = r.accountId,
        isNew = false,
        presentationHidden = r.presentation != Calls.PRESENTATION_ALLOWED || r.number.isNullOrBlank(),
        video = (r.features and Calls.FEATURES_VIDEO) != 0,
    )

    fun record(e: CallEntry) = CallLogRecord(
        number = e.number, date = e.date, duration = e.durationSec, type = ProviderColumns.typeOf(e.type),
        presentation = if (e.presentationHidden) Calls.PRESENTATION_RESTRICTED else Calls.PRESENTATION_ALLOWED,
        accountId = e.accountId, name = e.cachedName, isNew = false, isRead = true, features = if (e.video) Calls.FEATURES_VIDEO else 0,
    )

    fun values(r: CallLogRecord) = ContentValues().apply {
        put(Calls.NUMBER, r.number)
        put(Calls.DATE, r.date)
        put(Calls.DURATION, r.duration)
        put(Calls.TYPE, r.type)
        put(Calls.NUMBER_PRESENTATION, r.presentation)
        put(Calls.PHONE_ACCOUNT_ID, r.accountId)
        put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, r.accountComponent)
        put(Calls.CACHED_NAME, r.name)
        put(Calls.FEATURES, r.features)
        // Restored history is not news: no badge, no notification.
        put(Calls.NEW, 0)
        put(Calls.IS_READ, 1)
    }

    fun encode(r: CallLogRecord): String = JSONObject()
        .put("n", r.number ?: JSONObject.NULL).put("d", r.date).put("s", r.duration).put("t", r.type).put("p", r.presentation)
        .put("a", r.accountId ?: JSONObject.NULL).put("c", r.accountComponent ?: JSONObject.NULL).put("m", r.name ?: JSONObject.NULL)
        .apply { if (r.features != 0) put("f", r.features) }
        .toString()

    fun decode(s: String): CallLogRecord {
        val o = JSONObject(s)
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
        return CallLogRecord(
            str("n"), o.getLong("d"), o.optLong("s"), o.optInt("t"), o.optInt("p", 1), str("a"), str("c"), str("m"),
            isNew = false, isRead = true, features = o.optInt("f"),
        )
    }
}
