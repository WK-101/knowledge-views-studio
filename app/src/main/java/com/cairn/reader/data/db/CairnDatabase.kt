package com.cairn.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        ItemEntity::class,
        ItemStateEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        CollectionEntity::class,
        HighlightEntity::class,
        ItemFtsEntity::class,
        TombstoneEntity::class,
        SyncOpEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class CairnDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun sourceDao(): SourceDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun highlightDao(): HighlightDao
    abstract fun syncDao(): SyncDao
}

/** v1 → v2: the Raindrop-style library. Adds nullable columns only, so existing
 *  feeds, items, highlights and tags are preserved untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN collectionId TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN domain TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN cacheStatus TEXT")
        db.execSQL("ALTER TABLE collections ADD COLUMN icon TEXT")
        db.execSQL("ALTER TABLE collections ADD COLUMN viewMode TEXT")
    }
}

/** v2 → v3: podcast support. Adds the nullable audio enclosure URL only. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN enclosureUrl TEXT")
    }
}
