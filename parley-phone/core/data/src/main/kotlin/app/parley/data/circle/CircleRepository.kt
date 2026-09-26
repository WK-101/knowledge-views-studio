package app.parley.data.circle

import android.content.Context
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Interactions
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.LastContact
import app.parley.common.circle.LogMode
import app.parley.common.circle.NaturalRhythm
import app.parley.common.circle.PeopleInsights
import app.parley.common.circle.Promises
import app.parley.common.circle.YearlyEvents
import app.parley.common.history.Period
import app.parley.common.history.CallLogIndex
import app.parley.data.backup.BackupExtras
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.MetaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * R1–R5: the Circle's data layer. The Circle is a *view* over system contacts: a contact is in it when its
 * contact_meta row has a keep-in-touch rhythm (`reachOutDays`, plus [KeepRhythm] in `rhythm`). Interactions live in
 * [interactions]; calls always come from the call log (through the call-history index).
 *
 * Read APIs for later items (insights, widget, memory prompt): [interactions] (`interactionsFor(lookupKey)`),
 * [lastContact], [lastAnsweredCall], [members].
 */
class CircleRepository(
    context: Context,
    private val meta: MetaDao,
    val interactions: InteractionStore,
    private val index: () -> StateFlow<CallLogIndex?>,
    private val contactsFlow: () -> StateFlow<List<app.parley.common.ContactSummary>?>,
    /** The contact list read now (not the possibly stale [contactsFlow] snapshot); null when unavailable. */
    private val freshContacts: suspend () -> List<app.parley.common.ContactSummary>? = { null },
    /** Read-modify-write of contact_meta rows runs in one transaction here, so no newer edit is overwritten. */
    private val db: androidx.room.RoomDatabase? = null,
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private suspend fun <T> tx(block: suspend () -> T): T = db?.withTransaction(block) ?: block()

    /** Changes [lookupKey]'s contact_meta row as it is now (created when missing), atomically. */
    private suspend fun editMeta(lookupKey: String, create: Boolean, transform: (ContactMetaEntity) -> ContactMetaEntity?) = withContext(Dispatchers.IO) {
        tx {
            val m = meta.meta(lookupKey) ?: if (create) ContactMetaEntity(lookupKey) else null
            m?.let(transform)?.let { if (it != m) meta.setMeta(it) }
        }
    }

    private val _config = MutableStateFlow(CircleConfig.decode(prefs.getString(K_CONFIG, null)))
    val config: StateFlow<CircleConfig> = _config.asStateFlow()

    @Synchronized
    fun updateConfig(transform: (CircleConfig) -> CircleConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(K_CONFIG, CircleConfig.encode(next)).apply()
    }

    // --- Membership ---

    /** A Circle member: a contact_meta row with a rhythm. */
    data class Member(val meta: ContactMetaEntity, val rhythm: KeepRhythm) {
        val lookupKey: String get() = meta.lookupKey
        val everyDays: Int get() = meta.reachOutDays ?: 30
        val days: Int get() = rhythm.days(everyDays)
    }

    suspend fun members(): List<Member> = withContext(Dispatchers.IO) {
        meta.allMetaNow().filter { it.reachOutDays != null }.map { Member(it, KeepRhythm.decode(it.rhythm)) }
    }

    suspend fun isMember(lookupKey: String): Boolean = lookupKey.isNotEmpty() && withContext(Dispatchers.IO) { meta.meta(lookupKey)?.reachOutDays != null }

    /** Adds [lookupKey] to the Circle (or changes its rhythm). [natural]: learn the gap from history. */
    suspend fun setRhythm(lookupKey: String, contactId: Long?, everyDays: Int?, natural: Boolean = false) = withContext(Dispatchers.IO) {
        // History first (it may wait for the call-log index), then the row as it is now.
        val times = if (everyDays != null && natural) contactTimes(lookupKey) else emptyList()
        editMeta(lookupKey, create = true) { m ->
            val old = KeepRhythm.decode(m.rhythm)
            val rhythm = when {
                everyDays == null -> KeepRhythm()
                natural -> NaturalRhythm.relearn(old.copy(mode = app.parley.common.circle.RhythmMode.NATURAL, learnedAt = null, snoozedUntil = null), times, System.currentTimeMillis(), ZoneId.systemDefault())
                else -> KeepRhythm()
            }
            m.copy(contactId = contactId ?: m.contactId, reachOutDays = everyDays, lastNudgedAt = null, rhythm = rhythm.encode())
        }
    }

    /**
     * Undo of "remove from Circle": puts back the membership of [before] (rhythm, gap, last reminder) on the row as it
     * is now, so a note edited meanwhile stays.
     */
    suspend fun restoreMembership(before: ContactMetaEntity) {
        editMeta(before.lookupKey, create = true) { m -> m.copy(contactId = m.contactId ?: before.contactId, reachOutDays = before.reachOutDays, lastNudgedAt = before.lastNudgedAt, rhythm = before.rhythm) }
    }

    /** R4 "Not now": the next reminder about [lookupKey] waits one more full gap. */
    suspend fun snooze(lookupKey: String, now: Long = System.currentTimeMillis()) {
        editMeta(lookupKey, create = false) { m ->
            val r = KeepRhythm.decode(m.rhythm)
            val days = r.days(m.reachOutDays ?: return@editMeta null)
            m.copy(rhythm = r.copy(snoozedUntil = CirclePlanner.snooze(now, days)).encode())
        }
    }

    /** Monthly re-learning of natural rhythms (R4); returns the members, updated. */
    suspend fun relearnDue(now: Long = System.currentTimeMillis()): List<Member> = withContext(Dispatchers.IO) {
        members().map { mem ->
            if (!mem.rhythm.needsRelearn(now)) return@map mem
            val times = contactTimes(mem.lookupKey)
            // Relearn on the row as it is now (the history read may have waited for the index): only the rhythm
            // changes, so a note edit, a snooze or a re-key made meanwhile isn't overwritten.
            tx {
                val row = meta.meta(mem.lookupKey)?.takeIf { it.reachOutDays != null } ?: return@tx mem
                val current = KeepRhythm.decode(row.rhythm)
                if (!current.needsRelearn(now)) return@tx Member(row, current)
                val next = NaturalRhythm.relearn(current, times, now, ZoneId.systemDefault())
                val updated = row.copy(rhythm = next.encode())
                meta.setMeta(updated)
                Member(updated, next)
            }
        }
    }

    // --- Last contact (calls from the call log + interactions) ---

    private fun personKey(lookupKey: String) = "c:$lookupKey"

    /** Newest answered call with [lookupKey] (incoming or outgoing, longer than zero seconds), from the call log. */
    fun lastAnsweredCall(lookupKey: String, idx: CallLogIndex? = index().value): Long? =
        idx?.calls(personKey = personKey(lookupKey))?.firstOrNull { it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }?.date

    /** G6: the latest time you were in touch: an answered call or any logged interaction. */
    suspend fun lastContact(lookupKey: String, idx: CallLogIndex? = index().value): LastContact? {
        return Interactions.lastContact(lastAnsweredCall(lookupKey, idx), interactions.latestFor(lookupKey))
    }

    /** Days you were in touch (answered calls and interactions), for the natural rhythm. */
    suspend fun contactTimes(lookupKey: String): List<Long> {
        val idx = index().value ?: withTimeoutOrNull(10_000) { index().filterNotNull().first() }
        val calls = idx?.calls(personKey = personKey(lookupKey))?.filter { it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }?.map { it.date }.orEmpty()
        return calls + interactions.timesFor(lookupKey)
    }

    /**
     * X6: the last time you were in touch with every contact you ever were (answered calls with contacts and logged
     * interactions), by lookup key. Private contacts aren't contacts, so they never appear.
     */
    suspend fun lastContactsAll(idx: CallLogIndex? = index().value): Map<String, Long> {
        val out = HashMap<String, Long>()
        idx?.calls?.forEach { c ->
            if (c.durationSec <= 0 || (c.type != CallType.INCOMING && c.type != CallType.OUTGOING)) return@forEach
            val key = c.personKey.takeIf { it.startsWith("c:") }?.removePrefix("c:")?.takeIf { it.isNotEmpty() } ?: return@forEach
            if ((out[key] ?: Long.MIN_VALUE) < c.date) out[key] = c.date
        }
        runCatching { interactions.touchesSince(0) }.getOrDefault(emptyList()).forEach { t ->
            if ((out[t.lookupKey] ?: Long.MIN_VALUE) < t.time) out[t.lookupKey] = t.time
        }
        return out
    }

    // --- R3: "Log this?" ---

    /**
     * A launch Parley just made for a Circle contact. [loggedId]: "Always" already recorded it as this row (offer
     * Undo of exactly that row).
     */
    data class LogPrompt(val lookupKey: String, val contactId: Long?, val name: String, val channel: InteractionChannel, val time: Long, val dedupeKey: String, val loggedId: Long? = null) {
        val autoLogged: Boolean get() = loggedId != null
    }

    private val _prompt = MutableStateFlow<LogPrompt?>(null)

    /** Shown by the app when it's back in front (so the question waits while the user is in the other app). */
    val prompt: StateFlow<LogPrompt?> = _prompt.asStateFlow()

    fun clearPrompt(p: LogPrompt) {
        _prompt.compareAndSet(p, null)
    }

    /**
     * Parley opened [channel] for [lookupKey]. Only Circle contacts are asked about; the channel's [LogMode] decides:
     * never, ask on return, or log now and offer Undo. Returns quietly on any failure (it's a convenience).
     */
    suspend fun onLaunched(lookupKey: String?, contactId: Long?, name: String, channel: InteractionChannel, now: Long = System.currentTimeMillis()) {
        if (lookupKey.isNullOrEmpty() || !isMember(lookupKey)) return
        val mode = _config.value.logMode(channel)
        if (mode == LogMode.NEVER) return
        val key = Interactions.promptKey(channel, lookupKey, now)
        val logged = if (mode == LogMode.ALWAYS) runCatching { interactions.log(lookupKey, contactId, channel.type, channel, now, null, key) }.getOrNull() else null
        if (mode == LogMode.ALWAYS && logged == null) return
        _prompt.value = LogPrompt(lookupKey, contactId, name, channel, now, key, logged)
    }

    /**
     * "Log" on the question: records it and returns the new row's id, or null when nothing new was recorded (a second
     * tap in the same 10 minutes), so Undo is only offered for, and only removes, the entry this tap added.
     */
    suspend fun accept(p: LogPrompt): Long? {
        clearPrompt(p)
        return runCatching { interactions.log(p.lookupKey, p.contactId, p.channel.type, p.channel, p.time, null, p.dedupeKey) }.getOrNull()
    }

    // --- R4/R5 bookkeeping for the reminders worker ---

    fun stateString(key: String): String? = prefs.getString("s.$key", null)
    fun setStateString(key: String, value: String?) = prefs.edit().apply { if (value == null) remove("s.$key") else putString("s.$key", value) }.apply()
    fun stateSet(key: String): Set<String> = prefs.getStringSet("s.$key", emptySet()).orEmpty().toSet()
    fun setStateSet(key: String, value: Set<String>) = prefs.edit().putStringSet("s.$key", value).apply()

    /** R5 "Mark as wished": records an interaction for the occasion (once) and closes it. */
    suspend fun markWished(lookupKey: String, contactId: Long?, occurrence: String, now: Long = System.currentTimeMillis()): Boolean {
        setStateSet(S_WISHED, app.parley.common.circle.DateReminders.prune(stateSet(S_WISHED), now) + "$occurrence|$now")
        if (lookupKey.isEmpty()) return false
        return runCatching { interactions.log(lookupKey, contactId, InteractionType.MESSAGE, null, now, null, Interactions.wishedKey(occurrence)) != null }.getOrDefault(false)
    }

    fun isWished(occurrence: String): Boolean = app.parley.common.circle.DateReminders.has(stateSet(S_WISHED), occurrence)

    // --- R8/R9: notes and promises about a person ---

    /** Where a note lives: the pinned note, a call note or a logged interaction's note. */
    enum class NoteSource { PINNED, CALL, LOGGED }

    /** One note about a person ([time] 0 for the pinned note, which has no date). */
    data class PersonNote(val source: NoteSource, val id: Long, val time: Long, val text: String) {
        val promises: List<Promises.Item> get() = Promises.parse(text)
    }

    /**
     * Every note about [lookupKey]: its pinned note, the call notes of its numbers ([numberKeys], match keys) and
     * the notes of logged interactions (opened here). Newest first, the pinned note last.
     */
    suspend fun notesFor(lookupKey: String, numberKeys: Collection<String>, limit: Int = 60): List<PersonNote> = withContext(Dispatchers.IO) {
        if (lookupKey.isEmpty()) return@withContext emptyList()
        val calls = runCatching { if (numberKeys.isEmpty()) emptyList() else meta.callNotesNow(numberKeys.distinct()) }.getOrDefault(emptyList())
            .take(limit).map { PersonNote(NoteSource.CALL, it.id, it.callDate, it.text) }
        val logged = runCatching { interactions.interactionsFor(lookupKey) }.getOrDefault(emptyList())
            .take(limit).mapNotNull { i -> i.note?.let { PersonNote(NoteSource.LOGGED, i.id, i.time, it) } }
        val pinned = meta.meta(lookupKey)?.pinnedNote?.takeIf { it.isNotBlank() }?.let { PersonNote(NoteSource.PINNED, 0, 0, it) }
        (calls + logged).sortedByDescending { it.time } + listOfNotNull(pinned)
    }

    /** R9: ticks a promise off (or back on) by rewriting its line in the note it lives in. */
    suspend fun setPromiseDone(lookupKey: String, note: PersonNote, line: Int, done: Boolean): Boolean = withContext(Dispatchers.IO) {
        // [note] is what was shown; the note may have been edited since: re-read it and change only that promise's
        // box in the current text (in one transaction, so nothing written meanwhile is reverted).
        runCatching {
            tx {
                val current = when (note.source) {
                    NoteSource.CALL -> meta.callNote(note.id)?.text
                    NoteSource.LOGGED -> interactions.noteOf(note.id)
                    NoteSource.PINNED -> meta.meta(lookupKey)?.pinnedNote
                } ?: return@tx false
                val next = Promises.setDoneFresh(current, note.text, line, done)
                if (next == current) return@tx false
                when (note.source) {
                    NoteSource.CALL -> meta.setCallNoteText(note.id, next)
                    NoteSource.LOGGED -> interactions.setNote(note.id, next)
                    NoteSource.PINNED -> meta.meta(lookupKey)?.let { meta.setMeta(it.copy(pinnedNote = next)) }
                }
                true
            }
        }.getOrDefault(false)
    }

    // --- R10: life events remembered yearly ---

    suspend fun setYearly(lookupKey: String, contactId: Long?, key: String, on: Boolean) {
        if (lookupKey.isEmpty()) return
        editMeta(lookupKey, create = true) { m -> m.copy(contactId = contactId ?: m.contactId, yearlyEvents = YearlyEvents.toggle(m.yearlyEvents, key, on)) }
    }

    /** Lookup key -> flagged event keys. */
    suspend fun yearlyFlags(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        meta.allMetaNow().mapNotNull { r -> YearlyEvents.decode(r.yearlyEvents).takeIf { it.isNotEmpty() }?.let { r.lookupKey to it } }.toMap()
    }

    // --- R6: history for the People card ---

    /**
     * Every contact entry since [since] as [PeopleInsights.Touch]es keyed by lookup key: calls with contacts from
     * the call log (private contacts' calls aren't there, and their interactions travel sealed in the vault entry) and
     * logged interactions ("Mark as wished" entries as [PeopleInsights.TouchKind.WISHED]).
     */
    suspend fun touches(since: Long, idx: CallLogIndex? = index().value): List<PeopleInsights.Touch> {
        val calls = idx?.calls(Period(since, Long.MAX_VALUE)).orEmpty().mapNotNull { c ->
            val key = c.personKey.takeIf { it.startsWith("c:") }?.removePrefix("c:")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PeopleInsights.touchOf(key, c.call)
        }
        val logged = runCatching { interactions.touchesSince(since) }.getOrDefault(emptyList()).map {
            PeopleInsights.Touch(it.lookupKey, it.time, if (it.dedupeKey.startsWith("w:")) PeopleInsights.TouchKind.WISHED else PeopleInsights.TouchKind.LOGGED)
        }
        return calls + logged
    }

    // --- Backup (inside the encrypted backup's settings section) ---

    val backupExtras: BackupExtras = object : BackupExtras {
        /** Contacts read fresh (a restore has just inserted some), else the snapshot. */
        private suspend fun contactsNow(): List<app.parley.common.ContactSummary> =
            runCatching { freshContacts() }.getOrNull() ?: withTimeoutOrNull(30_000) { contactsFlow().filterNotNull().first() }.orEmpty()

        override suspend fun export(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            out[X_CONFIG] = CircleConfig.encode(_config.value)
            val contacts = contactsNow().associateBy { it.lookupKey }
            // Entries whose key isn't a current contact (not re-keyed yet, or the list couldn't be read) still go in,
            // with the key alone: a restore matches what it can and reports the rest.
            fun person(key: String): JSONObject {
                val o = JSONObject().put("k", key)
                val c = contacts[key] ?: return o
                return o.put("n", c.displayName).put("p", JSONArray(c.phones.map { PhoneNumbers.matchKey(it.number) }.filter { it.length >= 7 }.distinct()))
            }
            val members = JSONArray()
            members().forEach { m -> members.put(person(m.lookupKey).put("d", m.meta.reachOutDays).put("r", m.meta.rhythm ?: JSONObject.NULL)) }
            out[X_MEMBERS] = members.toString()
            val items = JSONArray()
            for (i in interactions.all()) {
                items.put(person(i.lookupKey).put("t", i.type.name).put("c", i.channel?.name ?: JSONObject.NULL).put("at", i.time).put("note", i.note ?: JSONObject.NULL).put("u", i.dedupeKey))
            }
            out[X_INTERACTIONS] = items.toString()
            // R10: life events remembered yearly.
            val yearly = JSONArray()
            yearlyFlags().forEach { (key, flags) -> yearly.put(person(key).put("y", JSONArray(flags.toList()))) }
            out[X_YEARLY] = yearly.toString()
            return out
        }

        override suspend fun import(values: Map<String, String>) {
            importCounting(values)
        }

        override suspend fun importCounting(values: Map<String, String>): Int {
            values[X_CONFIG]?.let { v -> updateConfig { CircleConfig.decode(v) } }
            if (values.keys.none { it == X_MEMBERS || it == X_INTERACTIONS || it == X_YEARLY }) return 0
            val contacts = contactsNow()
            val byKey = contacts.associateBy { it.lookupKey }
            var unmatched = 0
            // Lookup keys differ on another phone: fall back to a shared number, then to the same name.
            fun resolve(o: JSONObject): app.parley.common.ContactSummary? {
                byKey[o.optString("k")]?.let { return it }
                val phones = o.optJSONArray("p")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
                val name = o.optString("n")
                return (
                    contacts.firstOrNull { c -> phones.isNotEmpty() && c.phones.any { PhoneNumbers.matchKey(it.number) in phones } }
                        ?: contacts.filter { it.displayName == name && name.isNotBlank() }.singleOrNull()
                    ).also { if (it == null) unmatched++ }
            }
            values[X_MEMBERS]?.let { json ->
                val a = runCatching { JSONArray(json) }.getOrNull() ?: return@let
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val c = resolve(o) ?: continue
                    editMeta(c.lookupKey, create = true) { m ->
                        if (m.reachOutDays != null) null else m.copy(contactId = c.id, reachOutDays = o.optInt("d", 30), rhythm = o.optString("r").takeIf { o.has("r") && !o.isNull("r") })
                    }
                }
            }
            values[X_INTERACTIONS]?.let { json ->
                val a = runCatching { JSONArray(json) }.getOrNull() ?: return@let
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val c = resolve(o) ?: continue
                    val type = InteractionType.entries.firstOrNull { it.name == o.optString("t") } ?: InteractionType.OTHER
                    val channel = if (o.isNull("c")) null else InteractionChannel.decode(o.optString("c"))
                    val note = if (o.isNull("note")) null else o.optString("note")
                    // The unique key makes a second restore of the same backup add nothing.
                    runCatching { interactions.log(c.lookupKey, c.id, type, channel, o.optLong("at"), note, o.optString("u").ifEmpty { Interactions.manualKey(java.util.UUID.randomUUID().toString()) }) }
                }
            }
            values[X_YEARLY]?.let { json ->
                val a = runCatching { JSONArray(json) }.getOrNull() ?: return@let
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val c = resolve(o) ?: continue
                    val flags = o.optJSONArray("y")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty()
                    editMeta(c.lookupKey, create = true) { m -> m.copy(contactId = c.id, yearlyEvents = YearlyEvents.merge(m.yearlyEvents, YearlyEvents.encode(flags.toSet()))) }
                }
            }
            return unmatched
        }
    }

    companion object {
        private const val FILE = "parley_circle"
        private const val K_CONFIG = "config_v1"
        const val S_WISHED = "wished"
        private const val X_CONFIG = "${BackupExtras.PREFIX}circle.config"
        private const val X_MEMBERS = "${BackupExtras.PREFIX}circle.members"
        private const val X_INTERACTIONS = "${BackupExtras.PREFIX}circle.interactions"
        private const val X_YEARLY = "${BackupExtras.PREFIX}circle.yearly"
    }
}
