package app.parley.data.extras

import android.content.Context
import android.provider.ContactsContract
import app.parley.common.ContactSummary
import app.parley.common.extras.DndStars
import app.parley.common.extras.LabelPolicies
import app.parley.common.extras.LabelPolicy
import app.parley.common.extras.SimpleConfig
import app.parley.common.extras.SimpleSetup
import app.parley.common.extras.TripMatch
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.backup.BackupExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * v3.2 extras kept in their own small store (like the Circle's config): X2 the last "Who's in…" city, X3 label
 * policies, X4 the simple-mode setup. All of it travels in the encrypted backup ([backupExtras]).
 */
class ExtrasStore(private val c: DataContainer) {
    private val prefs = c.appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- X3: label policies ---

    private val _policies = MutableStateFlow(LabelPolicies.decode(prefs.getString(K_POLICIES, null)))
    val policies: StateFlow<Map<String, LabelPolicy>> = _policies.asStateFlow()

    @Synchronized
    fun updatePolicies(f: (Map<String, LabelPolicy>) -> Map<String, LabelPolicy>) {
        val next = f(_policies.value).filterValues { !it.isEmpty }
        if (next == _policies.value) return
        _policies.value = next
        prefs.edit().putString(K_POLICIES, LabelPolicies.encode(next)).apply()
    }

    fun updatePolicy(title: String, f: (LabelPolicy) -> LabelPolicy) = updatePolicies { m -> m + (title to f(m[title] ?: LabelPolicy())) }

    /**
     * Labels were renamed or merged on the labels screen (see LabelReferences). Contacts starred for a label's Do Not
     * Disturb choice follow it, or are unstarred when the label they end up in doesn't let people through.
     */
    suspend fun labelsRenamed(renames: Map<String, String>) {
        updatePolicies { LabelPolicies.renamed(it, renames) }
        val dnd = dndLabels()
        applyRelease(DndStars.renamed(_dndStars.value, renames, dnd))
    }

    /** Labels were deleted: their policies go, and the contacts Parley starred only for them are unstarred. */
    suspend fun labelsDeleted(titles: Set<String>) {
        updatePolicies { LabelPolicies.deleted(it, titles) }
        applyRelease(DndStars.release(_dndStars.value, titles))
    }

    // --- X3: contacts starred for "Allow through Do Not Disturb" ---

    private val _dndStars = MutableStateFlow(DndStars.decode(prefs.getString(K_DND_STARS, null)))

    /** Lookup key → the labels that made Parley star the contact (see [DndStars]). */
    val dndStars: StateFlow<Map<String, Set<String>>> = _dndStars.asStateFlow()

    @Synchronized
    private fun updateDndStars(f: (Map<String, Set<String>>) -> Map<String, Set<String>>) {
        val next = f(_dndStars.value).filterValues { it.isNotEmpty() }
        if (next == _dndStars.value) return
        _dndStars.value = next
        prefs.edit().putString(K_DND_STARS, DndStars.encode(next)).apply()
    }

    private fun dndLabels(): Set<String> = _policies.value.filterValues { it.allowThroughDnd }.keys

    /**
     * Stars the [members] of [label] that aren't starred and records that Parley did; members Parley already starred
     * for another label are recorded under this one too, so switching either off leaves them starred for the other.
     * A star the user set isn't recorded. Returns how many were starred now.
     */
    suspend fun starForDnd(label: String, members: List<ContactSummary>): Int = withContext(Dispatchers.IO) {
        val ledger = _dndStars.value
        val starred = members.filter { !it.starred && runCatching { c.contacts.setStarred(it.id, true) }.isSuccess }
        val shared = members.filter { it.starred && it.lookupKey in ledger }
        updateDndStars { DndStars.add(it, label, (starred + shared).map { m -> m.lookupKey }) }
        c.contacts.refresh()
        starred.size
    }

    /** [label]'s "Allow through Do Not Disturb" was switched off: returns how many contacts were unstarred. */
    suspend fun dndOff(label: String): Int {
        updatePolicy(label) { it.copy(allowThroughDnd = false) }
        return applyRelease(DndStars.release(_dndStars.value, setOf(label)))
    }

    /**
     * Unstars the released contacts, found by lookup key (not by today's label members), except those still in a
     * label that lets people through: they stay starred, recorded under that label.
     */
    private suspend fun applyRelease(r: DndStars.Release): Int = withContext(Dispatchers.IO) {
        if (r.unstar.isEmpty()) {
            updateDndStars { r.ledger }
            return@withContext 0
        }
        val dnd = dndLabels()
        val ids = r.unstar.associateWith { k -> runCatching { c.contacts.currentOf(k, null)?.first }.getOrNull() }
        val wants = ids.mapValues { (_, id) -> if (id == null || dnd.isEmpty()) emptySet() else runCatching { c.contacts.labelTitlesOf(id) }.getOrDefault(emptySet()).intersect(dnd) }
        val settled = DndStars.settle(r.ledger, r.unstar, wants)
        var n = 0
        for (k in settled.unstar) {
            val id = ids[k] ?: continue
            if (runCatching { c.contacts.setStarred(id, false) }.isSuccess) n++
        }
        updateDndStars { settled.ledger }
        c.contacts.refresh()
        n
    }

    /** F8: a contact's lookup key changed (see ContactKeys). */
    fun dndRekey(from: String, to: String) = updateDndStars { DndStars.rekey(it, from, to) }

    /** The contact moved into the vault: nothing of it stays outside. */
    fun dndForget(key: String) = updateDndStars { it - key }

    fun dndKeys(): Set<String> = _dndStars.value.keys

    /**
     * X3: the SIM a label asks for when calling [number], for people without a SIM of their own (the remembered SIM
     * per number wins; callers check it first). Blocking contacts query: call off the main thread. Null = none.
     */
    fun labelSimFor(number: String): String? {
        val p = _policies.value
        if (p.values.none { it.simId != null }) return null
        return runCatching {
            val id = c.contacts.lookup(number)?.contactId ?: return null
            val available = c.sims.accounts().map { it.id }.toSet()
            LabelPolicies.simFor(c.contacts.labelTitlesOf(id), p, available)
        }.getOrNull()
    }

    /** X3: the Circle rhythm the labels of [contactId] suggest, with the label's title. */
    suspend fun labelRhythmFor(contactId: Long): Pair<String, Int>? {
        val p = _policies.value
        if (p.values.none { it.rhythmDays != null }) return null
        return withContext(Dispatchers.IO) { LabelPolicies.rhythmFor(c.contacts.labelTitlesOf(contactId), p) }
    }

    // --- X4: simple mode ---

    private val _simple = MutableStateFlow(SimpleSetup.decode(prefs.getString(K_SIMPLE, null)))
    val simple: StateFlow<SimpleConfig> = _simple.asStateFlow()

    @Synchronized
    fun updateSimple(f: (SimpleConfig) -> SimpleConfig) {
        val next = with(SimpleSetup) { f(_simple.value).normalised() }
        if (next == _simple.value) return
        _simple.value = next
        prefs.edit().putString(K_SIMPLE, SimpleSetup.encode(next)).apply()
    }

    // --- X5: handshake ---

    private val _swap = MutableStateFlow(prefs.getBoolean(K_SWAP, false))

    /** "Swap": show your own card right after a received one. */
    val handshakeSwap: StateFlow<Boolean> = _swap.asStateFlow()

    fun setHandshakeSwap(on: Boolean) {
        _swap.value = on
        prefs.edit().putBoolean(K_SWAP, on).apply()
    }

    // --- X2: trip mode ---

    var lastTripCity: String?
        get() = prefs.getString(K_TRIP, null)
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove(K_TRIP) else putString(K_TRIP, v.trim()) }.apply()

    /** Everything "Who's in…" needs, read once: addresses and notes from the provider, number places offline. */
    data class TripData(val people: List<TripMatch.Person>, val cities: List<String>)

    suspend fun tripData(countryIso: String): TripData = withContext(Dispatchers.IO) {
        val contacts = c.contacts.snapshot()
        val places = HashMap<Long, MutableList<String>>()
        val notes = HashMap<Long, String>()
        val cities = ArrayList<String>()
        val postal = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
        val note = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
        runCatching {
            c.appContext.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
                    ContactsContract.CommonDataKinds.StructuredPostal.CITY, ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
                ),
                "${ContactsContract.Data.MIMETYPE} IN (?,?)", arrayOf(postal, note), null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    if (cur.getString(1) == postal) {
                        val list = places.getOrPut(id) { ArrayList(2) }
                        val city = cur.getString(3).orEmpty().trim()
                        if (city.isNotEmpty()) cities += city
                        listOf(city, cur.getString(4).orEmpty(), cur.getString(5).orEmpty(), cur.getString(2).orEmpty()).filter { it.isNotBlank() }.forEach { list += it }
                    } else {
                        cur.getString(2)?.takeIf { it.isNotBlank() }?.let { notes[id] = (notes[id]?.plus("\n") ?: "") + it }
                    }
                }
            }
        }
        val locales = listOf(Locale.getDefault(), Locale.ENGLISH).distinctBy { it.language }
        val people = contacts.map { s ->
            val numberPlaces = s.phones.flatMap { p -> locales.mapNotNull { l -> NumberInfo.location(p.number, countryIso, l) } }.distinct()
            TripMatch.Person(s.id, s.displayName, places[s.id].orEmpty(), notes[s.id].orEmpty(), numberPlaces)
        }
        TripData(people, cities)
    }

    // --- Backup (inside the encrypted backup's settings section) ---

    val backupExtras: BackupExtras = object : BackupExtras {
        override suspend fun export(): Map<String, String> = buildMap {
            put(X_POLICIES, LabelPolicies.encode(_policies.value))
            // X3: which contacts Parley starred for which label, so a restored phone can still unstar them later.
            put(X_DND_STARS, DndStars.encode(_dndStars.value))
            // People by name and number: lookup keys mean nothing on another phone (the simple home resolves them).
            put(X_SIMPLE, SimpleSetup.encode(_simple.value.copy(people = _simple.value.people.map { it.copy(lookupKey = null) })))
            lastTripCity?.let { put(X_TRIP, it) }
        }

        override suspend fun import(values: Map<String, String>) {
            values[X_POLICIES]?.let { v ->
                // SIM ids are per phone: a SIM that isn't here is simply skipped when calling.
                val incoming = LabelPolicies.decode(v)
                updatePolicies { current -> incoming + current }
            }
            // The contacts' stars come back with the contacts; the record of which ones Parley set comes back here.
            values[X_DND_STARS]?.let { v -> updateDndStars { current -> DndStars.merge(current, DndStars.decode(v)) } }
            values[X_SIMPLE]?.let { v -> updateSimple { SimpleSetup.decode(v) } }
            values[X_TRIP]?.let { lastTripCity = it }
        }
    }

    companion object {
        private const val FILE = "parley_extras"
        private const val K_POLICIES = "label_policies_v1"
        private const val K_SIMPLE = "simple_mode_v1"
        private const val K_TRIP = "trip_city"
        private const val K_SWAP = "handshake_swap"
        private const val K_DND_STARS = "dnd_stars_v1"
        private const val X_POLICIES = "${BackupExtras.PREFIX}extras.labelPolicies"
        private const val X_SIMPLE = "${BackupExtras.PREFIX}extras.simple"
        private const val X_TRIP = "${BackupExtras.PREFIX}extras.tripCity"
        private const val X_DND_STARS = "${BackupExtras.PREFIX}extras.dndStars"
    }
}
