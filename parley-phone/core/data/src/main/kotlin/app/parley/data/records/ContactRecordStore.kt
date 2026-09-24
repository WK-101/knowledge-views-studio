package app.parley.data.records

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.TransactionTooLargeException
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.util.Log
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Messengers
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.AccountRef

/** Maps a group-membership row to a group row id in [account], or null to drop the membership. */
fun interface GroupResolver {
    fun resolve(row: DataRow, account: AccountRef): Long?
}

/** Outcome of inserting one record: the new aggregate contact id, or why nothing was written. */
data class InsertResult(val contactId: Long?, val error: String? = null, val rawIds: List<Long> = emptyList())

/**
 * Reads and writes [ContactRecord]s: the lossless image of a contact (Contact -> RawContacts -> every Data
 * row, generically) used by vCard/CSV import and export and by backups.
 *
 * All methods block; call them from a background dispatcher. They need READ_CONTACTS / WRITE_CONTACTS.
 */
class ContactRecordStore(private val context: Context) {
    private val cr: ContentResolver = context.contentResolver

    data class GroupRef(val id: Long, val title: String?, val account: AccountRef, val system: Boolean)

    // ---------------------------------------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------------------------------------

    fun groups(): List<GroupRef> {
        val out = ArrayList<GroupRef>()
        query(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME, Groups.SYSTEM_ID, Groups.AUTO_ADD, Groups.GROUP_IS_READ_ONLY),
            "${Groups.DELETED}=0",
        )?.use { c ->
            while (c.moveToNext()) {
                val system = c.getString(4) != null || c.getInt(5) != 0 || c.getInt(6) != 0
                out += GroupRef(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() }, AccountRef(c.getString(2), c.getString(3)), system)
            }
        }
        return out
    }

    /**
     * Titles of user-made groups by row id. System groups ("My Contacts", "Starred in Android") are left out:
     * they are account plumbing, and every account recreates them itself.
     */
    fun groupTitles(): Map<Long, String> = groups().filter { !it.system && it.title != null }.associate { it.id to it.title!! }

    /**
     * Resolves memberships by title in the target account, creating a missing group there, or by row id when the
     * row still points at an existing group of the same account. Caches lookups for one import.
     */
    fun groupResolver(): GroupResolver {
        val all = groups()
        val byTitle = HashMap<Pair<AccountRef, String>, Long>()
        all.filter { it.title != null && !it.system }.forEach { byTitle.putIfAbsent(it.account to it.title!!.lowercase(), it.id) }
        val byId = all.associateBy { it.id }
        return GroupResolver { row, account ->
            val title = row[Col.GROUP_TITLE]?.trim()?.takeIf { it.isNotEmpty() }
            if (title != null) {
                byTitle[account to title.lowercase()] ?: createGroup(title, account)?.also { byTitle[account to title.lowercase()] = it }
            } else {
                row[Col.D1]?.toLongOrNull()?.takeIf { byId[it]?.account == account }
            }
        }
    }

    private fun createGroup(title: String, account: AccountRef): Long? = try {
        val v = ContentValues().apply {
            put(Groups.TITLE, title)
            put(Groups.ACCOUNT_TYPE, account.type)
            put(Groups.ACCOUNT_NAME, account.name)
            put(Groups.GROUP_VISIBLE, 1)
        }
        cr.insert(Groups.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not create group", e)
        null
    }

    // ---------------------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------------------

    /** Ids of every contact, in display order. */
    fun contactIds(): List<Long> =
        query(Contacts.CONTENT_URI, arrayOf(Contacts._ID), sort = Contacts.SORT_KEY_PRIMARY + " COLLATE LOCALIZED ASC")
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    fun read(contactId: Long, fullPhoto: Boolean = true): ContactRecord? = readAll(listOf(contactId), fullPhoto).firstOrNull()

    /**
     * Reads [ids] (or every contact) lazily, [BATCH] contacts per query, so a large address book is never held
     * in memory at once. Photos are full resolution (RawContacts.DisplayPhoto) when [fullPhoto] and available,
     * otherwise the DATA15 thumbnail. Group rows carry [Col.GROUP_TITLE] for user-made groups.
     */
    fun readAll(ids: List<Long>? = null, fullPhoto: Boolean = true): Sequence<ContactRecord> = sequence {
        val all = ids ?: contactIds()
        if (all.isEmpty()) return@sequence
        val titles = groupTitles()
        for (chunk in all.chunked(BATCH)) yieldAll(readChunk(chunk, titles, fullPhoto))
    }

    private fun readChunk(ids: List<Long>, titles: Map<Long, String>, fullPhoto: Boolean): List<ContactRecord> {
        class Head(val key: String, val name: String, val starred: Boolean, val ringtone: String?, val voicemail: Boolean)
        class Raw(val id: Long, val contactId: Long, val type: String?, val name: String?, val dataSet: String?, val sourceId: String?) {
            val rows = ArrayList<DataRow>()
        }
        val idList = ids.joinToString(",")
        val heads = LinkedHashMap<Long, Head>()
        query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED, Contacts.CUSTOM_RINGTONE, Contacts.SEND_TO_VOICEMAIL),
            "${Contacts._ID} IN ($idList)",
        )?.use { c ->
            while (c.moveToNext()) {
                heads[c.getLong(0)] = Head(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) != 0, c.getString(4), c.getInt(5) != 0)
            }
        }
        val raws = LinkedHashMap<Long, Raw>()
        query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.CONTACT_ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.DATA_SET, RawContacts.SOURCE_ID),
            "${RawContacts.CONTACT_ID} IN ($idList) AND ${RawContacts.DELETED}=0",
            sort = RawContacts._ID,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                raws[id] = Raw(id, c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
            }
        }
        if (raws.isNotEmpty()) {
            val projection = arrayOf(Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY, Data.DATA15) + DATA_COLUMNS
            query(Data.CONTENT_URI, projection, "${Data.RAW_CONTACT_ID} IN (${raws.keys.joinToString(",")})", sort = Data._ID)?.use { c ->
                while (c.moveToNext()) {
                    val raw = raws[c.getLong(0)] ?: continue
                    val mime = c.getString(1) ?: continue
                    val values = LinkedHashMap<String, String?>()
                    for (i in DATA_COLUMNS.indices) c.getString(5 + i)?.let { values[Col.ALL[i]] = it }
                    val blob = when (c.getType(4)) {
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(4)
                        Cursor.FIELD_TYPE_STRING -> c.getString(4)?.toByteArray(Charsets.UTF_8)
                        else -> null
                    }
                    if (mime == Mime.GROUP) values[Col.D1]?.toLongOrNull()?.let { id -> titles[id]?.let { values[Col.GROUP_TITLE] = it } }
                    raw.rows += DataRow(mime, values, blob, isPrimary = c.getInt(2) != 0, isSuperPrimary = c.getInt(3) != 0)
                }
            }
        }
        if (fullPhoto) {
            for (raw in raws.values) {
                val i = raw.rows.indexOfFirst { it.mimeType == Mime.PHOTO && it[Col.D14] != null }
                if (i < 0) continue
                displayPhoto(raw.id)?.let { raw.rows[i] = raw.rows[i].copy(blob = it) }
            }
        }
        val byContact = raws.values.groupBy { it.contactId }
        return ids.mapNotNull { id ->
            val h = heads[id] ?: return@mapNotNull null
            ContactRecord(
                key = h.key,
                displayName = h.name,
                starred = h.starred,
                customRingtone = h.ringtone,
                sendToVoicemail = h.voicemail,
                raws = byContact[id].orEmpty().map { r -> RawRecord(r.type, r.name, r.dataSet, r.sourceId, r.rows.toList(), rawId = r.id) },
            )
        }
    }

    private fun displayPhoto(rawId: Long): ByteArray? = try {
        cr.openAssetFileDescriptor(displayPhotoUri(rawId), "r")?.use { fd -> fd.createInputStream().use { it.readBytes() } }
    } catch (_: Exception) {
        null // no display photo (only a thumbnail), or it was removed meanwhile
    }

    private fun displayPhotoUri(rawId: Long): Uri =
        Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)

    // ---------------------------------------------------------------------------------------------
    // Write
    // ---------------------------------------------------------------------------------------------

    /**
     * Inserts [record] as a new contact and returns its aggregate id (null when nothing could be written).
     *
     * - [target] non-null: every raw contact is merged into one raw contact in that account.
     * - [target] null: each raw contact keeps its original account when that account exists on this device,
     *   otherwise it goes to the device account; the raws are then linked with KEEP_TOGETHER.
     *
     * Messenger raw contacts and rows (WhatsApp, Signal, Telegram...) are skipped unless [includeReadOnly].
     * Source ids are never copied: they belong to a sync adapter's server-side copy.
     */
    fun insert(record: ContactRecord, target: AccountRef?, groups: GroupResolver = groupResolver(), includeReadOnly: Boolean = false): Long? =
        insertAll(listOf(record), target, groups, includeReadOnly).single().contactId

    /**
     * Inserts many records efficiently: operations are packed into batches kept under [MAX_BATCH_BYTES] of
     * estimated parcel size, and a batch that still overflows the binder is retried per record, then per row.
     * Photos are written through RawContacts.DisplayPhoto afterwards (full resolution), and the Contacts-level
     * flags (starred, ringtone, send to voicemail) are applied once aggregation has settled.
     */
    fun insertAll(
        records: List<ContactRecord>,
        target: AccountRef?,
        groups: GroupResolver = groupResolver(),
        includeReadOnly: Boolean = false,
    ): List<InsertResult> {
        val results = arrayOfNulls<InsertResult>(records.size)
        val available = if (target == null) availableAccounts() else emptySet()
        val plans = records.mapIndexed { i, r ->
            val plan = plan(r, target, available, groups, includeReadOnly)
            if (plan.raws.isEmpty()) results[i] = InsertResult(null, "Nothing to import (only read-only messenger data)")
            plan
        }
        val batch = ArrayList<Int>()
        var bytes = 0L
        var ops = 0
        fun flush() {
            if (batch.isEmpty()) return
            applyPlans(batch.map { plans[it] }).forEachIndexed { k, res -> results[batch[k]] = res }
            batch.clear(); bytes = 0; ops = 0
        }
        plans.forEachIndexed { i, p ->
            if (p.raws.isEmpty()) return@forEachIndexed
            if (batch.isNotEmpty() && (bytes + p.bytes > MAX_BATCH_BYTES || ops + p.ops > MAX_BATCH_OPS)) flush()
            batch += i
            bytes += p.bytes
            ops += p.ops
        }
        flush()
        return results.map { it ?: InsertResult(null, "Not written") }
    }

    private class PlannedRaw(val account: AccountRef, val dataSet: String?, val rows: List<ContentValues>, val photo: ByteArray?)

    private class Plan(val record: ContactRecord, val raws: List<PlannedRaw>) {
        val ops = raws.sumOf { 1 + it.rows.size }
        val bytes = raws.sumOf { r -> OP_OVERHEAD + r.rows.sumOf { estimate(it) } }
    }

    private fun plan(r: ContactRecord, target: AccountRef?, available: Set<AccountRef>, groups: GroupResolver, includeReadOnly: Boolean): Plan {
        val sources = r.raws.filter { includeReadOnly || !Messengers.isMessengerAccount(it.accountType) }
        val grouped: List<Pair<AccountRef, List<RawRecord>>> = if (target != null) {
            if (sources.isEmpty()) emptyList() else listOf(target to sources)
        } else {
            val local = localAccount()
            sources.map { raw ->
                val a = AccountRef(raw.accountType, raw.accountName)
                (if (a.type == null || a in available) a else local) to listOf(raw)
            }
        }
        val planned = grouped.mapNotNull { (account, raws) ->
            val keepDataSet = target == null && raws.size == 1 && AccountRef(raws[0].accountType, raws[0].accountName) == account
            val seen = HashSet<String>()
            var name = false
            var photo: ByteArray? = null
            val rows = ArrayList<ContentValues>()
            for (raw in raws) for (row in raw.rows) {
                if (!includeReadOnly && Messengers.isMessengerMime(row.mimeType)) continue
                when (row.mimeType) {
                    Mime.PHOTO -> { if (photo == null) photo = row.blob?.takeIf { it.isNotEmpty() }; continue }
                    Mime.NAME -> { if (name) continue; name = true }
                }
                if (!seen.add(row.canonicalKey + "|" + row[Col.GROUP_TITLE])) continue
                val v = ContentValues()
                v.put(Data.MIMETYPE, row.mimeType)
                if (row.mimeType == Mime.GROUP) {
                    val id = groups.resolve(row, account) ?: continue
                    v.put(Data.DATA1, id)
                } else {
                    Col.ALL.forEach { col -> row[col]?.let { v.put(col, it) } }
                    row.blob?.let { v.put(Data.DATA15, it) }
                }
                if (row.isPrimary) v.put(Data.IS_PRIMARY, 1)
                if (row.isSuperPrimary) v.put(Data.IS_SUPER_PRIMARY, 1)
                rows += v
            }
            if (rows.isEmpty() && photo == null) null else PlannedRaw(account, if (keepDataSet) raws[0].dataSet else null, rows, photo)
        }
        return Plan(r, planned)
    }

    private fun rawInsert(p: PlannedRaw): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, p.account.type)
            .withValue(RawContacts.ACCOUNT_NAME, p.account.name)
            .apply { p.dataSet?.let { withValue(RawContacts.DATA_SET, it) } }

    /** Applies several plans in one transaction; on failure, isolates them one by one. */
    private fun applyPlans(plans: List<Plan>): List<InsertResult> {
        val ops = ArrayList<ContentProviderOperation>()
        val rawOpIndex = ArrayList<List<Int>>()
        for (plan in plans) {
            val idx = ArrayList<Int>()
            for (raw in plan.raws) {
                val base = ops.size
                idx += base
                ops += rawInsert(raw).build()
                raw.rows.forEach { v -> ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValues(v).withValueBackReference(Data.RAW_CONTACT_ID, base).build() }
            }
            rawOpIndex += idx
        }
        val results: Array<ContentProviderResult> = try {
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            // Nothing was committed: the whole batch runs in one transaction without yield points.
            if (plans.size > 1) return plans.flatMap { applyPlans(listOf(it)) }
            val plan = plans.single()
            return listOf(
                if (e is TransactionTooLargeException || plan.bytes > MAX_BATCH_BYTES) insertRowByRow(plan)
                else InsertResult(null, e.message ?: e.javaClass.simpleName).also { Log.w(TAG, "Insert failed", e) },
            )
        }
        return plans.mapIndexed { k, plan ->
            val rawIds = rawOpIndex[k].mapNotNull { results[it].uri?.let(ContentUris::parseId) }
            finish(plan, rawIds)
        }
    }

    /** Last resort for a contact too large for one binder transaction (e.g. hundreds of rows). */
    private fun insertRowByRow(plan: Plan): InsertResult {
        val rawIds = ArrayList<Long>()
        var failed = 0
        for (raw in plan.raws) {
            val values = ContentValues().apply {
                put(RawContacts.ACCOUNT_TYPE, raw.account.type)
                put(RawContacts.ACCOUNT_NAME, raw.account.name)
                raw.dataSet?.let { put(RawContacts.DATA_SET, it) }
            }
            val id = try {
                cr.insert(RawContacts.CONTENT_URI, values)?.let(ContentUris::parseId)
            } catch (e: Exception) {
                Log.w(TAG, "Raw insert failed", e)
                null
            } ?: continue
            rawIds += id
            for (row in raw.rows) {
                try {
                    cr.insert(Data.CONTENT_URI, ContentValues(row).apply { put(Data.RAW_CONTACT_ID, id) })
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "Row insert failed", e)
                }
            }
        }
        if (rawIds.isEmpty()) return InsertResult(null, "The contact could not be written")
        return finish(plan, rawIds).let { if (failed > 0 && it.error == null) it.copy(error = "$failed field(s) could not be written") else it }
    }

    /** Photos, aggregation and Contacts-level flags, once the raw contacts exist. */
    private fun finish(plan: Plan, rawIds: List<Long>): InsertResult {
        if (rawIds.isEmpty()) return InsertResult(null, "The contact could not be written")
        var error: String? = null
        plan.raws.zip(rawIds).forEach { (raw, id) ->
            raw.photo?.let { if (!writePhoto(id, it)) error = "The photo could not be saved" }
        }
        if (rawIds.size > 1) keepTogether(rawIds)
        val contactId = contactIdForRaw(rawIds.first()) ?: return InsertResult(null, "The contact could not be found after writing", rawIds)
        val r = plan.record
        if (r.starred || r.sendToVoicemail || r.customRingtone != null) {
            val v = ContentValues()
            if (r.starred) v.put(Contacts.STARRED, 1)
            if (r.sendToVoicemail) v.put(Contacts.SEND_TO_VOICEMAIL, 1)
            r.customRingtone?.let { v.put(Contacts.CUSTOM_RINGTONE, it) }
            try {
                cr.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), v, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set contact flags", e)
            }
        }
        return InsertResult(contactId, error, rawIds)
    }

    /**
     * Makes the writable part of an existing contact match [record] in place: rows are diffed by content,
     * unchanged rows keep their ids, removed rows are deleted from every writable raw contact and new rows go
     * to [targetRaw]. Read-only raws (messengers) and the contact's id, links and history stay untouched.
     * Returns false if nothing could be written.
     */
    fun replaceContent(contactId: Long, record: ContactRecord, targetRaw: Long, writableRaws: List<Long>, groups: GroupResolver = groupResolver()): Boolean {
        val raws = (writableRaws + targetRaw).distinct()
        val account = query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, targetRaw), arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME))
            ?.use { c -> if (c.moveToFirst()) AccountRef(c.getString(0), c.getString(1)) else null } ?: return false
        // Current rows: canonical key -> data ids (multiset).
        val current = HashMap<String, ArrayDeque<Long>>()
        query(
            Data.CONTENT_URI, arrayOf(Data._ID, Data.MIMETYPE, *DATA_COLUMNS),
            "${Data.RAW_CONTACT_ID} IN (${raws.joinToString(",")})",
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                if (Messengers.isMessengerMime(mime)) continue
                if (mime == Mime.PHOTO) continue
                val key = if (mime == Mime.GROUP) "g|" + c.getString(2) else DataRow(mime, Col.ALL.mapIndexed { i, col -> col to c.getString(2 + i) }.toMap()).canonicalKey
                current.getOrPut(key) { ArrayDeque() }.add(c.getLong(0))
            }
        }
        record.raws.firstNotNullOfOrNull { r -> r.rows.firstOrNull { it.mimeType == Mime.PHOTO }?.blob }?.let { remotePhoto ->
            val currentPhoto = read(contactId, fullPhoto = true)?.raws?.firstNotNullOfOrNull { r -> r.rows.firstOrNull { it.mimeType == Mime.PHOTO }?.blob }
            if (currentPhoto?.contentEquals(remotePhoto) != true) writePhoto(targetRaw, remotePhoto)
        }
        val ops = ArrayList<ContentProviderOperation>()
        var name = false
        val seen = HashSet<String>()
        for (raw in record.raws) {
            if (Messengers.isMessengerAccount(raw.accountType)) continue
            for (row in raw.rows) {
                if (Messengers.isMessengerMime(row.mimeType) || row.mimeType == Mime.PHOTO) continue
                if (row.mimeType == Mime.NAME) { if (name) continue; name = true }
                val v = ContentValues()
                val key = if (row.mimeType == Mime.GROUP) {
                    val id = groups.resolve(row, account) ?: continue
                    v.put(Data.DATA1, id)
                    "g|$id"
                } else {
                    Col.ALL.forEach { col -> row[col]?.let { v.put(col, it) } }
                    row.blob?.let { v.put(Data.DATA15, it) }
                    row.canonicalKey
                }
                if (!seen.add(key)) continue
                val existing = current[key]
                if (existing != null && existing.isNotEmpty()) { existing.removeFirst(); continue }
                v.put(Data.MIMETYPE, row.mimeType)
                v.put(Data.RAW_CONTACT_ID, targetRaw)
                if (row.isPrimary) v.put(Data.IS_PRIMARY, 1)
                if (row.isSuperPrimary) v.put(Data.IS_SUPER_PRIMARY, 1)
                ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValues(v).build()
            }
        }
        current.values.flatten().forEach { id -> ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, id)).build() }
        ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId))
            .withValue(Contacts.STARRED, if (record.starred) 1 else 0)
            .withValue(Contacts.SEND_TO_VOICEMAIL, if (record.sendToVoicemail) 1 else 0)
            .withValue(Contacts.CUSTOM_RINGTONE, record.customRingtone)
            .build()
        return try {
            ops.chunked(MAX_BATCH_OPS).forEach { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "In-place update failed", e)
            false
        }
    }

    /** Writes a full-resolution photo; the provider derives the display size and thumbnail. */
    private fun writePhoto(rawId: Long, bytes: ByteArray): Boolean {
        try {
            cr.openAssetFileDescriptor(displayPhotoUri(rawId), "rw")?.use { fd -> fd.createOutputStream().use { it.write(bytes) } }
            if (hasPhotoRow(rawId)) return true
        } catch (e: Exception) {
            Log.w(TAG, "Display photo write failed", e)
        }
        // Fallback: a plain photo row (the provider scales it); only for sizes a binder call can carry.
        if (bytes.size > MAX_INLINE_PHOTO) return false
        return try {
            cr.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, rawId)
                put(Data.MIMETYPE, Mime.PHOTO)
                put(Data.DATA15, bytes)
            }) != null
        } catch (e: Exception) {
            Log.w(TAG, "Photo row insert failed", e)
            false
        }
    }

    private fun hasPhotoRow(rawId: Long): Boolean =
        query(Data.CONTENT_URI, arrayOf(Data._ID), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(rawId.toString(), Mime.PHOTO))
            ?.use { it.count > 0 } ?: false

    private fun keepTogether(rawIds: List<Long>) {
        val ops = ArrayList<ContentProviderOperation>()
        for (i in rawIds.indices) for (j in i + 1 until rawIds.size) {
            ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(AggregationExceptions.RAW_CONTACT_ID1, rawIds[i])
                .withValue(AggregationExceptions.RAW_CONTACT_ID2, rawIds[j])
                .build()
        }
        try {
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.w(TAG, "Could not link raw contacts", e)
        }
    }

    private fun contactIdForRaw(rawId: Long): Long? =
        query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID))?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** Accounts that can hold contacts on this device (so a restored raw can keep its account). */
    fun availableAccounts(): Set<AccountRef> {
        val set = HashSet<AccountRef>()
        try {
            AccountManager.get(context).accounts.forEach { set += AccountRef(it.type, it.name) }
        } catch (_: SecurityException) {
        }
        query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED}=0")?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { set += AccountRef(it, c.getString(1)) }
        }
        set += localAccount()
        return set
    }

    private fun localAccount(): AccountRef {
        if (Build.VERSION.SDK_INT >= 35) {
            val type = RawContacts.getLocalAccountType(context)
            if (type != null) return AccountRef(type, RawContacts.getLocalAccountName(context))
        }
        return AccountRef(null, null)
    }

    private fun query(uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null, sort: String? = null): Cursor? =
        try {
            cr.query(uri, projection, selection, args, sort)
        } catch (e: SecurityException) {
            Log.w(TAG, "No contacts permission", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Query failed", e)
            null
        }

    companion object {
        private const val TAG = "ContactRecordStore"

        /** Contacts read per query. */
        const val BATCH = 100

        /** Keeps each applyBatch well under the 1 MB binder transaction limit. */
        const val MAX_BATCH_BYTES = 400L * 1024

        /** The provider rejects batches with too many operations between yield points. */
        const val MAX_BATCH_OPS = 400

        private const val OP_OVERHEAD = 96L
        private const val MAX_INLINE_PHOTO = 512 * 1024

        private val DATA_COLUMNS = arrayOf(
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7,
            Data.DATA8, Data.DATA9, Data.DATA10, Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14,
        )

        /** Rough parcel size of one insert: UTF-16 strings plus blobs plus per-entry overhead. */
        private fun estimate(v: ContentValues): Long {
            var n = OP_OVERHEAD
            for (key in v.keySet()) {
                n += 16 + key.length * 2L
                when (val value = v.get(key)) {
                    is ByteArray -> n += value.size
                    is String -> n += value.length * 2L
                    else -> n += 8
                }
            }
            return n
        }
    }
}
