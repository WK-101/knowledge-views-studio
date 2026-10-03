package app.parley.data.testing

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.robolectric.Robolectric

/**
 * A small Contacts Provider for Robolectric tests, backed by an in-memory SQLite database with the provider's own
 * column names, so Parley's real queries, selections and batches run unchanged. It keeps one aggregate per raw
 * contact (the contact id is the first raw contact's id unless a test sets another), ignores aggregation exceptions
 * and photos, and records every write in [writes] so a test can check exactly which rows a save touched. Like the
 * real provider, every change bumps the raw contact's version and the contact's last-updated time.
 *
 * Two behaviours of the real provider that Parley must cope with are kept:
 * - Lookup keys change ([changeLookupKey], as after a first sync or a rename), and an old key still resolves to the
 *   contact that now holds its raw contacts.
 * - Deleting a raw contact of a synced account only marks it `deleted = 1` until the sync adapter runs ([purgeDeleted]);
 *   such rows stay visible through the raw_contacts URI. Phone-only raw contacts (no account) go at once.
 */
class FakeContactsProvider : ContentProvider() {
    /** One write the provider received: "insert", "update" or "delete", the path and the values. */
    data class Write(val kind: String, val path: String, val values: Map<String, Any?> = emptyMap(), val selection: String? = null)

    val writes = ArrayList<Write>()

    /**
     * When set, a new raw contact (without aggregation disabled) joins this contact, as Android's aggregator joins a
     * contact with the same name and number: another account's copy, or a messenger's.
     */
    @Volatile var joinNewRawsInto: Long? = null

    /** While true, contacts are listed without a lookup key, as right after an insert before aggregation settles. */
    @Volatile var lookupKeysUnreadable = false

    /**
     * While true, a new raw contact in the phone-only account (no account type) is refused, as Android 16 does while
     * the user's default account for new contacts is a cloud account.
     */
    @Volatile var refusePhoneOnlyInserts = false

    /** Numbers the work profile's contacts hold, answered by the enterprise phone lookup only (after personal ones). */
    val workNumbers = HashSet<String>()

