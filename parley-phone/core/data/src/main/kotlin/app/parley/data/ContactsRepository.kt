package app.parley.data

import android.Manifest
import android.content.ContentProviderResult
import android.content.OperationApplicationException
import android.util.Log
import app.parley.common.PhoneIdentity
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.PhoneLookup
import android.provider.ContactsContract.RawContacts
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.PhoneNumbers
import app.parley.common.people.Batches
import app.parley.common.people.ContactText
import app.parley.common.people.Handles
import app.parley.common.people.RowEdits
import app.parley.common.record.ContentDiff
import app.parley.common.record.Messengers
import app.parley.common.record.Mime
import app.parley.common.record.NewContactAccount
import app.parley.data.people.ParleyWriteLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Observes a content URI; emits Unit on start and on each change.
 *
 * Registering fails with a SecurityException while the permission is missing. When [retry] is given, each of
 * its emissions (e.g. a refresh after the permission was granted) tries to register again, so the flow starts
 * following changes without a restart.
 */
fun ContentResolver.changes(uri: Uri, retry: Flow<*>? = null): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    fun register(): Boolean = try {
        registerContentObserver(uri, true, observer)
        true
    } catch (_: SecurityException) {
        false
    }
    var registered = register()
    val retrying = retry?.let { r ->
        launch {
            r.collect {
                if (!registered) {
                    registered = register()
                    if (registered) trySend(Unit)
                }
            }
        }
    }
    awaitClose {
        retrying?.cancel()
        if (registered) unregisterContentObserver(observer)
    }
}.onStart { emit(Unit) }.conflate()

/** How long the address book must be quiet after a change before it is read again. */
private const val CHANGE_QUIET_MS = 750L

/** More changed contacts than this (a first account sync, a restore) reload the whole list instead of patching it. */
private const val INCREMENTAL_MAX = 500

/** Ids per `IN (…)` selection. */
private const val IN_CHUNK = 500

/** Like [debounce], except that the first value passes at once (a first load shouldn't wait). */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.debounceAfterFirst(timeoutMillis: Long): Flow<T> = flow {
    var first = true
    emitAll(
        debounce {
            if (first) {
                first = false
                0L
            } else {
                timeoutMillis
            }
        },
    )
}

/**
 * [started]: when the shared [contacts] list starts loading (the container defers it in processes started for a call,
 * a worker or a widget; see [StartGate]).
 */
class ContactsRepository(private val context: Context, scope: CoroutineScope, started: SharingStarted = SharingStarted.Eagerly) {
    private val cr: ContentResolver = context.contentResolver

    /** Called before Parley changes existing contacts (set by the container to journal them). */
    var beforeChange: (suspend (ids: List<Long>, action: String) -> List<Long>)? = null

    /** Journal ids of the most recent change, for "Undo". */
    var lastJournalIds: List<Long> = emptyList()
        private set

    /** A deletion never goes ahead without its undo copy; other changes do, with a log entry. */
    private suspend fun journal(ids: List<Long>, action: String) {
        lastJournalIds = try {
            beforeChange?.invoke(ids, action).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ContactsRepository", "Journal failed for $action", e)
            if (action == "DELETE") throw IllegalStateException("Couldn't keep an undo copy, so nothing was deleted", e)
            emptyList()
        }
    }

    /** Bumped after permission changes so observers reload. */
    private val reload = MutableStateFlow(0)

    // A sync adapter or a bulk edit sends a burst of change notifications: the first load is immediate, later ones
    // wait until the burst has been quiet for a moment, so one sync means one reload rather than dozens.
    private val contactChanges = cr.changes(Contacts.CONTENT_URI, retry = reload).debounceAfterFirst(CHANGE_QUIET_MS)

    val contacts: StateFlow<List<ContactSummary>?> = combine(contactChanges, reload) { _, _ -> }
        .map { loadAll() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, started, null)

    fun refresh() {
        reload.value++
    }

    /**
     * Reads the contact list now, straight from the provider (the [contacts] snapshot only catches up after an
     * asynchronous reload), e.g. right after a restore inserted contacts.
     */
    suspend fun loadNow(): List<ContactSummary> = withContext(Dispatchers.IO) { loadAll() }

    /** The last list, by id, with each contact's last-updated time: the base an incremental reload patches. */
    private class Loaded(val region: String?, val byId: Map<Long, Pair<Long, ContactSummary>>)

    @Volatile private var loaded: Loaded? = null

    /** How the last load went, for tests: whether it patched the previous list, and how many contacts it read. */
    internal data class LoadStats(val incremental: Boolean, val read: Int)

    @Volatile internal var lastLoad = LoadStats(false, 0)
        private set

