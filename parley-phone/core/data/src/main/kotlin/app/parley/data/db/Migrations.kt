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
        }
    }

    val ALL: Array<Migration> = arrayOf(V10_TO_11)
}
