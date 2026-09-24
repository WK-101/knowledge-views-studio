package app.parley.data

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class CallLogRepository(private val context: Context, scope: CoroutineScope) {
    private val cr = context.contentResolver
    private val reload = MutableStateFlow(0)

    val calls: StateFlow<List<CallEntry>?> = combine(cr.changes(Calls.CONTENT_URI), reload) { _, _ -> }
        .map { load() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun refresh() {
        reload.value++
    }

    private fun load(limit: Int = 3000): List<CallEntry> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<CallEntry>()
        cr.safeQuery(
            Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString()).build(),
            arrayOf(Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.PHONE_ACCOUNT_ID, Calls.NEW, Calls.NUMBER_PRESENTATION),
            sort = Calls.DATE + " DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += CallEntry(
                    id = c.getLong(0),
                    number = c.getString(1).orEmpty(),
                    cachedName = c.getString(2),
                    type = mapType(c.getInt(3)),
                    date = c.getLong(4),
                    durationSec = c.getLong(5),
                    accountId = c.getString(6),
                    isNew = c.getInt(7) != 0,
                    presentationHidden = c.getInt(8) != Calls.PRESENTATION_ALLOWED,
                )
            }
        }
        return out
    }

    suspend fun delete(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ids.chunked(500).forEach { chunk ->
            cr.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { cr.delete(Calls.CONTENT_URI, null, null) }

    suspend fun deleteForNumber(number: String) = withContext(Dispatchers.IO) {
        val ids = calls.value.orEmpty().filter { PhoneNumbers.same(it.number, number, PhoneEnv.countryIso(context)) }.map { it.id }
        delete(ids)
    }

    /** Clears the "new" flag on missed calls so badges and notifications go away. */
    suspend fun markMissedRead() = withContext(Dispatchers.IO) {
        try {
            val v = ContentValues().apply {
                put(Calls.NEW, 0)
                put(Calls.IS_READ, 1)
            }
            cr.update(Calls.CONTENT_URI, v, "${Calls.NEW}=1 AND ${Calls.TYPE}=?", arrayOf(Calls.MISSED_TYPE.toString()))
        } catch (_: Exception) {
        }
    }

    companion object {
        fun mapType(t: Int): CallType = when (t) {
            Calls.INCOMING_TYPE -> CallType.INCOMING
            Calls.OUTGOING_TYPE -> CallType.OUTGOING
            Calls.MISSED_TYPE -> CallType.MISSED
            Calls.REJECTED_TYPE -> CallType.REJECTED
            Calls.BLOCKED_TYPE -> CallType.BLOCKED
            Calls.VOICEMAIL_TYPE -> CallType.VOICEMAIL
            Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.ANSWERED_EXTERNALLY
            else -> CallType.UNKNOWN
        }
    }
}
