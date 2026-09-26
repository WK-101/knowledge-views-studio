package app.parley.data.extras

import android.content.Context
import android.provider.ContactsContract
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

    /** Labels were renamed or merged on the labels screen (see LabelReferences). */
    fun labelsRenamed(renames: Map<String, String>) = updatePolicies { LabelPolicies.renamed(it, renames) }

    fun labelsDeleted(titles: Set<String>) = updatePolicies { LabelPolicies.deleted(it, titles) }

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
            // People by name and number: lookup keys mean nothing on another phone (the simple home resolves them).
            put(X_SIMPLE, SimpleSetup.encode(_simple.value.copy(people = _simple.value.people.map { it.copy(lookupKey = null) })))
            lastTripCity?.let { put(X_TRIP, it) }
        }

        override suspend fun import(values: Map<String, String>) {
            values[X_POLICIES]?.let { v ->
                // SIM ids are per phone: a SIM that isn't here is simply skipped when calling.
                val incoming = LabelPolicies.decode(v).mapValues { it.value.copy(starredByPolicy = emptySet()) }
                updatePolicies { current -> incoming + current }
            }
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
        private const val X_POLICIES = "${BackupExtras.PREFIX}extras.labelPolicies"
        private const val X_SIMPLE = "${BackupExtras.PREFIX}extras.simple"
        private const val X_TRIP = "${BackupExtras.PREFIX}extras.tripCity"
    }
}
