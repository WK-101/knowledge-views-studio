package app.parley.data.people

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.RawContacts
import app.parley.common.people.ParleyWrite
import app.parley.common.people.Provenance
import app.parley.common.people.ProvenanceVerdict
import app.parley.common.people.RawState
import app.parley.data.AccountRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Parley itself wrote, per raw contact: when, the raw contact's VERSION right after, and which fields.
 * Kept on this phone only (no contact data, just ids, times and field names), last [MAX] saves.
 */
class ParleyWriteLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parley_writes", Context.MODE_PRIVATE)

    data class Entry(val rawId: Long, val lookupKey: String, val write: ParleyWrite)

    @Synchronized
    fun record(rawId: Long, lookupKey: String, versionAfter: Long, fields: List<String>, time: Long = System.currentTimeMillis()) {
        val arr = load()
        arr += Entry(rawId, lookupKey, ParleyWrite(rawId, time, versionAfter, fields))
        val kept = arr.takeLast(MAX)
        val json = JSONArray()
        kept.forEach { e ->
            json.put(JSONObject().put("r", e.rawId).put("k", e.lookupKey).put("t", e.write.time).put("v", e.write.versionAfter).put("f", JSONArray(e.write.fields)))
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun load(): MutableList<Entry> {
        val raw = prefs.getString(KEY, null) ?: return ArrayList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapTo(ArrayList()) { i ->
                val o = a.getJSONObject(i)
                val f = o.optJSONArray("f") ?: JSONArray()
                Entry(o.getLong("r"), o.optString("k"), ParleyWrite(o.getLong("r"), o.getLong("t"), o.getLong("v"), (0 until f.length()).map { f.getString(it) }))
            }
        }.getOrElse { ArrayList() }
    }

    fun forRaws(rawIds: Collection<Long>): List<ParleyWrite> = load().filter { it.rawId in rawIds }.map { it.write }

    /** Parley's save of [lookupKey] closest after [time] (journal entries are written just before the save). */
    fun near(lookupKey: String, time: Long, windowMs: Long = 60_000L): ParleyWrite? =
        load().filter { it.lookupKey == lookupKey && it.write.time >= time && it.write.time - time < windowMs }.minByOrNull { it.write.time }?.write

    /** Reads RawContacts.VERSION (after a save). */
    fun version(cr: ContentResolver, rawId: Long): Long? = try {
        cr.query(android.content.ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.VERSION), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEY = "writes"
        const val MAX = 500
    }
}

/** Builds the "Why did this change?" line for the contact page. */
class ProvenanceReader(private val context: Context, private val log: ParleyWriteLog) {
    private val cr = context.contentResolver

    suspend fun verdict(contactId: Long): ProvenanceVerdict? = withContext(Dispatchers.IO) {
        val synced = try {
            ContentResolver.getSyncAdapterTypes().filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }.map { it.accountType }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val raws = ArrayList<RawState>()
        try {
            cr.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.VERSION, RawContacts.DIRTY),
                "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(1)
                    if (app.parley.common.record.Messengers.isMessengerAccount(type)) continue
                    raws += RawState(c.getLong(0), AccountRef(type, c.getString(2)).displayLabel, type != null && type in synced, c.getLong(3), c.getInt(4) != 0)
                }
            }
        } catch (_: Exception) {
        }
        val updated = try {
            cr.query(android.content.ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), arrayOf(Contacts.CONTACT_LAST_UPDATED_TIMESTAMP), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) {
            null
        }
        Provenance.verdict(raws, log.forRaws(raws.map { it.rawId }), updated)
    }
}