    private lateinit var db: SQLiteDatabase
    private val columns = HashMap<String, Set<String>>()

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        val dataCols = (1..15).joinToString(", ") { "data$it" } + ", " + (1..4).joinToString(", ") { "data_sync$it" }
        db.execSQL(
            "CREATE TABLE raw_contacts (_id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id INTEGER, account_type TEXT, account_name TEXT, " +
                "data_set TEXT, sourceid TEXT, deleted INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1, dirty INTEGER NOT NULL DEFAULT 0, " +
                "starred INTEGER NOT NULL DEFAULT 0, custom_ringtone TEXT, send_to_voicemail INTEGER NOT NULL DEFAULT 0, " +
                "aggregation_mode INTEGER NOT NULL DEFAULT 0, sync1 TEXT, sync2 TEXT, sync3 TEXT, sync4 TEXT, last_updated INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE data (_id INTEGER PRIMARY KEY AUTOINCREMENT, raw_contact_id INTEGER NOT NULL, mimetype TEXT NOT NULL, " +
                "is_primary INTEGER NOT NULL DEFAULT 0, is_super_primary INTEGER NOT NULL DEFAULT 0, is_read_only INTEGER NOT NULL DEFAULT 0, " +
                "data_version INTEGER NOT NULL DEFAULT 0, $dataCols)",
        )
        db.execSQL(
            "CREATE TABLE groups (_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, account_type TEXT, account_name TEXT, data_set TEXT, " +
                "sourceid TEXT, system_id TEXT, notes TEXT, auto_add INTEGER NOT NULL DEFAULT 0, group_is_read_only INTEGER NOT NULL DEFAULT 0, " +
                "favorites INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, group_visible INTEGER NOT NULL DEFAULT 0, " +
                "should_sync INTEGER NOT NULL DEFAULT 1, dirty INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1)",
        )
        // Indexes like the real provider's, so tests with thousands of contacts stay fast.
        db.execSQL("CREATE INDEX data_raw ON data (raw_contact_id)")
        db.execSQL("CREATE INDEX data_raw_mime ON data (raw_contact_id, mimetype)")
        db.execSQL("CREATE INDEX raw_contact ON raw_contacts (contact_id)")
        // The current key a test gave a contact, and the raw contacts each earlier key stood for.
        db.execSQL("CREATE TABLE lookup_override (contact_id INTEGER PRIMARY KEY, lookup TEXT NOT NULL)")
        db.execSQL("CREATE TABLE lookup_history (lookup TEXT NOT NULL, raw_contact_id INTEGER NOT NULL)")
        val name = "(SELECT n.data1 FROM data n JOIN raw_contacts nr ON n.raw_contact_id = nr._id WHERE nr.contact_id = r.contact_id " +
            "AND n.mimetype = '${ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE}' ORDER BY n._id LIMIT 1)"
        db.execSQL(
            "CREATE VIEW contacts AS SELECT r.contact_id AS _id, " +
                "COALESCE((SELECT o.lookup FROM lookup_override o WHERE o.contact_id = r.contact_id), 'lk' || r.contact_id) AS lookup, " +
                "$name AS display_name, $name AS display_name_alt, " +
                "NULL AS phonetic_name, NULL AS photo_uri, NULL AS photo_thumb_uri, 0 AS photo_id, MAX(r.starred) AS starred, " +
                "MAX(r.custom_ringtone) AS custom_ringtone, MAX(r.send_to_voicemail) AS send_to_voicemail, $name AS sort_key, $name AS sort_key_alt, " +
                "MIN(r._id) AS name_raw_contact_id, 0 AS pinned, 0 AS times_contacted, 0 AS last_time_contacted, 1 AS in_visible_group, " +
                "MAX(r.last_updated) AS contact_last_updated_timestamp, " +
                "EXISTS(SELECT 1 FROM data p JOIN raw_contacts pr ON p.raw_contact_id = pr._id WHERE pr.contact_id = r.contact_id " +
                "AND p.mimetype = '${Phone.CONTENT_ITEM_TYPE}') AS has_phone_number " +
                "FROM raw_contacts r WHERE r.deleted = 0 GROUP BY r.contact_id",
        )
        // Per-row subqueries rather than a join with the grouped contacts view, which SQLite would rebuild per row.
        db.execSQL(
            "CREATE VIEW data_view AS SELECT d.*, r.contact_id AS contact_id, r.account_type AS account_type, r.account_name AS account_name, " +
                "r.data_set AS data_set, r.starred AS starred, $name AS display_name, " +
                "COALESCE((SELECT o.lookup FROM lookup_override o WHERE o.contact_id = r.contact_id), 'lk' || r.contact_id) AS lookup, NULL AS photo_uri " +
                "FROM data d JOIN raw_contacts r ON d.raw_contact_id = r._id WHERE r.deleted = 0",
        )
        for (t in listOf("raw_contacts", "data", "groups")) {
            columns[t] = db.rawQuery("PRAGMA table_info($t)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        }
        return true
    }

    private fun segments(uri: Uri) = uri.pathSegments

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val s = segments(uri)
        val sort = sortOrder?.replace(" COLLATE LOCALIZED", "")
        fun q(table: String, extra: String? = null): Cursor {
            val where = listOfNotNull(selection?.let { "($it)" }, extra).joinToString(" AND ").ifEmpty { null }
            return db.query(table, projection, where, selectionArgs, null, null, sort)
        }
        return when (s.firstOrNull()) {
            "contacts" -> when {
                s.size == 1 -> q("contacts")
                s[1] == "lookup" -> q("contacts", "_id = ${resolveLookup(s[2], s.getOrNull(3)?.toLongOrNull()) ?: -1}")
                else -> q("contacts", "_id = ${s[1].toLong()}")
            }.let { if (lookupKeysUnreadable) withoutLookup(it) else it }
            "raw_contacts" -> if (s.size == 1) q("raw_contacts") else q("raw_contacts", "_id = ${s[1].toLong()}")
            "data" -> when {
                s.size == 1 -> q("data_view")
                s[1] == "phones" -> q("data_view", "mimetype = '${Phone.CONTENT_ITEM_TYPE}'")
                s[1] == "emails" -> q("data_view", "mimetype = '${Email.CONTENT_ITEM_TYPE}'")
                else -> q("data_view", "_id = ${s[1].toLong()}")
            }
            "groups" -> if (s.size == 1) q("groups") else q("groups", "_id = ${s[1].toLong()}")
            "phone_lookup" -> phoneLookup(s.getOrNull(1).orEmpty(), projection, work = false)
            "phone_lookup_enterprise" -> phoneLookup(s.getOrNull(1).orEmpty(), projection, work = true)
            else -> MatrixCursor(projection ?: emptyArray())
        }
    }

