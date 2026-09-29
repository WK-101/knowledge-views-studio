package app.parley.data.testing

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CallLog
import org.robolectric.Robolectric

/**
 * A small call-log provider for Robolectric tests: one in-memory `calls` table with the columns Parley reads and
 * writes, so the real queries, inserts and deletes run unchanged. [failInserts] makes every insert fail, as the real
 * provider does without WRITE_CALL_LOG.
 */
class FakeCallLogProvider : ContentProvider() {
    private lateinit var db: SQLiteDatabase

    /** Every insert fails (no permission to write the call log). */
    @Volatile var failInserts = false

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE calls (_id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, date INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, " +
                "type INTEGER NOT NULL DEFAULT 0, presentation INTEGER NOT NULL DEFAULT 1, subscription_id TEXT, subscription_component_name TEXT, " +
                "name TEXT, new INTEGER NOT NULL DEFAULT 0, is_read INTEGER NOT NULL DEFAULT 0)",
        )
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        db.query("calls", projection, selection, selectionArgs, null, null, sortOrder)

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (failInserts) throw SecurityException("No permission to write the call log")
        return ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, db.insertOrThrow("calls", null, values))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        db.update("calls", values, selection, selectionArgs)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = db.delete("calls", selection, selectionArgs)

    override fun getType(uri: Uri): String? = null

    /** Every row, each as column → value, for assertions. */
    fun rows(): List<Map<String, Any?>> = db.query("calls", null, null, null, null, null, "_id").use { c ->
        buildList {
            while (c.moveToNext()) {
                add((0 until c.columnCount).associate { i -> c.getColumnName(i) to if (c.getType(i) == Cursor.FIELD_TYPE_NULL) null else c.getString(i) })
            }
        }
    }

    companion object {
        /** Creates the provider and registers it for the call-log authority in the Robolectric application. */
        fun install(): FakeCallLogProvider = Robolectric.buildContentProvider(FakeCallLogProvider::class.java).create(CallLog.AUTHORITY).get()
    }
}
