package app.parley.data

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import app.parley.common.backup.CallLogRecord
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

    private val _preview = MutableStateFlow<List<CallEntry>?>(null)

    @Volatile
    private var fullLoaded = false

    /**
     * The newest [PREVIEW_ROWS] calls, read first so Recents can show them while the full log loads. Only filled
     * before the first full load; use [calls] for anything that needs the whole log.
     */
    val preview: StateFlow<List<CallEntry>?> = _preview

    // A refresh after READ_CALL_LOG is granted also registers the observer, so Recents update live.
    val calls: StateFlow<List<CallEntry>?> = combine(cr.changes(Calls.CONTENT_URI, retry = reload), reload) { _, _ -> }
        .map {
            // Two-stage load: a quick first page on the first load only, then everything.
            if (_preview.value == null && !fullLoaded) _preview.value = load(PREVIEW_ROWS)
            load().also { fullLoaded = true }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun refresh() {
        reload.value++
    }

    private fun load(limit: Int = 3000): List<CallEntry> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        return query(Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString()).build(), null, null)
    }

    private fun query(uri: android.net.Uri, selection: String?, args: Array<String>?): List<CallEntry> {
        val out = ArrayList<CallEntry>()
        cr.safeQuery(
            uri,
            arrayOf(Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.PHONE_ACCOUNT_ID, Calls.NEW, Calls.NUMBER_PRESENTATION),
            selection, args,
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

    /**
     * Every system call-log row for [number] since [since], read from the provider rather than the newest-3000
     * window [calls] shows, so destructive actions reach old calls too. The filter URI matches loosely (trailing
     * digits); only rows that are exactly this line are returned ([PhoneNumbers.sameExact]): a delete built from this
     * list must never reach another number that merely ends the same way.
     */
    fun queryForNumber(number: String, since: Long = Long.MIN_VALUE): List<CallEntry> {
        if (number.isBlank() || !Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        val iso = PhoneEnv.countryIso(context)
        val uri = android.net.Uri.withAppendedPath(Calls.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        val bounded = since != Long.MIN_VALUE
        return query(uri, if (bounded) "${Calls.DATE} >= ?" else null, if (bounded) arrayOf(since.toString()) else null)
            .filter { !it.presentationHidden && PhoneNumbers.sameExact(it.number, number, iso) }
    }

    /**
     * Earlier calls with [number] (or either line of a forwarded "A&B" number) before [before], newest first, read
     * straight from the provider: the screening path can't wait for [calls] to load in a process just started for
     * the call.
     */
    fun pastCalls(number: String, before: Long, limit: Int = 50): List<CallEntry> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<CallEntry>()
        for (part in PhoneNumbers.forwardedParts(number)) {
            val uri = android.net.Uri.withAppendedPath(Calls.CONTENT_FILTER_URI, android.net.Uri.encode(part))
                .buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString()).build()
            out += query(uri, "${Calls.DATE} < ?", arrayOf(before.toString()))
        }
        return out.sortedByDescending { it.date }
    }

    /** Unseen missed calls, newest first (what Telecom counts: missed, new and not read). */
    fun unseenMissed(limit: Int = 50): List<CallEntry> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        return query(
            Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString()).build(),
            "${Calls.TYPE} = ? AND ${Calls.NEW} = 1 AND (${Calls.IS_READ} = 0 OR ${Calls.IS_READ} IS NULL)",
            arrayOf(Calls.MISSED_TYPE.toString()),
        )
    }

    /** Every row of the system call log, oldest first, with the columns a backup keeps. */
    fun exportAll(): Sequence<CallLogRecord> = sequence {
        val c = runCatching {
            cr.query(
                Calls.CONTENT_URI,
                arrayOf(Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.NUMBER_PRESENTATION, Calls.PHONE_ACCOUNT_ID, Calls.PHONE_ACCOUNT_COMPONENT_NAME, Calls.CACHED_NAME, Calls.NEW, Calls.IS_READ),
                null, null, Calls.DATE + " ASC",
            )
        }.getOrNull() ?: return@sequence
        c.use {
            while (it.moveToNext()) {
                yield(CallLogRecord(it.getString(0), it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getString(5), it.getString(6), it.getString(7), it.getInt(8) != 0, it.getInt(9) != 0))
            }
        }
    }

    /** "number|date|duration|type" of every system call-log row, to skip rows a restore would add twice. */
    fun rowSignatures(): Set<String> {
        val existing = HashSet<String>()
        runCatching {
            cr.query(Calls.CONTENT_URI, arrayOf(Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) existing += signature(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3))
            }
        }
        return existing
    }

    /** Adds call-log rows (a restore); returns how many were written. */
    fun insert(records: List<CallLogRecord>): Int = records.chunked(200).sumOf { chunk ->
        val values = chunk.map { rec ->
            ContentValues().apply {
                put(Calls.NUMBER, rec.number)
                put(Calls.DATE, rec.date)
                put(Calls.DURATION, rec.duration)
                put(Calls.TYPE, rec.type)
                put(Calls.NUMBER_PRESENTATION, rec.presentation)
                put(Calls.PHONE_ACCOUNT_ID, rec.accountId)
                put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, rec.accountComponent)
                put(Calls.CACHED_NAME, rec.name)
                put(Calls.NEW, if (rec.isNew) 1 else 0)
                put(Calls.IS_READ, if (rec.isRead) 1 else 0)
            }
        }.toTypedArray()
        runCatching { cr.bulkInsert(Calls.CONTENT_URI, values) }.getOrDefault(0)
    }

    suspend fun delete(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ids.chunked(500).forEach { chunk ->
            cr.delete(Calls.CONTENT_URI, "${Calls._ID} IN (${chunk.joinToString(",")})", null)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { cr.delete(Calls.CONTENT_URI, null, null) }

    suspend fun deleteForNumber(number: String) = withContext(Dispatchers.IO) {
        delete(queryForNumber(number).map { it.id })
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
        const val PREVIEW_ROWS = 100

        fun signature(number: String?, date: Long, duration: Long, type: Int) = "$number|$date|$duration|$type"

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
