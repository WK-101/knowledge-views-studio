package app.parley.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Hand-written steps of [AppDatabase], for changes an auto-migration can't make. */
object Migrations {
    /**
     * Rebuilds the journal and vault_contacts with their large blob as the last column. SQLite stores a row's columns
     * in order and spills a large value into overflow pages, so a listing that selects a column after the blob walked
     * the blob too: History & undo read every payload, and private-contact listings every sealed detail. Every row
     * and every byte is copied as it is (ids included); only the column order changes. Also adds journal_photos, where
     * new journal copies keep their photos once by hash.
     */
    val V10_TO_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // The AUTOINCREMENT high-water marks go with the old tables: kept, so a deleted row's id is never reused
            // (per-person data is keyed by a private contact's id, and a leftover must not attach to a new contact).
            val journalSeq = sequence(db, "journal")
            val vaultSeq = sequence(db, "vault_contacts")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `journal_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contactKey` TEXT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, `action` TEXT NOT NULL, `time` INTEGER NOT NULL, `restored` INTEGER NOT NULL, " +
                    "`photoHashes` TEXT, `payload` BLOB NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO `journal_new` (`id`, `contactKey`, `displayName`, `action`, `time`, `restored`, `photoHashes`, `payload`) " +
                    "SELECT `id`, `contactKey`, `displayName`, `action`, `time`, `restored`, NULL, `payload` FROM `journal`",
            )
            db.execSQL("DROP TABLE `journal`")
            db.execSQL("ALTER TABLE `journal_new` RENAME TO `journal`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_time` ON `journal` (`time`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_contactKey` ON `journal` (`contactKey`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `journal_photos` (`hash` TEXT NOT NULL, `blob` BLOB NOT NULL, PRIMARY KEY(`hash`))")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `vault_contacts_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`callerIdBlob` BLOB NOT NULL, `expiresAt` INTEGER, `createdAt` INTEGER NOT NULL, `detailBlob` BLOB NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO `vault_contacts_new` (`id`, `callerIdBlob`, `expiresAt`, `createdAt`, `detailBlob`) " +
                    "SELECT `id`, `callerIdBlob`, `expiresAt`, `createdAt`, `detailBlob` FROM `vault_contacts`",
            )
            db.execSQL("DROP TABLE `vault_contacts`")
            db.execSQL("ALTER TABLE `vault_contacts_new` RENAME TO `vault_contacts`")
            restoreSequence(db, "journal", journalSeq)
            restoreSequence(db, "vault_contacts", vaultSeq)
        }
    }

    /** [table]'s AUTOINCREMENT high-water mark, or null when it never had a row. */
    private fun sequence(db: SupportSQLiteDatabase, table: String): Long? =
        db.query("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Sets [table]'s mark back to at least [seq] (the rebuilt table's own mark is its largest surviving id). */
    private fun restoreSequence(db: SupportSQLiteDatabase, table: String, seq: Long?) {
        if (seq == null) return
        db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = ?", arrayOf<Any>(seq, table))
        db.execSQL(
            "INSERT INTO sqlite_sequence (name, seq) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = ?)",
            arrayOf<Any>(table, seq, table),
        )
    }

    val ALL: Array<Migration> = arrayOf(V10_TO_11)
}