    /**
     * The contact list. After the first load only contacts whose last-updated time moved are read again (a light
     * query of ids and times finds them, and gives the display order); a large change, such as a first account
     * sync, reads everything as before.
     */
    private fun loadAll(): List<ContactSummary> {
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) {
            loaded = null
            return emptyList()
        }
        val region = PhoneEnv.countryIso(context)
        val prev = loaded?.takeIf { it.region == region }
        if (prev != null) {
            val order = ArrayList<Long>(prev.byId.size + 16)
            val stamps = HashMap<Long, Long>(prev.byId.size + 16)
            val listed = cr.safeQuery(
                Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                sort = Contacts.SORT_KEY_PRIMARY + " COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    order += c.getLong(0)
                    stamps[c.getLong(0)] = c.getLong(1)
                }
                true
            } ?: false
            val changed = order.filter { id -> prev.byId[id]?.first != stamps[id] }
            if (listed && changed.size <= INCREMENTAL_MAX) {
                val fresh = if (changed.isEmpty()) emptyMap() else changed.chunked(IN_CHUNK).flatMap { summaries(it) }.associateBy { it.second.id }
                val byId = HashMap<Long, Pair<Long, ContactSummary>>(order.size)
                val out = ArrayList<ContactSummary>(order.size)
                for (id in order) {
                    // A contact that vanished between the two queries is left out.
                    val s = fresh[id] ?: prev.byId[id]?.takeIf { id !in fresh && stamps[id] == it.first } ?: continue
                    byId[id] = s
                    out += s.second
                }
                loaded = Loaded(region, byId)
                lastLoad = LoadStats(incremental = true, read = fresh.size)
                return out
            }
        }
        val all = summaries(null)
        loaded = Loaded(region, all.associateBy { it.second.id })
        lastLoad = LoadStats(incremental = false, read = all.size)
        return all.map { it.second }
    }

    /** [ids]' summaries (or everyone's), in display order, each with its last-updated time. */
    private fun summaries(ids: List<Long>?): List<Pair<Long, ContactSummary>> {
        val phones = HashMap<Long, MutableList<PhoneEntry>>()
        val seen = HashMap<Long, MutableSet<String>>()
        val region = PhoneEnv.countryIso(context)
        val byContact = ids?.let { "${Data.CONTACT_ID} IN (${it.joinToString(",")})" }
        cr.safeQuery(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY, Phone.IS_PRIMARY),
            byContact,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(1) ?: continue
                val key = PhoneIdentity.key(number, region)
                if (!seen.getOrPut(id) { HashSet() }.add(key)) continue
                // Default number: super-primary across the contact, or primary within its account.
                phones.getOrPut(id) { ArrayList(2) } += PhoneEntry(number, c.getInt(2), c.getString(3), c.getInt(4) != 0 || c.getInt(5) != 0)
            }
        }
        val emails = HashMap<Long, MutableList<String>>()
        cr.safeQuery(Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS), byContact)?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(1) ?: continue
                emails.getOrPut(c.getLong(0)) { ArrayList(1) } += a
            }
        }
        val out = ArrayList<Pair<Long, ContactSummary>>()
        val blank = ArrayList<Int>()
        cr.safeQuery(
            Contacts.CONTENT_URI,
            arrayOf(
                Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.DISPLAY_NAME_ALTERNATIVE,
                Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED, Contacts.PHONETIC_NAME, Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ),
            ids?.let { "${Contacts._ID} IN (${it.joinToString(",")})" },
            sort = Contacts.SORT_KEY_PRIMARY + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                // A contact holding only an address, note, website… has no display name: list it anyway.
                val name = c.getString(2)?.takeIf { it.isNotBlank() }
                    ?: phones[id]?.firstOrNull()?.number
                    ?: emails[id]?.firstOrNull()
                    ?: "".also { blank += out.size }
                out += c.getLong(7) to ContactSummary(
                    id = id,
                    lookupKey = c.getString(1) ?: "",
                    displayName = name,
                    photoUri = c.getString(4),
                    starred = c.getInt(5) != 0,
                    phones = phones[id].orEmpty(),
                    emails = emails[id].orEmpty(),
                    displayNameAlt = c.getString(3)?.takeIf { it.isNotBlank() } ?: name,
                    phoneticName = c.getString(6)?.takeIf { it.isNotBlank() },
                )
            }
        }
        if (blank.isNotEmpty()) {
            val names = blankNames(blank.map { out[it].second.id })
            blank.forEach { i ->
                out[i] = out[i].let { (t, s) -> t to (names[s.id] ?: ContactText.NO_NAME).let { n -> s.copy(displayName = n, displayNameAlt = n) } }
            }
        }
        return out
    }

    /**
     * The given name ("first name" in western order) of each of [contactIds] that has one, from its structured name
     * (the super-primary name first). For short mentions that shouldn't guess by splitting the display name.
     */
    suspend fun givenNames(contactIds: Collection<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, String>()
        for (chunk in contactIds.distinct().chunked(500)) {
            cr.safeQuery(
                Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, StructuredName.GIVEN_NAME),
                "${Data.MIMETYPE} = ? AND ${Data.CONTACT_ID} IN (${chunk.joinToString(",")})", arrayOf(StructuredName.CONTENT_ITEM_TYPE),
                sort = "${Data.IS_SUPER_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val given = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    out.putIfAbsent(c.getLong(0), given)
                }
            }
        }
        out
    }

    /** Names for contacts Android shows without a display name, from their address, website, note… */
    private fun blankNames(ids: List<Long>): Map<Long, String> {
        val rows = HashMap<Long, MutableList<Pair<String, String?>>>()
        for (chunk in ids.chunked(500)) {
            cr.safeQuery(
                Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, StructuredPostal.STREET, StructuredPostal.CITY),
                "${Data.CONTACT_ID} IN (${chunk.joinToString(",")})",
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: continue
                    val v = if (mime == StructuredPostal.CONTENT_ITEM_TYPE) {
                        c.getString(2)?.takeIf { it.isNotBlank() } ?: listOfNotNull(c.getString(3), c.getString(4)).filter { it.isNotBlank() }.joinToString(", ")
                    } else {
                        c.getString(2)
                    }
                    rows.getOrPut(c.getLong(0)) { ArrayList() } += mime to v
                }
            }
        }
        return rows.mapValues { ContactText.blankContactName(it.value) }
    }

    /** Every contact straight from the provider, without waiting for [contacts] to load (e.g. in a worker, F17). */
    suspend fun snapshot(): List<ContactSummary> = contacts.value ?: withContext(Dispatchers.IO) { loadAll() }

    /**
     * Fast indexed lookup used on incoming calls. I9: when this user has a work profile, the enterprise lookup is
     * used, which also finds work contacts when the work profile's policy allows caller ID across profiles (personal
     * contacts come first). If that fails (policy, older OEM builds) the personal lookup is used, as before.
     */
    fun lookup(number: String): CallerInfo? {
        if (number.isBlank() || !Permissions.has(context, Manifest.permission.READ_CONTACTS)) return null
        if (WorkProfile.exists(context)) {
            try {
                lookupIn(Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(number)), number, strict = true)?.let { return it }
            } catch (_: Exception) {
                // Fall back to the personal profile only.
            }
        }
        return lookupIn(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), number, strict = false)
    }

    private fun lookupIn(uri: Uri, number: String, strict: Boolean): CallerInfo? {
        val projection = arrayOf(
            PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI,
            PhoneLookup.TYPE, PhoneLookup.LABEL, PhoneLookup.CUSTOM_RINGTONE, PhoneLookup.SEND_TO_VOICEMAIL,
        ) + STAR.takeUnless { strict }.orEmpty() // the drive profile answers favourites; the work profile's lookup isn't asked
        val cursor = if (strict) cr.query(uri, projection, null, null, null) else cr.safeQuery(uri, projection)
        val found = cursor?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            CallerInfo(
                contactId = id,
                lookupKey = c.getString(1),
                name = c.getString(2) ?: number,
                photoUri = c.getString(3),
                numberLabel = Phone.getTypeLabel(context.resources, c.getInt(4), c.getString(5))?.toString(),
                customRingtone = c.getString(6),
                sendToVoicemail = c.getInt(7) != 0,
                work = Contacts.isEnterpriseContactId(id),
                starred = !strict && c.getInt(8) != 0,
            )
        } ?: return null
        // M3: the enterprise lookup isn't asked for the star, so a personal contact it found ("personal contacts come
        // first") reads it from its own row; without this every favourite looks unstarred on a phone with a work profile.
        return if (strict && !found.work) found.copy(starred = starredOf(found.contactId)) else found
    }

    /** Whether the personal contact [contactId] is a favourite (false when it can't be read). */
    private fun starredOf(contactId: Long): Boolean =
        cr.safeQuery(Contacts.CONTENT_URI, arrayOf(Contacts.STARRED), "${Contacts._ID}=?", arrayOf(contactId.toString()), null)
            ?.use { c -> c.moveToFirst() && c.getInt(0) != 0 } ?: false

    /** Company names by contact id, for every contact with one (an empty map when they can't be read). */
    fun organizations(): Map<Long, String> =
        cr.safeQuery(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Organization.COMPANY),
            "${Data.MIMETYPE}=?", arrayOf(Organization.CONTENT_ITEM_TYPE),
        )?.use { c ->
            val out = HashMap<Long, String>()
            while (c.moveToNext()) {
                val company = c.getString(1)?.trim().orEmpty()
                if (company.isNotEmpty()) out.putIfAbsent(c.getLong(0), company)
            }
            out
        }.orEmpty()

    /** (company, job title) of [contactId]'s first organization row, or null. */
    /** The contact's pronouns (Parley's row, [Mime.PRONOUNS]), for the call screen; null when it has none. */
    fun pronounsOf(contactId: Long): String? =
        cr.safeQuery(
            Data.CONTENT_URI, arrayOf(Data.DATA1), "${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contactId.toString(), Mime.PRONOUNS), null,
        )?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0)?.trim() else null }.firstOrNull { it.isNotEmpty() } }

    fun organization(contactId: Long): Pair<String, String>? =
        cr.safeQuery(
            Data.CONTENT_URI, arrayOf(Organization.COMPANY, Organization.TITLE),
            "${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contactId.toString(), Organization.CONTENT_ITEM_TYPE),
        )?.use { c ->
            var out: Pair<String, String>? = null
            while (out == null && c.moveToNext()) {
                val company = c.getString(0)?.trim().orEmpty()
                val title = c.getString(1)?.trim().orEmpty()
                if (company.isNotEmpty() || title.isNotEmpty()) out = company to title
            }
            out
        }

    /**
     * Whether [number] belongs to a contact: true / false, or null when it couldn't be checked (no permission,
     * provider busy or failing). Screening must treat null as "maybe a contact" so a real contact is never blocked.
     * Work-profile contacts count too (like caller ID in [lookup]), when the work profile's policy lets them be seen.
     */
    fun isContact(number: String): Boolean? {
        if (number.isBlank()) return false
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) return null
        val personal = try {
            cr.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(PhoneLookup._ID), null, null, null)?.use { it.count > 0 }
        } catch (_: Exception) {
            null
        }
        if (personal == true || !WorkProfile.exists(context)) return personal
        val work = try {
            cr.query(Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(PhoneLookup._ID), null, null, null)?.use { it.count > 0 }
        } catch (_: Exception) {
            // The policy may forbid the lookup: then the personal answer stands.
            null
        }
        return if (work == true) true else personal
    }

    /** Aggregated view of a contact (all sources), for display. */
    suspend fun details(contactId: Long): ContactDetails? = load(contactId, forEdit = false)

    /**
     * Editable view: only the rows of one writable raw contact, so edits never touch another
     * account's copy (e.g. a messenger's read-only entry).
     */
    suspend fun editable(contactId: Long): ContactDetails? = load(contactId, forEdit = true)

    /** Editable view of one specific copy (raw contact) of a contact ("Edit this copy"). */
    suspend fun editableRaw(contactId: Long, rawId: Long): ContactDetails? = load(contactId, forEdit = true, preferRaw = rawId)

    private fun writableTypes(): Set<String> = DeviceAccounts.uploadingTypes()

    /** Local (AOSP, Android 15 or OEM phone account) or uploading; never SIM or messenger accounts. */
    private fun isWritable(a: AccountRef, types: Set<String>, local: AccountRef): Boolean = DeviceAccounts.isWritable(a, types, local)

    /**
     * Ids among [dataIds] that the provider marks read-only. IS_READ_ONLY can't be projected, only selected on
     * (see contacts-android's DataIsReadOnly), hence the `_ID IN (…) AND is_read_only=1` query.
     */
    fun readOnlyDataIds(dataIds: Collection<Long>): Set<Long> {
        if (dataIds.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        for (chunk in dataIds.distinct().chunked(500)) {
            cr.safeQuery(Data.CONTENT_URI, arrayOf(Data._ID), "${Data._ID} IN (${chunk.joinToString(",")}) AND ${Data.IS_READ_ONLY}=1")
                ?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
        }
        return out
    }

    private suspend fun load(contactId: Long, forEdit: Boolean, preferRaw: Long? = null): ContactDetails? = withContext(Dispatchers.IO) {
        var base: ContactDetails = cr.safeQuery(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
            arrayOf(
                Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_URI, Contacts.STARRED,
                Contacts.CUSTOM_RINGTONE, Contacts.SEND_TO_VOICEMAIL,
            ),
        )?.use { c ->
            if (!c.moveToFirst()) return@withContext null
            ContactDetails(
                id = c.getLong(0), lookupKey = c.getString(1) ?: "", displayName = c.getString(2) ?: "",
                photoUri = c.getString(3), starred = c.getInt(4) != 0, customRingtone = c.getString(5),
                sendToVoicemail = c.getInt(6) != 0,
            )
        } ?: return@withContext null

        val raws = ArrayList<RawContactRef>()
        val versions = HashMap<Long, Long>()
        cr.safeQuery(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.VERSION),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0",
            arrayOf(contactId.toString()),
        )?.use { c ->
            while (c.moveToNext()) {
                raws += RawContactRef(c.getLong(0), AccountRef(c.getString(1), c.getString(2)))
                versions[c.getLong(0)] = c.getLong(3)
            }
        }
        val types = writableTypes()
        val local = localAccount()
        val writable = raws.filter { isWritable(it.account, types, local) }
        val target = preferRaw?.let { p -> writable.firstOrNull { it.id == p } }
            ?: writable.firstOrNull { it.account.type == "com.google" } ?: writable.firstOrNull { !it.account.isLocal } ?: writable.firstOrNull()
        base = base.copy(rawContacts = raws, editRawId = target?.id, editRawVersion = target?.let { versions[it.id] }, writableRawIds = writable.map { it.id })
        if (forEdit && target == null) {
            // Nothing writable: start from the aggregated name only; save() adds a linked device entry.
            val shown = load(contactId, forEdit = false) ?: return@withContext null
            return@withContext base.copy(given = shown.given, family = shown.family, middle = shown.middle, prefix = shown.prefix, suffix = shown.suffix)
        }

        val phones = ArrayList<DataItem>()
        val emails = ArrayList<DataItem>()
        val sites = ArrayList<DataItem>()
        val relations = ArrayList<DataItem>()
        val handles = ArrayList<HandleItem>()
        val addrs = ArrayList<PostalItem>()
        val events = ArrayList<EventItem>()
        val groups = HashSet<Long>()
        val dataIds = ArrayList<Long>()
        cr.safeQuery(
            Data.CONTENT_URI,
            arrayOf(
                Data._ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6,
                Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10, Data.IS_SUPER_PRIMARY,
            ),
            if (forEdit) "${Data.RAW_CONTACT_ID}=?" else "${Data.CONTACT_ID}=?",
            arrayOf(if (forEdit) target!!.id.toString() else contactId.toString()),
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                dataIds += id
                fun s(i: Int) = c.getString(i).orEmpty()
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (base.nameId == null) base = base.copy(
                        nameId = id, given = s(3), family = s(4), prefix = s(5), middle = s(6), suffix = s(7),
                        phoneticGiven = s(8), phoneticFamily = s(10),
                    )
                    Nickname.CONTENT_ITEM_TYPE -> if (base.nicknameId == null) base = base.copy(nicknameId = id, nickname = s(2))
                    Mime.PRONOUNS -> if (base.pronounsId == null) base = base.copy(pronounsId = id, pronouns = s(2))
                    Organization.CONTENT_ITEM_TYPE -> if (base.orgId == null) base = base.copy(orgId = id, company = s(2), title = s(5))
                    Note.CONTENT_ITEM_TYPE -> if (base.noteId == null) base = base.copy(noteId = id, note = s(2))
                    Phone.CONTENT_ITEM_TYPE -> phones += DataItem(id, s(2), c.getInt(3), c.getString(4), c.getInt(12) != 0)
                    Email.CONTENT_ITEM_TYPE -> emails += DataItem(id, s(2), c.getInt(3), c.getString(4), c.getInt(12) != 0)
                    Im.CONTENT_ITEM_TYPE, SipAddress.CONTENT_ITEM_TYPE ->
                        Handles.fromRow(c.getString(1), c.getString(2), c.getString(6), c.getString(7))
                            ?.let { h -> handles += HandleItem(id, h.service, h.value, h.customProtocol) }
                    Website.CONTENT_ITEM_TYPE -> sites += DataItem(id, s(2), c.getInt(3), c.getString(4))
                    Relation.CONTENT_ITEM_TYPE -> relations += DataItem(id, s(2), c.getInt(3), c.getString(4))
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        var p = PostalItem(
                            id, street = s(5), city = s(8), region = s(9), postcode = s(10), country = s(11).ifEmpty { "" },
                            type = c.getInt(3), label = c.getString(4), poBox = s(6), neighborhood = s(7),
                        )
                        if (p.isBlank) p = p.copy(street = s(2))
                        addrs += p
                    }
                    Event.CONTENT_ITEM_TYPE -> events += EventItem(id, s(2), c.getInt(3), c.getString(4))
                    GroupMembership.CONTENT_ITEM_TYPE -> groups += c.getLong(2)
                }
            }
        }
        base.copy(
            phones = if (forEdit) phones else phones.distinctBy { PhoneIdentity.key(it.value, PhoneEnv.countryIso(context)) + it.type },
            emails = emails, websites = sites, relations = relations, addresses = addrs, events = events, groupIds = groups,
            handles = if (forEdit) handles else handles.distinctBy { it.service to it.value.trim().lowercase() },
            readOnlyDataIds = if (forEdit) readOnlyDataIds(dataIds) else emptySet(),
        )
    }

    /**
     * Accounts contacts can be saved, imported, moved or restored to: the device account first, then accounts whose
     * contacts sync adapter uploads. SIM, messenger and other read-only accounts are never offered.
     */
    fun accounts(): List<AccountRef> = DeviceAccounts.targets(context)

    /**
     * Android 16's default account for new contacts while it is a cloud account, which then takes every new contact
     * instead of the phone; null when the phone takes them (and always before Android 16).
     */
    fun systemDefaultAccount(): AccountRef? = DeviceAccounts.newContacts(context).cloudInstead

    /** The account a new contact asked for [requested] really goes to, and whether Android redirected it. */
    fun newContactTarget(requested: AccountRef?): NewContactAccount.Decision<AccountRef> = DeviceAccounts.newContacts(context).decide(requested)

    /** Whether new data can be written to raw contacts of [account]. */
    fun isWritableAccount(account: AccountRef): Boolean = isWritable(account, writableTypes(), localAccount())

    /** All contact dates (birthdays, anniversaries…) with the first phone number. */
    fun events(): List<ContactEvent> {
        val phones = HashMap<Long, String>()
        contacts.value.orEmpty().forEach { c -> c.phones.firstOrNull()?.let { phones[c.id] = it.number } }
        val out = ArrayList<ContactEvent>()
        cr.safeQuery(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.LOOKUP_KEY, Data.DISPLAY_NAME_PRIMARY, Data.PHOTO_THUMBNAIL_URI, Event.START_DATE, Event.TYPE, Event.LABEL),
            "${Data.MIMETYPE}=?", arrayOf(Event.CONTENT_ITEM_TYPE),
        )?.use { c ->
            while (c.moveToNext()) {
                val date = c.getString(4) ?: continue
                val id = c.getLong(0)
                out += ContactEvent(id, c.getString(1).orEmpty(), c.getString(2) ?: continue, c.getString(3), date, c.getInt(5), c.getString(6), phones[id])
            }
        }
        return out.distinctBy { "${it.contactId}|${it.date}|${it.type}" }
    }

    /**
     * The user's labels: groups with a title that aren't system groups ("My Contacts", Family/Friends/Coworkers),
     * auto-add, read-only or the favourites group ("Starred in Android").
     */
    fun groups(): List<GroupInfo> {
        val out = ArrayList<GroupInfo>()
        cr.safeQuery(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME, Groups.SYSTEM_ID, Groups.AUTO_ADD, Groups.GROUP_IS_READ_ONLY, Groups.FAVORITES),
            "${Groups.DELETED}=0",
            sort = Groups.TITLE + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)
                if (!ContentDiff.isUserGroup(title, c.getString(4), c.getInt(5) != 0, c.getInt(6) != 0, c.getInt(7) != 0)) continue
                out += GroupInfo(c.getLong(0), title!!, AccountRef(c.getString(2), c.getString(3)))
            }
        }
        return out
    }

    /** Ids of [groupIds] that are user labels; system, read-only and favourites groups are left out. */
    fun userGroupIds(groupIds: Collection<Long>): Set<Long> {
        if (groupIds.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        cr.safeQuery(
            Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE, Groups.SYSTEM_ID, Groups.AUTO_ADD, Groups.GROUP_IS_READ_ONLY, Groups.FAVORITES),
            "${Groups._ID} IN (${groupIds.distinct().joinToString(",")}) AND ${Groups.DELETED}=0",
        )?.use { c ->
            while (c.moveToNext()) if (ContentDiff.isUserGroup(c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4) != 0, c.getInt(5) != 0)) out += c.getLong(0)
        }
        return out
    }

    /**
     * Trimmed titles of one contact's labels, in every account (the way rules and ringtones name labels), straight
     * from the provider. Null when contacts can't be read.
     */
    fun labelTitlesOrNull(contactId: Long): Set<String>? = try {
        val ids = HashSet<Long>()
        cr.query(
            Data.CONTENT_URI, arrayOf(GroupMembership.GROUP_ROW_ID),
            "${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE), null,
        )?.use { q -> while (q.moveToNext()) ids += q.getLong(0) } ?: throw IllegalStateException("contacts unavailable")
        if (ids.isEmpty()) {
            emptySet()
        } else {
            val out = HashSet<String>()
            cr.query(
                Groups.CONTENT_URI, arrayOf(Groups.TITLE),
                "${Groups._ID} IN (${ids.joinToString(",")}) AND ${Groups.DELETED}=0 AND ${Groups.SYSTEM_ID} IS NULL AND ${Groups.AUTO_ADD}=0" +
                    " AND ${Groups.GROUP_IS_READ_ONLY}=0 AND ${Groups.FAVORITES}=0", null, null,
            )?.use { q -> while (q.moveToNext()) q.getString(0)?.takeIf { it.isNotBlank() }?.let { out += it.trim() } } ?: throw IllegalStateException("contacts unavailable")
            out
        }
    } catch (_: Exception) {
        null
    }

    fun labelTitlesOf(contactId: Long): Set<String> = labelTitlesOrNull(contactId).orEmpty()

    fun contactIdsInGroup(groupId: Long): Set<Long> {
        val ids = HashSet<Long>()
        cr.safeQuery(
            // The group id as a number (as LabelsRepository.removeMembers does): it matches however the provider typed the column.
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID),
            "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=$groupId",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE),
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids
    }

    suspend fun createGroup(title: String, account: AccountRef): Long? = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put(Groups.TITLE, title)
            put(Groups.ACCOUNT_TYPE, account.type)
            put(Groups.ACCOUNT_NAME, account.name)
            put(Groups.GROUP_VISIBLE, 1)
        }
        cr.insert(Groups.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
    }

    /**
     * What [save] wrote: the aggregate [contactId] and the raw contact it created or edited ([rawId]; null when the
     * edited copy became empty and was removed). Returned per call, so concurrent saves never see each other's ids.
     */
    data class SaveResult(
        val contactId: Long,
        val rawId: Long?,
        /** Set when the account asked for refused new contacts (Android 16's cloud default) and this one took it. */
        val redirectedTo: AccountRef? = null,
    )

    /**
     * Saves [edited]. When [original] is null a new raw contact is created in [account].
     * Returns the aggregate contact id and the raw contact written.
     */
    suspend fun save(original: ContactDetails?, edited: ContactDetails, account: AccountRef?, photo: Uri?, removePhoto: Boolean): SaveResult? =
        withContext(Dispatchers.IO) {
            val expected = original?.editRawVersion
            val versioned = original?.editRawId?.takeIf { expected != null }
            // Cheap early check, so a stale edit isn't journaled; the batch's assertion below closes the race.
            if (versioned != null && rawVersion(versioned) != expected) throw ContactChangedElsewhereException(original?.id ?: 0L)
            if (original != null && original.id > 0) journal(listOf(original.id), "EDIT")
            val ops = ArrayList<ContentProviderOperation>()
            if (versioned != null && expected != null) {
                // The whole batch fails when the raw contact was changed (or deleted) since the editor loaded it.
                ops += ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(RawContacts.CONTENT_URI, versioned))
                    .withSelection("${RawContacts.DELETED}=0", null)
                    .withValue(RawContacts.VERSION, expected)
                    .withExpectedCount(1)
                    .build()
            }
            val rawId: Long?
            val insertTarget: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder
            val linkTo: List<Long> = if (original != null && original.editRawId == null) original.rawContacts.map { it.id } else emptyList()
            var redirectedTo: AccountRef? = null
            if (original == null || original.editRawId == null) {
                // Android 16 refuses the phone while the user's default is a cloud account: that account takes it.
                val decision = DeviceAccounts.newContacts(context).decide(if (original == null) account else null)
                val acc = decision.account
                if (decision.redirected) redirectedTo = acc
                ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, acc.type)
                    .withValue(RawContacts.ACCOUNT_NAME, acc.name)
                    .build()
                rawId = null
                insertTarget = { it.withValueBackReference(Data.RAW_CONTACT_ID, 0) }
            } else {
                rawId = original.editRawId
                insertTarget = { it.withValue(Data.RAW_CONTACT_ID, rawId) }
            }

            // Only rows that really changed are written, so a sync adapter uploads (and other apps see) just the edit.
            val changed = LinkedHashSet<String>()
            // Read-only rows are shown locked; an edit or removal of one must not be written or logged.
            val locked = original?.readOnlyDataIds.orEmpty()
            fun insert(mime: String, values: ContentValues) {
                ops += insertTarget(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                    .withValue(Data.MIMETYPE, mime).withValues(values).build()
                changed += fieldName(mime)
            }
            fun update(id: Long, mime: String, values: ContentValues) {
                if (id in locked) return
                ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, id)).withValues(values).build()
                changed += fieldName(mime)
            }
            fun delete(id: Long, mime: String) {
                if (id in locked) return
                ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, id)).build()
                changed += fieldName(mime)
            }
            fun single(id: Long?, mime: String, blank: Boolean, values: ContentValues, same: Boolean = false) {
                when {
                    id != null && blank -> delete(id, mime)
                    id != null && same -> Unit
                    id != null -> update(id, mime, values)
                    !blank -> insert(mime, values)
                }
            }
            fun t(s: String?) = s.orEmpty().trim()

            val nameValues = ContentValues().apply {
                put(StructuredName.DISPLAY_NAME, edited.composedName.ifBlank { null })
                put(StructuredName.PREFIX, edited.prefix.trim().ifEmpty { null })
                put(StructuredName.GIVEN_NAME, edited.given.trim().ifEmpty { null })
                put(StructuredName.MIDDLE_NAME, edited.middle.trim().ifEmpty { null })
                put(StructuredName.FAMILY_NAME, edited.family.trim().ifEmpty { null })
                put(StructuredName.SUFFIX, edited.suffix.trim().ifEmpty { null })
                put(StructuredName.PHONETIC_GIVEN_NAME, edited.phoneticGiven.trim().ifEmpty { null })
                put(StructuredName.PHONETIC_FAMILY_NAME, edited.phoneticFamily.trim().ifEmpty { null })
            }
            val o = original
            val sameName = o != null && listOf(o.prefix, o.given, o.middle, o.family, o.suffix, o.phoneticGiven, o.phoneticFamily).map(::t) ==
                listOf(edited.prefix, edited.given, edited.middle, edited.family, edited.suffix, edited.phoneticGiven, edited.phoneticFamily).map(::t)
            single(original?.nameId, StructuredName.CONTENT_ITEM_TYPE, edited.composedName.isBlank() && edited.phoneticGiven.isBlank() && edited.phoneticFamily.isBlank(), nameValues, sameName)
            single(
                original?.nicknameId, Nickname.CONTENT_ITEM_TYPE, edited.nickname.isBlank(), ContentValues().apply { put(Nickname.NAME, edited.nickname.trim()) },
                o != null && t(o.nickname) == t(edited.nickname),
            )
            // Pronouns: Parley's own row (Android has no kind for them), written like the nickname.
            single(
                original?.pronounsId, Mime.PRONOUNS, edited.pronouns.isBlank(), ContentValues().apply { put(Data.DATA1, edited.pronouns.trim()) },
                o != null && t(o.pronouns) == t(edited.pronouns),
            )
            single(
                original?.orgId, Organization.CONTENT_ITEM_TYPE, edited.company.isBlank() && edited.title.isBlank(),
                ContentValues().apply {
                    put(Organization.COMPANY, edited.company.trim().ifEmpty { null })
                    put(Organization.TITLE, edited.title.trim().ifEmpty { null })
                },
                o != null && t(o.company) == t(edited.company) && t(o.title) == t(edited.title),
            )
            single(original?.noteId, Note.CONTENT_ITEM_TYPE, edited.note.isBlank(), ContentValues().apply { put(Note.NOTE, edited.note.trim()) }, o != null && t(o.note) == t(edited.note))

            fun multi(orig: List<DataItem>, now: List<DataItem>, mime: String, valueCol: String, typeCol: String, labelCol: String) {
                val keep = now.mapNotNull { it.id }.toSet()
                val before = orig.filter { it.id != null }.associateBy { it.id }
                orig.filter { it.id != null && it.id !in keep }.forEach { delete(it.id!!, mime) }
                now.forEach { item ->
                    val v = ContentValues().apply {
                        put(valueCol, item.value.trim())
                        put(typeCol, item.type)
                        put(labelCol, item.label?.takeIf { item.type == 0 })
                    }
                    val prev = item.id?.let { before[it] }
                    val same = prev != null && t(prev.value) == t(item.value) && prev.type == item.type &&
                        prev.label?.takeIf { prev.type == 0 } == item.label?.takeIf { item.type == 0 }
                    when {
                        item.id != null && item.value.isBlank() -> delete(item.id, mime)
                        item.id != null && same -> Unit
                        item.id != null -> update(item.id, mime, v)
                        item.value.isNotBlank() -> insert(mime, v)
                    }
                }
            }
            multi(original?.phones.orEmpty(), edited.phones, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
            multi(original?.emails.orEmpty(), edited.emails, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, Email.TYPE, Email.LABEL)
            multi(original?.websites.orEmpty(), edited.websites, Website.CONTENT_ITEM_TYPE, Website.URL, Website.TYPE, Website.LABEL)
            multi(original?.relations.orEmpty(), edited.relations, Relation.CONTENT_ITEM_TYPE, Relation.NAME, Relation.TYPE, Relation.LABEL)
            multi(
                original?.events.orEmpty().map { DataItem(it.id, it.date, it.type, it.label) },
                edited.events.map { DataItem(it.id, it.date, it.type, it.label) },
                Event.CONTENT_ITEM_TYPE, Event.START_DATE, Event.TYPE, Event.LABEL,
            )

            // Messenger handles. Only Im and SIP rows are planned, so no other row can be touched (see RowEdits).
            fun handleRow(h: HandleItem) = Handles.toColumns(h.handle).let { (m, v) -> RowEdits.Row(h.id, m, v) }
            val handleOps = RowEdits.plan(
                original?.handles.orEmpty().map(::handleRow), edited.handles.map(::handleRow),
                setOf(Im.CONTENT_ITEM_TYPE, SipAddress.CONTENT_ITEM_TYPE), locked,
            )
            fun cv(m: Map<String, String?>) = ContentValues().apply { m.forEach { (k, v) -> put(k, v) } }
            handleOps.forEach { op ->
                when (op) {
                    is RowEdits.Op.Delete -> delete(op.id, op.mime)
                    is RowEdits.Op.Update -> update(op.id, op.mime, cv(op.values))
                    // TYPE_OTHER (3) for both kinds, like other contacts apps.
                    is RowEdits.Op.Insert -> insert(op.mime, cv(op.values).apply { put(Data.DATA2, 3) })
                }
            }

            val keepAddr = edited.addresses.mapNotNull { it.id }.toSet()
            val addrBefore = original?.addresses.orEmpty().filter { it.id != null }.associateBy { it.id }
            original?.addresses.orEmpty().filter { it.id != null && it.id !in keepAddr }.forEach { delete(it.id!!, StructuredPostal.CONTENT_ITEM_TYPE) }
            edited.addresses.forEach { a ->
                val v = ContentValues().apply {
                    put(StructuredPostal.STREET, a.street.trim())
                    put(StructuredPostal.POBOX, a.poBox.trim())
                    put(StructuredPostal.NEIGHBORHOOD, a.neighborhood.trim())
                    put(StructuredPostal.CITY, a.city.trim())
                    put(StructuredPostal.REGION, a.region.trim())
                    put(StructuredPostal.POSTCODE, a.postcode.trim())
                    put(StructuredPostal.COUNTRY, a.country.trim())
                    put(StructuredPostal.FORMATTED_ADDRESS, a.formatted)
                    put(StructuredPostal.TYPE, a.type)
                    put(StructuredPostal.LABEL, a.label?.takeIf { a.type == 0 })
                }
                val prev = a.id?.let { addrBefore[it] }
                val same = prev != null && prev.type == a.type && prev.label?.takeIf { prev.type == 0 } == a.label?.takeIf { a.type == 0 } &&
                    listOf(prev.street, prev.poBox, prev.neighborhood, prev.city, prev.region, prev.postcode, prev.country).map(::t) ==
                    listOf(a.street, a.poBox, a.neighborhood, a.city, a.region, a.postcode, a.country).map(::t)
                when {
                    a.id != null && a.isBlank -> delete(a.id, StructuredPostal.CONTENT_ITEM_TYPE)
                    a.id != null && same -> Unit
                    a.id != null -> update(a.id, StructuredPostal.CONTENT_ITEM_TYPE, v)
                    !a.isBlank -> insert(StructuredPostal.CONTENT_ITEM_TYPE, v)
                }
            }

            // Group membership (only groups of the target account can be assigned).
            val origGroups = original?.groupIds.orEmpty()
            (edited.groupIds - origGroups).forEach { g -> insert(GroupMembership.CONTENT_ITEM_TYPE, ContentValues().apply { put(GroupMembership.GROUP_ROW_ID, g) }) }
            if (rawId != null) {
                (origGroups - edited.groupIds).forEach { g ->
                    ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                        .withSelection(
                            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
                            arrayOf(rawId.toString(), GroupMembership.CONTENT_ITEM_TYPE, g.toString()),
                        ).build()
                    changed += fieldName(GroupMembership.CONTENT_ITEM_TYPE)
                }
            }
            if (photo != null || removePhoto) changed += "Photo"
            if (removePhoto) {
                (original?.writableRawIds.orEmpty() + listOfNotNull(rawId)).distinct().forEach { rid ->
                    ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                        .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(rid.toString(), Photo.CONTENT_ITEM_TYPE))
                        .build()
                }
            }

            val onlyAssert = ops.size == 1 && versioned != null
            val results = if (ops.isEmpty() || onlyAssert) {
                emptyArray<ContentProviderResult>()
            } else {
                try {
                    cr.applyBatch(ContactsContract.AUTHORITY, ops)
                } catch (e: OperationApplicationException) {
                    if (versioned != null && rawVersion(versioned) != expected) throw ContactChangedElsewhereException(original?.id ?: 0L)
                    throw e
                }
            }
            val finalRawId = rawId ?: results.firstOrNull { it.uri != null }?.uri?.let { ContentUris.parseId(it) } ?: return@withContext null
            // Every field of this copy was cleared: remove the empty raw contact instead of leaving a blank behind
            // (AOSP does the same, F24). The person stays if another copy has details.
            if (rawId != null && changed.isNotEmpty() && photo == null && isBlankRaw(rawId)) {
                val others = original?.rawContacts.orEmpty().map { it.id }.filter { it != rawId }
                cr.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), null, null)
                return@withContext others.firstNotNullOfOrNull { contactIdForRaw(it) }?.let { SaveResult(it, null) }
            }
            if (linkTo.isNotEmpty()) setAggregation(linkTo + finalRawId, AggregationExceptions.TYPE_KEEP_TOGETHER)
            if (photo != null) writePhoto(finalRawId, photo)
            val contactId = contactIdForRaw(finalRawId)
            // Remember what Parley wrote (and the version it left), for "Why did this change?" and the journal.
            runCatching {
                val key = contactId?.let { id -> cr.safeQuery(ContentUris.withAppendedId(Contacts.CONTENT_URI, id), arrayOf(Contacts.LOOKUP_KEY))?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
                writeLog.version(cr, finalRawId)?.let { v -> writeLog.record(finalRawId, key ?: original?.lookupKey.orEmpty(), v, changed.toList()) }
            }
            contactId?.let { SaveResult(it, finalRawId, redirectedTo) }
        }

    /** Parley's own saves, per raw contact ("Why did this change?"). */
    val writeLog by lazy { ParleyWriteLog(context) }

    private fun fieldName(mime: String): String = FIELD_NAMES[mime] ?: "Other"

    private fun localAccount(): AccountRef = DeviceAccounts.localAccount(context)

    /** A raw contact with no content left (group memberships don't count). */
    private fun isBlankRaw(rawId: Long): Boolean =
        cr.safeQuery(
            Data.CONTENT_URI, arrayOf(Data._ID),
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}<>?", arrayOf(rawId.toString(), GroupMembership.CONTENT_ITEM_TYPE),
        )?.use { it.count == 0 } ?: false

    /** The raw contact's current RawContacts.VERSION; null when it is gone (or marked deleted). */
    private fun rawVersion(rawId: Long): Long? =
        cr.safeQuery(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.VERSION), "${RawContacts.DELETED}=0")?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun contactIdForRaw(rawId: Long): Long? =
        cr.safeQuery(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID))?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun writePhoto(rawId: Long, source: Uri) {
        // Bounded decode, EXIF rotation, HEIC, centre square, 720 px (the old full decode could run out of memory).
        val bytes = ContactPhotoProcessor.process(cr, source) ?: return
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        cr.openAssetFileDescriptor(uri, "rw")?.use { fd -> fd.createOutputStream().use { it.write(bytes) } }
    }

    suspend fun setStarred(contactId: Long, starred: Boolean) = updateContact(contactId, ContentValues().apply { put(Contacts.STARRED, if (starred) 1 else 0) })

    /** Every ringtone set on a device contact, or null when they can't be read (no permission, provider gone). */
    suspend fun customRingtones(): List<String>? = withContext(Dispatchers.IO) {
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS)) return@withContext null
        cr.safeQuery(Contacts.CONTENT_URI, arrayOf(Contacts.CUSTOM_RINGTONE), "${Contacts.CUSTOM_RINGTONE} IS NOT NULL", null, null)?.use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }
    }

    suspend fun setRingtone(contactId: Long, ringtone: String?) = updateContact(contactId, ContentValues().apply { put(Contacts.CUSTOM_RINGTONE, ringtone) })

    suspend fun setSendToVoicemail(contactId: Long, value: Boolean) =
        updateContact(contactId, ContentValues().apply { put(Contacts.SEND_TO_VOICEMAIL, if (value) 1 else 0) })

    private suspend fun updateContact(contactId: Long, values: ContentValues) = withContext(Dispatchers.IO) {
        cr.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), values, null, null)
    }

    /** Current id of a contact remembered by lookup key (ids change when contacts are re-aggregated); null if gone. */
    suspend fun resolve(lookupKey: String, contactId: Long): Long? = withContext(Dispatchers.IO) {
        runCatching { Contacts.lookupContact(cr, Contacts.getLookupUri(contactId, lookupKey))?.let(ContentUris::parseId) }.getOrNull()
    }

    /**
     * Where a remembered contact is now: its current id and lookup key, resolved like AOSP does
     * (`lookupContact(getLookupUri(id, key))`, which survives links, unlinks and first syncs), or null when it is
     * gone or contacts can't be read. [contactId] may be null or stale.
     */
    fun currentOf(lookupKey: String, contactId: Long?): Pair<Long, String>? = try {
        val uri = if (contactId != null && contactId > 0) Contacts.getLookupUri(contactId, lookupKey) else Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey)
        val id = Contacts.lookupContact(cr, uri)?.let(ContentUris::parseId)
        id?.let { i -> lookupKeyOf(i)?.let { i to it } }
    } catch (_: Exception) {
        null
    }

    /** Journals contacts before a change made outside this repository (folder sync, restore). */
    suspend fun recordChange(ids: List<Long>, action: String) = journal(ids, action)

    /** The raw contact edits go to, and every writable raw contact of [contactId]. */
    suspend fun writableRaws(contactId: Long): Pair<Long?, List<Long>> =
        details(contactId)?.let { it.editRawId to it.writableRawIds } ?: (null to emptyList())

    /** Deletes without a journal entry: moving a contact into the vault must leave no plaintext copy behind. */
    suspend fun deleteUnjournaled(contactIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val ops = contactIds.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(Contacts.CONTENT_URI, it)).build() }
        if (ops.isNotEmpty()) cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
    }

    suspend fun delete(contactIds: Collection<Long>) = withContext(Dispatchers.IO) {
        journal(contactIds.toList(), "DELETE")
        val ops = contactIds.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(Contacts.CONTENT_URI, it)).build() }
        if (ops.isNotEmpty()) cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
    }

    /** Live raw contact ids of [contactId], oldest first. */
    fun rawIds(contactId: Long): List<Long> =
        cr.safeQuery(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()),
            sort = RawContacts._ID,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    /** The live raw contacts among [rawIds], with the contact each belongs to now. */
    fun contactsOfRaws(rawIds: Collection<Long>): Map<Long, Long> {
        if (rawIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, Long>()
        cr.safeQuery(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.CONTACT_ID),
            "${RawContacts._ID} IN (${rawIds.distinct().joinToString(",")}) AND ${RawContacts.DELETED}=0",
        )?.use { c -> while (c.moveToNext()) if (!c.isNull(1)) out[c.getLong(0)] = c.getLong(1) }
        return out
    }

    /** Current lookup key of [contactId], or null. */
    /** [contactId]'s phone numbers, straight from the provider (the call path doesn't load the whole list). */
    fun numbersOf(contactId: Long): List<String> =
        cr.safeQuery(Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()))
            ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }.orEmpty()

    /** Every contact's lookup key → contact id, in one query (the key sweep's listing); null when contacts can't be read. */
    fun lookupKeys(): Map<String, Long>? = try {
        cr.safeQuery(Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.LOOKUP_KEY))?.use(::keyIdMap)
    } catch (_: Exception) {
        null
    }

    private fun keyIdMap(c: Cursor): Map<String, Long> {
        val out = HashMap<String, Long>(c.count)
        while (c.moveToNext()) c.getString(1)?.let { out[it] = c.getLong(0) }
        return out
    }

    fun lookupKeyOf(contactId: Long): String? =
        cr.safeQuery(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), arrayOf(Contacts.LOOKUP_KEY))?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /**
     * Called after Parley changed how raw contacts are grouped into contacts (join, separate, move), with the
     * (contact id, lookup key) pairs from before, so per-contact metadata and temporary flags can follow.
     * Set by the container.
     */
    var afterRelink: (suspend (before: List<Pair<Long, String>>, kind: String) -> Unit)? = null

    private suspend fun relinked(before: List<Pair<Long, String>>, kind: String) {
        try {
            afterRelink?.invoke(before, kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ContactsRepository", "Metadata re-key after $kind failed", e)
        }
    }

    /** Tells the container that keys may have moved (ContactMover and others outside this class). */
    suspend fun notifyRelinked(before: List<Pair<Long, String>>, kind: String) = relinked(before, kind)

    /**
     * Merges several contacts into one (platform "join"). Like AOSP and contacts-android's ContactLinks, the name of
     * the first contact becomes the default name, so the shown name doesn't flip after linking. Returns the
     * merged contact's id.
     */
    suspend fun join(contactIds: List<Long>): Long? = withContext(Dispatchers.IO) {
        journal(contactIds, "MERGE")
        val before = contactIds.mapNotNull { id -> lookupKeyOf(id)?.let { id to it } }
        val nameRow = contactIds.firstNotNullOfOrNull { structuredNameRowOf(it) }
        val raws = contactIds.flatMap { rawIds(it) }.distinct()
        setAggregation(raws, AggregationExceptions.TYPE_KEEP_TOGETHER)
        nameRow?.let { runCatching { setDefault(it) } }
        val merged = raws.firstNotNullOfOrNull { contactIdForRaw(it) }
        relinked(before, "MERGE")
        merged
    }

    /** Splits a merged contact back into its raw contacts. */
    suspend fun separate(contactId: Long) = withContext(Dispatchers.IO) {
        journal(listOf(contactId), "SEPARATE")
        val before = listOfNotNull(lookupKeyOf(contactId)?.let { contactId to it })
        setAggregation(rawIds(contactId), AggregationExceptions.TYPE_KEEP_SEPARATE)
        relinked(before, "SEPARATE")
    }

    /**
     * The structured-name row that currently names [contactId] (its NAME_RAW_CONTACT_ID's name row), when the
     * display name comes from a structured name. Mirrors contacts-android's `nameRowIdToUseAsDefault`.
     */
    private fun structuredNameRowOf(contactId: Long): Long? {
        val nameRaw = cr.safeQuery(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), arrayOf(Contacts.DISPLAY_NAME_SOURCE, Contacts.NAME_RAW_CONTACT_ID),
        )?.use { c ->
            if (!c.moveToFirst() || c.getInt(0) != ContactsContract.DisplayNameSources.STRUCTURED_NAME || c.isNull(1)) null else c.getLong(1)
        } ?: return null
        return cr.safeQuery(
            Data.CONTENT_URI, arrayOf(Data._ID), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(nameRaw.toString(), StructuredName.CONTENT_ITEM_TYPE),
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /**
     * Makes data row [dataId] the default of its kind: primary in its raw contact and super-primary for the whole
     * contact, clearing the flags on the other rows of that kind first (like AOSP's "Set default" and
     * contacts-android's DefaultContactData). For numbers, e-mails and names.
     */
    suspend fun setDefault(dataId: Long): Boolean = withContext(Dispatchers.IO) {
        val (raw, contact, mime) = cr.safeQuery(
            ContentUris.withAppendedId(Data.CONTENT_URI, dataId), arrayOf(Data.RAW_CONTACT_ID, Data.CONTACT_ID, Data.MIMETYPE),
        )?.use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getString(2)) else null } ?: return@withContext false
        val ops = arrayListOf(
            ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(raw.toString(), mime))
                .withValue(Data.IS_PRIMARY, 0).build(),
            ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection("${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contact.toString(), mime))
                .withValue(Data.IS_SUPER_PRIMARY, 0).build(),
            ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, dataId))
                .withValue(Data.IS_PRIMARY, 1).withValue(Data.IS_SUPER_PRIMARY, 1).build(),
        )
        runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    /** Clears the default [mimeType] row of [contactId] (no default number / e-mail any more). */
    suspend fun clearDefault(contactId: Long, mimeType: String): Boolean = withContext(Dispatchers.IO) {
        val ops = arrayListOf(
            ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection("${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contactId.toString(), mimeType))
                .withValue(Data.IS_PRIMARY, 0).withValue(Data.IS_SUPER_PRIMARY, 0).build(),
        )
        runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    /** AggregationExceptions for every pair, in batches small enough for the provider (F16: 33+ copies failed). */
    private fun setAggregation(raws: List<Long>, type: Int) {
        if (raws.size < 2) return
        val ops = Batches.pairs(raws).map { (a, b) ->
            ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                .withValue(AggregationExceptions.TYPE, type)
                .withValue(AggregationExceptions.RAW_CONTACT_ID1, a)
                .withValue(AggregationExceptions.RAW_CONTACT_ID2, b)
                .build()
        }
        Batches.chunks(ops).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
    }

    /**
     * Deletes exactly these raw contacts (a temporary contact's own copies), journaling their contacts first. Other
     * raw contacts of the same person are never touched.
     */
    suspend fun deleteRaws(rawIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val owners = contactsOfRaws(rawIds)
        if (owners.isEmpty()) return@withContext
        journal(owners.values.distinct(), "DELETE")
        val ops = owners.keys.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, it)).build() }
        Batches.chunks(ops).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
    }

    /**
     * Takes back raw contacts Parley has just inserted (a conversion that couldn't finish): gone at once, as if never
     * written, with no journal entry (nothing of the user's is lost) and no deletion waiting for a sync. Only these
     * raw contacts; others the provider joined them with are never touched.
     */
    suspend fun discardInserted(rawIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val ops = rawIds.distinct().map { id ->
            val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
            ContentProviderOperation.newDelete(uri).build()
        }
        Batches.chunks(ops).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
    }

    /**
     * Removes a contact that moved into the private vault, leaving as little readable behind as possible:
     * phone-only and never-synced copies are purged at once (CALLER_IS_SYNCADAPTER, like AccountDiagnostics does);
     * synced copies are deleted normally so their account removes them on the server too. Messenger copies
     * (WhatsApp, Signal…) belong to their app and can't be deleted here: they stay until that app syncs its contacts.
     */
    data class VaultPurge(
        /** Some copy was synced: other apps may still see it until the account's next sync. */
        val synced: Boolean,
        /** Messenger copies remain until that app resyncs its contacts. */
        val messengerCopies: Boolean,
    )

    suspend fun purgeForVault(contactId: Long): VaultPurge = withContext(Dispatchers.IO) {
        val local = localAccount()
        var synced = false
        var messengers = false
        val ops = ArrayList<ContentProviderOperation>()
        cr.safeQuery(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.SOURCE_ID),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()),
        )?.use { c ->
            while (c.moveToNext()) {
                val account = AccountRef(c.getString(1), c.getString(2))
                if (Messengers.isMessengerAccount(account.type)) { messengers = true; continue } // the messenger owns it
                val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, c.getLong(0))
                val unsynced = DeviceAccounts.isLocal(account, local) || c.isNull(3)
                if (unsynced) {
                    ops += ContentProviderOperation.newDelete(uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()).build()
                } else {
                    synced = true
                    ops += ContentProviderOperation.newDelete(uri).build()
                }
            }
        }
        Batches.chunks(ops).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
        VaultPurge(synced, messengers)
    }

    fun vcardUri(lookupKey: String): Uri = Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI, Uri.encode(lookupKey))

    /** One vCard stream for several contacts (the platform composer). */
    fun multiVcardUri(lookupKeys: List<String>): Uri =
        Uri.withAppendedPath(Contacts.CONTENT_MULTI_VCARD_URI, Uri.encode(lookupKeys.joinToString(":")))

    /**
     * Adds contacts to a label. Membership must live on a raw contact of the label's account;
     * returns how many contacts could not be added (no raw contact in that account).
     */
    suspend fun addToGroup(contactIds: Collection<Long>, group: GroupInfo): Int = withContext(Dispatchers.IO) {
        var skipped = 0
        val ops = ArrayList<ContentProviderOperation>()
        for (id in contactIds) {
            val raw = cr.safeQuery(
                RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
                "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0 AND " +
                    (if (group.account.type == null) "${RawContacts.ACCOUNT_TYPE} IS NULL" else "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=?"),
                listOfNotNull(id.toString(), group.account.type, group.account.name.takeIf { group.account.type != null }).toTypedArray(),
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (raw == null) {
                skipped++
                continue
            }
            val exists = cr.safeQuery(
                Data.CONTENT_URI, arrayOf(Data._ID),
                "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
                arrayOf(raw.toString(), GroupMembership.CONTENT_ITEM_TYPE, group.id.toString()),
            )?.use { it.count > 0 } ?: false
            if (!exists) {
                ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, raw)
                    .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, group.id)
                    .build()
            }
        }
        Batches.chunks(ops).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
        skipped
    }

    fun contactUri(id: Long, lookupKey: String): Uri = Contacts.getLookupUri(id, lookupKey)

    /** Resolves a contact from a contacts URI (lookup or id based) coming from another app. */
    fun resolveContactId(uri: Uri): Long? = try {
        val lookup = Contacts.lookupContact(cr, uri)
        lookup?.let { ContentUris.parseId(it) } ?: cr.safeQuery(uri, arrayOf(Data.CONTACT_ID))?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }
}