    /** [c] with every "lookup" value null. */
    private fun withoutLookup(c: Cursor): Cursor = c.use {
        MatrixCursor(it.columnNames).apply {
            while (it.moveToNext()) {
                addRow(Array<Any?>(it.columnCount) { i -> if (it.getColumnName(i) == "lookup" || it.isNull(i)) null else it.getString(i) })
            }
        }
    }

    /**
     * Like the real provider: a key with the contact's id is that contact when the key is still its own; otherwise
     * the key is resolved to the contact that holds the raw contacts it stood for.
     */
    private fun resolveLookup(key: String, id: Long?): Long? {
        fun one(sql: String, vararg args: String): Long? = db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (id != null && one("SELECT _id FROM contacts WHERE _id = ? AND lookup = ?", id.toString(), key) != null) return id
        one("SELECT _id FROM contacts WHERE lookup = ?", key)?.let { return it }
        return one(
            "SELECT r.contact_id FROM lookup_history h JOIN raw_contacts r ON r._id = h.raw_contact_id " +
                "WHERE h.lookup = ? AND r.deleted = 0 ORDER BY r._id LIMIT 1",
            key,
        )
    }

    private fun digits(n: String) = n.filter { it.isDigit() }

    /** Same line when the last 7 digits agree, which is what the platform's loose comparison amounts to here. */
    private fun sameLine(a: String, b: String): Boolean {
        val x = digits(a)
        val y = digits(b)
        return x.isNotEmpty() && y.isNotEmpty() && x.takeLast(7) == y.takeLast(7)
    }

