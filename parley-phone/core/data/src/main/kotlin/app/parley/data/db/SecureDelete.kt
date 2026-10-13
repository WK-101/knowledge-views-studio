package app.parley.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Turns on SQLite's `secure_delete` for Parley's database: a deleted row (a call note, a remembered SIM, a private
 * contact, a private call) is overwritten with zeros instead of lingering in the file's free pages until they are
 * reused. The setting belongs to a connection; every delete runs on the database's one writing connection, which a
 * transaction always holds, so it is set there (and kept for as long as the connection is open). The cost is a little
 * more writing per delete, and Parley's database sees few deletes.
 */
object SecureDelete : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        turnOn(db)
    }

    fun turnOn(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.query("PRAGMA secure_delete = ON").close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Whether the writing connection has it on (a transaction holds that connection). */
    fun isOn(db: SupportSQLiteDatabase): Boolean {
        db.beginTransaction()
        try {
            return db.query("PRAGMA secure_delete").use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        } finally {
            db.endTransaction()
        }
    }
}
