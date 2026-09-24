package app.parley.data

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Observes a content URI; emits Unit on start and on each change. */
fun ContentResolver.changes(uri: Uri): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    try {
        registerContentObserver(uri, true, observer)
    } catch (_: SecurityException) {
    }
    awaitClose { unregisterContentObserver(observer) }
}.onStart { emit(Unit) }.conflate()

class ContactsRepository(private val context: Context, scope: CoroutineScope) {
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ContactsRepository", "Journal failed for $action", e)
            if (action == "DELETE") throw IllegalStateException("Couldn't keep an undo copy, so nothing was deleted", e)
            emptyList()
        }
    }

    /** Bumped after permission changes so observers reload. */
    private val reload = MutableStateFlow(0)

    val contacts: StateFlow<List<ContactSummary>?> = kotlinx.coroutines.flow.combine(cr.changes(Contacts.CONTENT_URI), reload) { _, _ -> }
        .map { loadAll() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun refresh() {
        reload.value++
    }

    private fun loadAll(): List<ContactSummary> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return emptyList()
        val phones = HashMap<Long, MutableList<PhoneEntry>>()
        val seen = HashMap<Long, MutableSet<String>>()
        cr.safeQuery(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY, Phone.IS_PRIMARY),
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(1) ?: continue
                val key = PhoneNumbers.matchKey(number)
                if (!seen.getOrPut(id) { HashSet() }.add(key)) continue
                // Default number: super-primary across the contact, or primary within its account.
                phones.getOrPut(id) { ArrayList(2) } += PhoneEntry(number, c.getInt(2), c.getString(3), c.getInt(4) != 0 || c.getInt(5) != 0)
            }
        }
        val emails = HashMap<Long, MutableList<String>>()
        cr.safeQuery(Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS))?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(1) ?: continue
                emails.getOrPut(c.getLong(0)) { ArrayList(1) } += a
            }
        }
        val out = ArrayList<ContactSummary>()
        cr.safeQuery(
            Contacts.CONTENT_URI,
            arrayOf(
                Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.DISPLAY_NAME_ALTERNATIVE,
                Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED, Contacts.PHONETIC_NAME,
            ),
            sort = Contacts.SORT_KEY_PRIMARY + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(2)?.takeIf { it.isNotBlank() }
                    ?: phones[id]?.firstOrNull()?.number
                    ?: emails[id]?.firstOrNull()
                    ?: continue
                out += ContactSummary(
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
        return out
    }

    /** Fast indexed lookup used on incoming calls. */
    fun lookup(number: String): CallerInfo? {
        if (number.isBlank() || !Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return cr.safeQuery(
            uri,
            arrayOf(
                PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI,
                PhoneLookup.TYPE, PhoneLookup.LABEL, PhoneLookup.CUSTOM_RINGTONE, PhoneLookup.SEND_TO_VOICEMAIL,
            ),
        )?.use { c ->
            if (!c.moveToFirst()) return null
            CallerInfo(
                contactId = c.getLong(0),
                lookupKey = c.getString(1),
                name = c.getString(2) ?: number,
                photoUri = c.getString(3),
                numberLabel = Phone.getTypeLabel(context.resources, c.getInt(4), c.getString(5))?.toString(),
                customRingtone = c.getString(6),
                sendToVoicemail = c.getInt(7) != 0,
            )
        }
    }

    /**
     * Whether [number] belongs to a contact: true / false, or null when it couldn't be checked (no permission,
     * provider busy or failing). Screening must treat null as "maybe a contact" so a real contact is never blocked.
     */
    fun isContact(number: String): Boolean? {
        if (number.isBlank()) return false
        if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            cr.query(uri, arrayOf(PhoneLookup._ID), null, null, null)?.use { it.count > 0 }
        } catch (_: Exception) {
            null
        }
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

    private fun writableTypes(): Set<String> = try {
        ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .map { it.accountType }.toSet() - IGNORED_ACCOUNT_TYPES
    } catch (_: Exception) {
        emptySet()
    }

    private fun isWritable(a: AccountRef, types: Set<String>, local: AccountRef): Boolean =
        a.type == null || a.type == local.type || a.type in types

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
        cr.safeQuery(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0",
            arrayOf(contactId.toString()),
        )?.use { c ->
            while (c.moveToNext()) raws += RawContactRef(c.getLong(0), AccountRef(c.getString(1), c.getString(2)))
        }
        val types = writableTypes()
        val local = localAccount()
        val writable = raws.filter { isWritable(it.account, types, local) }
        val target = preferRaw?.let { p -> writable.firstOrNull { it.id == p } }
            ?: writable.firstOrNull { it.account.type == "com.google" } ?: writable.firstOrNull { !it.account.isLocal } ?: writable.firstOrNull()
        base = base.copy(rawContacts = raws, editRawId = target?.id, writableRawIds = writable.map { it.id })
        if (forEdit && target == null) {
            // Nothing writable: start from the aggregated name only; save() adds a linked device entry.
            val shown = load(contactId, forEdit = false) ?: return@withContext null
            return@withContext base.copy(given = shown.given, family = shown.family, middle = shown.middle, prefix = shown.prefix, suffix = shown.suffix)
        }

        val phones = ArrayList<DataItem>()
        val emails = ArrayList<DataItem>()
        val sites = ArrayList<DataItem>()
        val relations = ArrayList<DataItem>()
        val addrs = ArrayList<PostalItem>()
        val events = ArrayList<EventItem>()
        val groups = HashSet<Long>()
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
                fun s(i: Int) = c.getString(i).orEmpty()
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (base.nameId == null) base = base.copy(
                        nameId = id, given = s(3), family = s(4), prefix = s(5), middle = s(6), suffix = s(7),
                        phoneticGiven = s(8), phoneticFamily = s(10),
                    )
                    Nickname.CONTENT_ITEM_TYPE -> if (base.nicknameId == null) base = base.copy(nicknameId = id, nickname = s(2))
                    Organization.CONTENT_ITEM_TYPE -> if (base.orgId == null) base = base.copy(orgId = id, company = s(2), title = s(5))
                    Note.CONTENT_ITEM_TYPE -> if (base.noteId == null) base = base.copy(noteId = id, note = s(2))
                    Phone.CONTENT_ITEM_TYPE -> phones += DataItem(id, s(2), c.getInt(3), c.getString(4), c.getInt(12) != 0)
                    Email.CONTENT_ITEM_TYPE -> emails += DataItem(id, s(2), c.getInt(3), c.getString(4))
                    Website.CONTENT_ITEM_TYPE -> sites += DataItem(id, s(2), c.getInt(3), c.getString(4))
                    Relation.CONTENT_ITEM_TYPE -> relations += DataItem(id, s(2), c.getInt(3), c.getString(4))
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        var p = PostalItem(
                            id, street = s(5), city = s(8), region = s(9), postcode = s(10), country = s(11).ifEmpty { "" },
                            type = c.getInt(3), label = c.getString(4),
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
            phones = if (forEdit) phones else phones.distinctBy { PhoneNumbers.matchKey(it.value) + it.type },
            emails = emails, websites = sites, relations = relations, addresses = addrs, events = events, groupIds = groups,
        )
    }

    fun accounts(): List<AccountRef> {
        val set = LinkedHashSet<AccountRef>()
        set += AccountRef(null, null)
        try {
            val contactTypes = ContentResolver.getSyncAdapterTypes()
                .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
                .map { it.accountType }.toSet()
            AccountManager.get(context).accounts.filter { it.type in contactTypes }.forEach { set += AccountRef(it.type, it.name) }
        } catch (_: Exception) {
        }
        cr.safeQuery(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED}=0")?.use { c ->
            while (c.moveToNext()) {
                val t = c.getString(0)
                if (t != null && t !in IGNORED_ACCOUNT_TYPES) set += AccountRef(t, c.getString(1))
            }
        }
        return set.toList()
    }

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

    fun groups(): List<GroupInfo> {
        val out = ArrayList<GroupInfo>()
        cr.safeQuery(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME, Groups.SYSTEM_ID, Groups.AUTO_ADD),
            "${Groups.DELETED}=0",
            sort = Groups.TITLE + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(4) != null || c.getInt(5) != 0) continue
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                out += GroupInfo(c.getLong(0), title, AccountRef(c.getString(2), c.getString(3)))
            }
        }
        return out
    }

    fun contactIdsInGroup(groupId: Long): Set<Long> {
        val ids = HashSet<Long>()
        cr.safeQuery(
            Data.CONTENT_URI, arrayOf(Data.CONTACT_ID),
            "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
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
     * Saves [edited]. When [original] is null a new raw contact is created in [account].
     * Returns the aggregate contact id.
     */
    suspend fun save(original: ContactDetails?, edited: ContactDetails, account: AccountRef?, photo: Uri?, removePhoto: Boolean): Long? =
        withContext(Dispatchers.IO) {
            if (original != null && original.id > 0) journal(listOf(original.id), "EDIT")
            val ops = ArrayList<ContentProviderOperation>()
            val rawId: Long?
            val insertTarget: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder
            val linkTo: List<Long> = if (original != null && original.editRawId == null) original.rawContacts.map { it.id } else emptyList()
            if (original == null || original.editRawId == null) {
                val acc = if (original == null) account ?: localAccount() else localAccount()
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
            fun insert(mime: String, values: ContentValues) {
                ops += insertTarget(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                    .withValue(Data.MIMETYPE, mime).withValues(values).build()
                changed += fieldName(mime)
            }
            fun update(id: Long, mime: String, values: ContentValues) {
                ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, id)).withValues(values).build()
                changed += fieldName(mime)
            }
            fun delete(id: Long, mime: String) {
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

            val keepAddr = edited.addresses.mapNotNull { it.id }.toSet()
            val addrBefore = original?.addresses.orEmpty().filter { it.id != null }.associateBy { it.id }
            original?.addresses.orEmpty().filter { it.id != null && it.id !in keepAddr }.forEach { delete(it.id!!, StructuredPostal.CONTENT_ITEM_TYPE) }
            edited.addresses.forEach { a ->
                val v = ContentValues().apply {
                    put(StructuredPostal.STREET, a.street.trim())
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
                    listOf(prev.street, prev.city, prev.region, prev.postcode, prev.country).map(::t) == listOf(a.street, a.city, a.region, a.postcode, a.country).map(::t)
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

            val results = if (ops.isEmpty()) emptyArray<android.content.ContentProviderResult>() else cr.applyBatch(ContactsContract.AUTHORITY, ops)
            val finalRawId = rawId ?: results.firstOrNull()?.uri?.let { ContentUris.parseId(it) } ?: return@withContext null
            if (linkTo.isNotEmpty()) setAggregation(linkTo + finalRawId, AggregationExceptions.TYPE_KEEP_TOGETHER)
            if (photo != null) writePhoto(finalRawId, photo)
            val contactId = contactIdForRaw(finalRawId)
            // Remember what Parley wrote (and the version it left), for "Why did this change?" and the journal.
            runCatching {
                val key = contactId?.let { id -> cr.safeQuery(ContentUris.withAppendedId(Contacts.CONTENT_URI, id), arrayOf(Contacts.LOOKUP_KEY))?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
                writeLog.version(cr, finalRawId)?.let { v -> writeLog.record(finalRawId, key ?: original?.lookupKey.orEmpty(), v, changed.toList()) }
            }
            contactId
        }

    /** Parley's own saves, per raw contact ("Why did this change?"). */
    val writeLog by lazy { app.parley.data.people.ParleyWriteLog(context) }

    private fun fieldName(mime: String): String = when (mime) {
        StructuredName.CONTENT_ITEM_TYPE -> "Name"
        Nickname.CONTENT_ITEM_TYPE -> "Nickname"
        Organization.CONTENT_ITEM_TYPE -> "Company"
        Note.CONTENT_ITEM_TYPE -> "Note"
        Phone.CONTENT_ITEM_TYPE -> "Phone"
        Email.CONTENT_ITEM_TYPE -> "Email"
        Website.CONTENT_ITEM_TYPE -> "Website"
        Relation.CONTENT_ITEM_TYPE -> "Relation"
        Event.CONTENT_ITEM_TYPE -> "Dates"
        StructuredPostal.CONTENT_ITEM_TYPE -> "Address"
        GroupMembership.CONTENT_ITEM_TYPE -> "Labels"
        else -> "Other"
    }

    private fun localAccount(): AccountRef {
        if (Build.VERSION.SDK_INT >= 35) {
            val type = RawContacts.getLocalAccountType(context)
            val name = RawContacts.getLocalAccountName(context)
            if (type != null) return AccountRef(type, name)
        }
        return AccountRef(null, null)
    }

    private fun contactIdForRaw(rawId: Long): Long? =
        cr.safeQuery(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID))?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun writePhoto(rawId: Long, source: Uri) {
        val bytes = cr.openInputStream(source)?.use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return
            val max = 720
            val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        } ?: return
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        cr.openAssetFileDescriptor(uri, "rw")?.use { fd -> fd.createOutputStream().use { it.write(bytes) } }
    }

    suspend fun setStarred(contactId: Long, starred: Boolean) = updateContact(contactId, ContentValues().apply { put(Contacts.STARRED, if (starred) 1 else 0) })

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

    private fun rawIds(contactId: Long): List<Long> =
        cr.safeQuery(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()))
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    /** Merges several contacts into one (platform "join"). */
    suspend fun join(contactIds: List<Long>) = withContext(Dispatchers.IO) {
        journal(contactIds, "MERGE")
        val raws = contactIds.flatMap { rawIds(it) }.distinct()
        setAggregation(raws, AggregationExceptions.TYPE_KEEP_TOGETHER)
    }

    /** Splits a merged contact back into its raw contacts. */
    suspend fun separate(contactId: Long) = withContext(Dispatchers.IO) {
        journal(listOf(contactId), "SEPARATE")
        setAggregation(rawIds(contactId), AggregationExceptions.TYPE_KEEP_SEPARATE)
    }

    private fun setAggregation(raws: List<Long>, type: Int) {
        if (raws.size < 2) return
        val ops = ArrayList<ContentProviderOperation>()
        for (i in raws.indices) for (j in i + 1 until raws.size) {
            ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                .withValue(AggregationExceptions.TYPE, type)
                .withValue(AggregationExceptions.RAW_CONTACT_ID1, raws[i])
                .withValue(AggregationExceptions.RAW_CONTACT_ID2, raws[j])
                .build()
        }
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
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
        ops.chunked(300).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
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

    companion object {
        private val IGNORED_ACCOUNT_TYPES = setOf("com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.viber.voip")
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