    private fun phoneLookup(number: String, projection: Array<out String>?, work: Boolean): Cursor {
        val cols = projection ?: arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME)
        val out = MatrixCursor(cols)
        if (work) {
            // Like the platform: personal contacts come first, then the work profile's.
            val personal = phoneLookup(number, projection, work = false)
            if (personal.count > 0) return personal
            if (workNumbers.any { sameLine(it, number) }) out.addRow(cols.map { c -> if (c == "_id") 1_000_000_001L /* past the enterprise id base */ else null })
            return out
        }
        db.query("data_view", null, "mimetype = ?", arrayOf(Phone.CONTENT_ITEM_TYPE), null, null, "_id").use { c ->
            while (c.moveToNext()) {
                val n = c.getString(c.getColumnIndexOrThrow("data1")) ?: continue
                if (!sameLine(n, number)) continue
                val row = mapOf(
                    "_id" to c.getLong(c.getColumnIndexOrThrow("contact_id")),
                    "contact_id" to c.getLong(c.getColumnIndexOrThrow("contact_id")),
                    "display_name" to c.getString(c.getColumnIndexOrThrow("display_name")),
                    "lookup" to c.getString(c.getColumnIndexOrThrow("lookup")),
                    "starred" to c.getInt(c.getColumnIndexOrThrow("starred")),
                    "number" to n,
                    "type" to c.getInt(c.getColumnIndexOrThrow("data2")),
                    "label" to c.getString(c.getColumnIndexOrThrow("data3")),
                )
                val ringtone = db.rawQuery("SELECT custom_ringtone FROM contacts WHERE _id = ?", arrayOf(row["_id"].toString())).use { r -> if (r.moveToFirst()) r.getString(0) else null }
                out.addRow(cols.map { col -> if (col == "custom_ringtone") ringtone else row[col] })
                break
            }
        }
        return out
    }

    private fun known(table: String, values: ContentValues?): ContentValues {
        val v = ContentValues()
        values?.keySet()?.filter { it in columns.getValue(table) }?.forEach { k -> putAny(v, k, values.get(k)) }
        return v
    }

    private fun putAny(v: ContentValues, k: String, x: Any?) = when (x) {
        null -> v.putNull(k)
        is String -> v.put(k, x)
        is Long -> v.put(k, x)
        is Int -> v.put(k, x)
        is Boolean -> v.put(k, if (x) 1 else 0)
        is ByteArray -> v.put(k, x)
        is Double -> v.put(k, x)
        is Float -> v.put(k, x)
        is Short -> v.put(k, x)
        is Byte -> v.put(k, x)
        else -> v.put(k, x.toString())
    }

    private fun record(kind: String, uri: Uri, values: ContentValues? = null, selection: String? = null) {
        writes += Write(kind, uri.path.orEmpty().trimStart('/'), values?.keySet()?.associateWith { values.get(it) }.orEmpty(), selection)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        record("insert", uri, values)
        return when (segments(uri).firstOrNull()) {
            "raw_contacts" -> {
                require(!refusePhoneOnlyInserts || values?.getAsString("account_type") != null) {
                    "Cannot add contacts to local or SIM accounts when default account is set to cloud"
                }
                val id = db.insertOrThrow("raw_contacts", null, known("raw_contacts", values))
                val join = joinNewRawsInto?.takeIf { values?.getAsInteger("aggregation_mode") != ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED }
                if (values?.containsKey("contact_id") != true) db.execSQL("UPDATE raw_contacts SET contact_id = ${join ?: id} WHERE _id = $id")
                touchRaw(id)
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id)
            }
            "data" -> {
                val id = db.insertOrThrow("data", null, known("data", values))
                touchRaw(values?.getAsLong("raw_contact_id"))
                ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
            }
            "groups" -> ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, db.insertOrThrow("groups", null, known("groups", values)))
            else -> null
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        record("update", uri, values, selection)
        val s = segments(uri)
        fun where(idCol: String) = if (s.size > 1) "$idCol = ${s[1].toLong()}" else selection
        return when (s.firstOrNull()) {
            "data" -> db.update("data", known("data", values), where("_id"), if (s.size > 1) null else selectionArgs).also { bump(where("_id"), selectionArgs.takeIf { s.size == 1 }) }
            "raw_contacts" -> db.update("raw_contacts", known("raw_contacts", values), where("_id"), if (s.size > 1) null else selectionArgs)
                .also { n -> if (n > 0 && values?.containsKey("version") != true) touchWhere(where("_id"), selectionArgs.takeIf { s.size == 1 }) }
            "contacts" -> db.update("raw_contacts", known("raw_contacts", values), where("contact_id"), if (s.size > 1) null else selectionArgs)
                .also { n -> if (n > 0) touchWhere(where("contact_id"), selectionArgs.takeIf { s.size == 1 }) }
            "groups" -> db.update("groups", known("groups", values), where("_id"), if (s.size > 1) null else selectionArgs)
            else -> 1 // aggregation exceptions and the like: recorded only
        }
    }

    /**
     * Editing a data row bumps its raw contact's version and the contact's last-updated time, as the real provider
     * does (so do inserting and deleting rows, and changing the raw contact itself).
     */
    private fun bump(where: String?, args: Array<out String>?) {
        touchWhere("_id IN (SELECT raw_contact_id FROM data" + (where?.let { " WHERE $it" } ?: "") + ")", args)
    }

    private fun touchWhere(where: String?, args: Array<out String>?) {
        touch("UPDATE raw_contacts SET version = version + 1, last_updated = ? WHERE " + (where ?: "1"), args)
    }

    private fun touchRaw(rawId: Long?) {
        if (rawId != null) touch("UPDATE raw_contacts SET version = version + 1, last_updated = ? WHERE _id = $rawId", null)
    }

    /** A strictly increasing "now", so two changes in the same millisecond still differ. */
    private var clock = System.currentTimeMillis()

    private fun touch(sql: String, args: Array<out String>?) {
        db.execSQL(sql, (listOf<Any>(++clock) + args.orEmpty()).toTypedArray())
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        record("delete", uri, selection = selection)
        val s = segments(uri)
        val syncAdapter = uri.getBooleanQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, false)
        return when (s.firstOrNull()) {
            "data" -> {
                val where = if (s.size > 1) "_id = ${s[1].toLong()}" else selection
                val args = if (s.size > 1) null else selectionArgs
                bump(where, args)
                db.delete("data", where, args)
            }
            "raw_contacts" -> {
                val raw = s.getOrNull(1)?.toLong()
                val where = if (raw != null) "_id = $raw" else selection
                val args = if (raw != null) null else selectionArgs
                deleteRaws(where, args, syncAdapter)
            }
            "contacts" -> {
                val id = s.getOrNull(1)?.toLong() ?: return 0
                deleteRaws("contact_id = $id", null, syncAdapter)
            }
            "groups" -> if (s.size > 1) db.delete("groups", "_id = ${s[1].toLong()}", null) else db.delete("groups", selection, selectionArgs)
            else -> 0
        }
    }

    /** Synced accounts' raw contacts are only marked deleted (unless the sync adapter asks); the others go now. */
    private fun deleteRaws(where: String?, args: Array<out String>?, syncAdapter: Boolean): Int {
        val w = where?.let { "($it)" } ?: "1"
        val a = args?.toList()?.toTypedArray() ?: emptyArray()
        var n = 0
        if (!syncAdapter) {
            n += db.compileStatement("UPDATE raw_contacts SET deleted = 1, dirty = 1 WHERE $w AND deleted = 0 AND account_type IS NOT NULL").apply {
                a.forEachIndexed { i, v -> bindString(i + 1, v) }
            }.executeUpdateDelete()
        }
        val hard = if (syncAdapter) w else "$w AND account_type IS NULL"
        db.delete("data", "raw_contact_id IN (SELECT _id FROM raw_contacts WHERE $hard)", a)
        n += db.delete("raw_contacts", hard, a)
        return n
    }

    /** What the sync adapter does after uploading deletions: rows marked deleted go for good. */
    fun purgeDeleted() {
        db.execSQL("DELETE FROM data WHERE raw_contact_id IN (SELECT _id FROM raw_contacts WHERE deleted = 1)")
        db.execSQL("DELETE FROM raw_contacts WHERE deleted = 1")
    }

    /**
     * Gives [contactId] a new lookup key, as a first sync, a rename of a phone-only contact or a relink does. The old
     * key keeps resolving to whichever contact holds its raw contacts.
     */
    fun changeLookupKey(contactId: Long, newKey: String) {
        val old = db.rawQuery("SELECT lookup FROM contacts WHERE _id = ?", arrayOf(contactId.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (old != null) {
            db.execSQL("INSERT INTO lookup_history (lookup, raw_contact_id) SELECT ?, _id FROM raw_contacts WHERE contact_id = ?", arrayOf(old, contactId))
        }
        db.execSQL("INSERT OR REPLACE INTO lookup_override (contact_id, lookup) VALUES (?, ?)", arrayOf(contactId, newKey))
    }

    /**
     * Moves raw contact [rawId] to the contact [contactId] (a link done by another app). The key of the contact it
     * left keeps resolving through that raw contact.
     */
    fun moveRaw(rawId: Long, contactId: Long) {
        db.execSQL(
            "INSERT INTO lookup_history (lookup, raw_contact_id) " +
                "SELECT c.lookup, r._id FROM raw_contacts r JOIN contacts c ON c._id = r.contact_id WHERE r._id = ?",
            arrayOf(rawId),
        )
        db.execSQL("UPDATE raw_contacts SET contact_id = ? WHERE _id = ?", arrayOf(contactId, rawId))
    }

    override fun getType(uri: Uri): String? = null

    /** Rows of [table] ("raw_contacts", "data", "groups"), each as column → value, for assertions. */
    fun rows(table: String, where: String? = null): List<Map<String, Any?>> =
        db.query(table, null, where, null, null, null, "_id").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).associate { i -> c.getColumnName(i) to if (c.getType(i) == Cursor.FIELD_TYPE_NULL) null else c.getString(i) })
            }
        }

    /** Runs raw SQL against the fake's tables (e.g. to mark a row read-only as a sync adapter would). */
    fun exec(sql: String) = db.execSQL(sql)

    companion object {
        /** Creates the provider and registers it for the contacts authority in the Robolectric application. */
        fun install(): FakeContactsProvider =
            Robolectric.buildContentProvider(FakeContactsProvider::class.java).create(ContactsContract.AUTHORITY).get()
    }
}
