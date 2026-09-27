package app.parley.data.backup

import android.util.Base64
import androidx.room.withTransaction
import app.parley.common.ContactSummary
import app.parley.common.backup.CallTimeRestore
import app.parley.common.backup.PersonRef
import app.parley.common.backup.PersonRefs
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.CallingJson
import app.parley.common.history.HistoryFilter
import app.parley.common.history.PlanConfig
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryExpiry
import app.parley.common.storage.PersistentStores.Sections
import app.parley.data.SpamListStore
import app.parley.data.calls.CallExtrasRepository
import app.parley.data.calltime.CallingRepository
import app.parley.data.db.AppDatabase
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.TemporaryContactEntity
import app.parley.data.history.HistoryPrefs
import org.json.JSONArray
import org.json.JSONObject

internal fun PersonRef.toJson(): JSONObject = JSONObject().put("k", key).also { o ->
    name?.let { o.put("n", it) }
    if (phones.isNotEmpty()) o.put("p", JSONArray(phones.toList()))
}

internal fun JSONObject.toPersonRef(): PersonRef = PersonRef(
    optString("k"), optString("n").ifEmpty { null },
    optJSONArray("p")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
)

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * The contact notes Parley keeps beside the address book: pinned notes, preferred messengers, relation links and the
 * last nudge (contact_meta outside the Circle's columns), call notes, and which contacts are temporary. People are
 * matched by [PersonRefs] on the other phone; notes never overwrite ones this phone already has.
 */
class ContactNotesBackup(
    private val db: AppDatabase,
    private val contactsNow: suspend () -> List<ContactSummary>,
    private val rawIds: (Long) -> List<Long>,
) : BackupExtras {
    override val section = "contact notes"
    override val sections = setOf(Sections.CONTACT_NOTES)
    override val restoreWith = RestorePart.CONTACTS
    private val meta get() = db.metaDao()

    override suspend fun export(): Map<String, String> {
        val refs = PersonRefs(contactsNow())
        val metas = JSONArray()
        for (m in meta.allMetaNow()) {
            if (m.pinnedNote == null && m.preferredMessenger == null && m.relationLinks == null && m.lastNudgedAt == null) continue
            val o = refs.ref(m.lookupKey).toJson()
            m.pinnedNote?.let { o.put("note", it) }
            m.preferredMessenger?.let { o.put("msg", it) }
            m.lastNudgedAt?.let { o.put("nudged", it) }
            val links = RelationLinks.decode(m.relationLinks)
            if (links.isNotEmpty()) o.put("rel", JSONArray(links.map { (name, l) -> JSONObject().put("name", name).put("to", refs.ref(l.lookupKey).toJson()) }))
            metas.put(o)
        }
        val notes = JSONArray()
        meta.allCallNotesNow().forEach { n -> notes.put(JSONObject().put("k", n.numberKey).put("d", n.callDate).put("t", n.text).put("c", n.createdAt)) }
        val temps = JSONArray()
        meta.allTemporary().forEach { t ->
            val o = refs.ref(t.lookupKey).toJson().put("exp", t.expiresAt).put("purge", t.purgeHistory).put("asked", t.keepAsked)
            t.name?.let { if (!o.has("n")) o.put("n", it) }
            temps.put(o)
        }
        return mapOf(K_META to metas.toString(), K_CALL_NOTES to notes.toString(), K_TEMPORARY to temps.toString())
    }

    override suspend fun import(values: Map<String, String>) {
        importCounting(values)
    }

    override suspend fun importCounting(values: Map<String, String>): Int {
        if (values.keys.none { it == K_META || it == K_CALL_NOTES || it == K_TEMPORARY }) return 0
        val refs = PersonRefs(contactsNow())
        var unmatched = 0
        // People are resolved (and raw ids read from the provider) first; the database writes then run as one transaction.
        val metas = values[K_META]?.let { JSONArray(it).objects() }.orEmpty().mapNotNull { o ->
            val c = refs.resolve(o.toPersonRef()) ?: run { unmatched++; return@mapNotNull null }
            val links = o.optJSONArray("rel")?.objects().orEmpty().mapNotNull { r ->
                val to = r.optJSONObject("to")?.toPersonRef()?.let { refs.resolve(it) } ?: return@mapNotNull null
                r.optString("name").takeIf { it.isNotEmpty() }?.let { it to RelationLinks.Link(to.lookupKey, to.id) }
            }.toMap()
            Triple(c, o, links)
        }
        val temps = values[K_TEMPORARY]?.let { JSONArray(it).objects() }.orEmpty().mapNotNull { o ->
            val c = refs.resolve(o.toPersonRef()) ?: run { unmatched++; return@mapNotNull null }
            TemporaryContactEntity(
                c.lookupKey, c.id, o.optLong("exp"), o.optBoolean("purge", true),
                rawIds = runCatching { TemporaryExpiry.encodeIds(rawIds(c.id)) }.getOrNull()?.ifEmpty { null },
                name = o.optString("n").ifEmpty { c.displayName }, keepAsked = o.optBoolean("asked", false),
            )
        }
        val notes = values[K_CALL_NOTES]?.let { JSONArray(it).objects() }.orEmpty()
        db.withTransaction {
            for ((c, o, links) in metas) {
                val have = meta.meta(c.lookupKey)
                val note = o.optString("note").ifEmpty { null }
                val msg = o.optString("msg").ifEmpty { null }
                val nudged = if (o.has("nudged")) o.optLong("nudged") else null
                // This phone's links win for a relation name both have.
                val merged = links + RelationLinks.decode(have?.relationLinks)
                val encoded = merged.takeIf { it.isNotEmpty() }?.let { RelationLinks.encode(it) }
                if (have == null) {
                    meta.setMeta(ContactMetaEntity(c.lookupKey, pinnedNote = note, preferredMessenger = msg, lastNudgedAt = nudged, contactId = c.id, relationLinks = encoded))
                } else {
                    meta.setPersonalMeta(c.lookupKey, have.pinnedNote ?: note, have.preferredMessenger ?: msg, encoded, nudged.takeIf { have.lastNudgedAt == null })
                }
            }
            for (o in notes) {
                val key = o.optString("k").ifEmpty { continue }
                val date = o.optLong("d")
                val text = o.optString("t").ifEmpty { continue }
                if (meta.countCallNote(key, date, text) == 0) meta.addCallNote(CallNoteEntity(numberKey = key, callDate = date, text = text, createdAt = o.optLong("c", date)))
            }
            // A contact that is already temporary here keeps its own expiry.
            for (t in temps) if (meta.temporary(t.lookupKey) == null) meta.setTemporary(t)
        }
        return unmatched
    }

    private companion object {
        const val K_META = "${BackupExtras.PREFIX}notes.meta"
        const val K_CALL_NOTES = "${BackupExtras.PREFIX}notes.calls"
        const val K_TEMPORARY = "${BackupExtras.PREFIX}notes.temporary"
    }
}

/**
 * Call-time settings (reminders, limits, allowances, supervision) and the call switches (proximity, pocket guard,
 * missed-call re-alert). Supervision is never changed silently (neither lifted nor imposed): the backup's settings wait
 * until the user confirms with the app lock ([ConfirmedRestore]).
 */
class CallTimeBackup(
    private val calling: CallingRepository,
    private val callExtras: CallExtrasRepository,
    private val contactsNow: suspend () -> List<ContactSummary>,
) : BackupExtras, ConfirmedRestore {
    override val section = "call time"
    override val sections = setOf(Sections.CALL_TIME)

    @Volatile private var pending: CallingConfig? = null

    override suspend fun export(): Map<String, String> {
        val config = calling.config.value
        val refs = PersonRefs(contactsNow())
        val people = JSONObject()
        CallTimeRestore.keys(config).forEach { k -> people.put(k, refs.ref(k).toJson()) }
        return mapOf(K_CONFIG to CallingJson.encode(config), K_PEOPLE to people.toString(), K_SWITCHES to CallExtrasConfig.encode(callExtras.config.value))
    }

    override suspend fun import(values: Map<String, String>) {
        importCounting(values)
    }

    override suspend fun importCounting(values: Map<String, String>): Int {
        values[K_SWITCHES]?.let { v -> callExtras.update { CallExtrasConfig.decode(v) } }
        val backup = values[K_CONFIG]?.let { CallingJson.decode(it) } ?: return 0
        val people = values[K_PEOPLE]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val refs = PersonRefs(contactsNow())
        val mapped = CallTimeRestore.remap(backup) { k -> refs.resolve(people.optJSONObject(k)?.toPersonRef() ?: PersonRef(k))?.lookupKey }
        // Supervision is a safeguard either way: switching it off, or on, from a file waits for the user's confirmation.
        if (calling.config.value.supervised || mapped.config.supervised) pending = mapped.config else apply(mapped.config)
        return mapped.unmatched
    }

    private fun apply(config: CallingConfig) = calling.update { config.copy(healthBannerDismissed = it.healthBannerDismissed) }

    override fun hasPending(): Boolean = pending != null

    override suspend fun applyPending(): Boolean {
        val p = pending ?: return false
        pending = null
        apply(p)
        return true
    }

    override fun discardPending() {
        pending = null
    }

    private companion object {
        const val K_CONFIG = "${BackupExtras.PREFIX}calltime.config"
        const val K_PEOPLE = "${BackupExtras.PREFIX}calltime.people"
        const val K_SWITCHES = "${BackupExtras.PREFIX}calltime.switches"
    }
}

/** Call-history settings: keeping the full history, saved filters, CSV byte-order mark and plan minutes per SIM. */
class HistorySettingsBackup(private val prefs: () -> HistoryPrefs) : BackupExtras {
    override val section = "call history settings"
    override val sections = setOf(Sections.HISTORY_SETTINGS)

    override suspend fun export(): Map<String, String> {
        val s = prefs().current()
        val o = JSONObject().put("archive", s.archiveEnabled).put("bom", s.csvBom)
            .put("filters", HistoryFilter.encodeList(s.savedFilters)).put("plans", PlanConfig.encodeList(s.plans))
        return mapOf(K to o.toString())
    }

    override suspend fun import(values: Map<String, String>) {
        val o = values[K]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val p = prefs()
        if (o.has("archive")) p.setArchiveEnabled(o.optBoolean("archive", true))
        if (o.has("bom")) p.setCsvBom(o.optBoolean("bom", true))
        // Saved filters are added to this phone's; plans replace this phone's plan for the same SIM.
        val have = p.current()
        val filters = HistoryFilter.decodeList(o.optString("filters"))
        if (filters.isNotEmpty()) p.setSavedFilters((have.savedFilters + filters).distinct())
        PlanConfig.decodeList(o.optString("plans")).forEach { p.setPlan(it) }
    }

    private companion object {
        const val K = "${BackupExtras.PREFIX}history.settings"
    }
}

/** Spam lists the user added from a file, with their choices (other lists come back from their source). */
class SpamListsBackup(private val lists: () -> SpamListStore) : BackupExtras {
    override val section = "spam lists"
    override val sections = setOf(Sections.SPAM_LISTS)
    override val restoreWith = RestorePart.BLOCKING

    override suspend fun export(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        lists().userPacks(MAX_BYTES).forEachIndexed { i, (state, zip) ->
            out["$PREFIX$i"] = JSONObject().put("state", state).put("pack", Base64.encodeToString(zip, Base64.NO_WRAP)).toString()
        }
        return out
    }

    override suspend fun import(values: Map<String, String>) {
        for ((k, v) in values) {
            if (!k.startsWith(PREFIX)) continue
            val o = runCatching { JSONObject(v) }.getOrNull() ?: continue
            val zip = runCatching { Base64.decode(o.getString("pack"), Base64.NO_WRAP) }.getOrNull() ?: continue
            runCatching { lists().restoreUserPack(o.optString("state"), zip) }
        }
    }

    private companion object {
        const val PREFIX = "${BackupExtras.PREFIX}lists."
        /** User packs are small hand-made lists; a huge one stays out rather than bloat every backup. */
        const val MAX_BYTES = 4 shl 20
    }
}
