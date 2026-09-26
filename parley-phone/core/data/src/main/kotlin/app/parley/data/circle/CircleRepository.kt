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
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
        val m = meta.meta(lookupKey) ?: ContactMetaEntity(lookupKey)
        val old = KeepRhythm.decode(m.rhythm)
        val rhythm = when {
            everyDays == null -> KeepRhythm()
            natural -> NaturalRhythm.relearn(old.copy(mode = app.parley.common.circle.RhythmMode.NATURAL, learnedAt = null, snoozedUntil = null), contactTimes(lookupKey), System.currentTimeMillis(), ZoneId.systemDefault())
            else -> KeepRhythm()
        }
        meta.setMeta(m.copy(contactId = contactId ?: m.contactId, reachOutDays = everyDays, lastNudgedAt = null, rhythm = rhythm.encode()))
    }

    /** R4 "Not now": the next reminder about [lookupKey] waits one more full gap. */
    suspend fun snooze(lookupKey: String, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val m = meta.meta(lookupKey) ?: return@withContext
        val r = KeepRhythm.decode(m.rhythm)
        val days = r.days(m.reachOutDays ?: return@withContext)
        meta.setMeta(m.copy(rhythm = r.copy(snoozedUntil = CirclePlanner.snooze(now, days)).encode()))
    }

    /** Monthly re-learning of natural rhythms (R4); returns the members, updated. */
    suspend fun relearnDue(now: Long = System.currentTimeMillis()): List<Member> = withContext(Dispatchers.IO) {
        members().map { mem ->
            if (!mem.rhythm.needsRelearn(now)) return@map mem
            val next = NaturalRhythm.relearn(mem.rhythm, contactTimes(mem.lookupKey), now, ZoneId.systemDefault())
            val row = mem.meta.copy(rhythm = next.encode())
            meta.setMeta(row)
            Member(row, next)
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

    /** A launch Parley just made for a Circle contact. [autoLogged]: "Always" already recorded it (offer Undo). */
    data class LogPrompt(val lookupKey: String, val contactId: Long?, val name: String, val channel: InteractionChannel, val time: Long, val dedupeKey: String, val autoLogged: Boolean)

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
        val auto = mode == LogMode.ALWAYS && runCatching { interactions.log(lookupKey, contactId, channel.type, channel, now, null, key) }.getOrNull() != null
        if (mode == LogMode.ALWAYS && !auto) return
        _prompt.value = LogPrompt(lookupKey, contactId, name, channel, now, key, auto)
    }

    /** "Log" on the question: records it (a second tap in the same 10 minutes records nothing new). */
    suspend fun accept(p: LogPrompt): Boolean {
        clearPrompt(p)
        return runCatching { interactions.log(p.lookupKey, p.contactId, p.channel.type, p.channel, p.time, null, p.dedupeKey) != null }.getOrDefault(false)
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
        val next = Promises.setDone(note.text, line, done)
        if (next == note.text) return@withContext false
        runCatching {
            when (note.source) {
                NoteSource.CALL -> meta.setCallNoteText(note.id, next)
                NoteSource.LOGGED -> interactions.setNote(note.id, next)
                NoteSource.PINNED -> meta.meta(lookupKey)?.let { meta.setMeta(it.copy(pinnedNote = next)) }
            }
            true
        }.getOrDefault(false)
    }

    // --- R10: life events remembered yearly ---

    suspend fun setYearly(lookupKey: String, contactId: Long?, key: String, on: Boolean) = withContext(Dispatchers.IO) {
        if (lookupKey.isEmpty()) return@withContext
        val m = meta.meta(lookupKey) ?: ContactMetaEntity(lookupKey)
        meta.setMeta(m.copy(contactId = contactId ?: m.contactId, yearlyEvents = YearlyEvents.toggle(m.yearlyEvents, key, on)))
    }

    /** Lookup key -> flagged event keys. */
    suspend fun yearlyFlags(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        meta.allMetaNow().mapNotNull { r -> YearlyEvents.decode(r.yearlyEvents).takeIf { it.isNotEmpty() }?.let { r.lookupKey to it } }.toMap()
    }

    // --- R6: history for the People card ---

    /**
     * Every contact entry since [since] as [PeopleInsights.Touch]es keyed by lookup key: calls with contacts from
     * the call log (private contacts' calls aren't there, and their interactions were moved into the vault) and
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
        override suspend fun export(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            out[X_CONFIG] = CircleConfig.encode(_config.value)
            val contacts = withTimeoutOrNull(30_000) { contactsFlow().filterNotNull().first() }.orEmpty().associateBy { it.lookupKey }
            fun person(key: String): JSONObject? {
                val c = contacts[key] ?: return null
                return JSONObject().put("k", key).put("n", c.displayName).put("p", JSONArray(c.phones.map { PhoneNumbers.matchKey(it.number) }.filter { it.length >= 7 }.distinct()))
            }
            val members = JSONArray()
            members().forEach { m ->
                val p = person(m.lookupKey) ?: return@forEach
                members.put(p.put("d", m.meta.reachOutDays).put("r", m.meta.rhythm ?: JSONObject.NULL))
            }
            out[X_MEMBERS] = members.toString()
            val items = JSONArray()
            for (i in interactions.all()) {
                val p = person(i.lookupKey) ?: continue
                items.put(p.put("t", i.type.name).put("c", i.channel?.name ?: JSONObject.NULL).put("at", i.time).put("note", i.note ?: JSONObject.NULL).put("u", i.dedupeKey))
            }
            out[X_INTERACTIONS] = items.toString()
            // R10: life events remembered yearly.
            val yearly = JSONArray()
            yearlyFlags().forEach { (key, flags) -> person(key)?.let { yearly.put(it.put("y", JSONArray(flags.toList()))) } }
            out[X_YEARLY] = yearly.toString()
            return out
        }

        override suspend fun import(values: Map<String, String>) {
            values[X_CONFIG]?.let { v -> updateConfig { CircleConfig.decode(v) } }
            val contacts = withTimeoutOrNull(30_000) { contactsFlow().filterNotNull().first() }.orEmpty()
            val byKey = contacts.associateBy { it.lookupKey }
            // Lookup keys differ on another phone: fall back to a shared number, then to the same name.
            fun resolve(o: JSONObject): app.parley.common.ContactSummary? {
                byKey[o.optString("k")]?.let { return it }
                val phones = o.optJSONArray("p")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
                val name = o.optString("n")
                return contacts.firstOrNull { c -> phones.isNotEmpty() && c.phones.any { PhoneNumbers.matchKey(it.number) in phones } }
                    ?: contacts.filter { it.displayName == name && name.isNotBlank() }.singleOrNull()
            }
            values[X_MEMBERS]?.let { json ->
                val a = runCatching { JSONArray(json) }.getOrNull() ?: return@let
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val c = resolve(o) ?: continue
                    withContext(Dispatchers.IO) {
                        val m = meta.meta(c.lookupKey) ?: ContactMetaEntity(c.lookupKey)
                        if (m.reachOutDays == null) meta.setMeta(m.copy(contactId = c.id, reachOutDays = o.optInt("d", 30), rhythm = o.optString("r").takeIf { o.has("r") && !o.isNull("r") }))
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
                    withContext(Dispatchers.IO) {
                        val m = meta.meta(c.lookupKey) ?: ContactMetaEntity(c.lookupKey)
                        meta.setMeta(m.copy(contactId = c.id, yearlyEvents = YearlyEvents.merge(m.yearlyEvents, YearlyEvents.encode(flags.toSet()))))
                    }
                }
            }
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