internal fun ContentResolver.safeQuery(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    args: Array<String>? = null,
    sort: String? = null,
): Cursor? = try {
    query(uri, projection, selection, args, sort)
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** The field an edited row belongs to, as History & undo names it. */
private val FIELD_NAMES: Map<String, String> = mapOf(
    StructuredName.CONTENT_ITEM_TYPE to "Name",
    Nickname.CONTENT_ITEM_TYPE to "Nickname",
    Mime.PRONOUNS to "Pronouns",
    Organization.CONTENT_ITEM_TYPE to "Company",
    Note.CONTENT_ITEM_TYPE to "Note",
    Phone.CONTENT_ITEM_TYPE to "Phone",
    Email.CONTENT_ITEM_TYPE to "Email",
    Website.CONTENT_ITEM_TYPE to "Website",
    Relation.CONTENT_ITEM_TYPE to "Relation",
    Im.CONTENT_ITEM_TYPE to "Messenger handles",
    SipAddress.CONTENT_ITEM_TYPE to "Messenger handles",
    Event.CONTENT_ITEM_TYPE to "Dates",
    StructuredPostal.CONTENT_ITEM_TYPE to "Address",
    GroupMembership.CONTENT_ITEM_TYPE to "Labels",
)

/** The caller lookup's extra column for the star (the personal profile only). */
private val STAR = arrayOf(PhoneLookup.STARRED)
