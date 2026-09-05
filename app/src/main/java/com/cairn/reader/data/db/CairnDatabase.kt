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
    version = 8,
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

/** v3 → v4: per-feed "mark as podcast" flag. Non-null with a default, so existing rows
 *  are backfilled to 0 (not a podcast). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN isPodcast INTEGER NOT NULL DEFAULT 0")
    }
}

/** v4 → v5: per-feed retention override. Nullable, so existing feeds keep the global cap. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN maxItems INTEGER")
    }
}

/** v5 → v6: watched-page change detection stores the last-seen content hash. Nullable. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN contentHash TEXT")
    }
}

/** v6 → v7: teach-by-example scraping stores the chosen CSS selector. Nullable. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN scrapeSelector TEXT")
    }
}

/** v3.44: the Trash. A nullable timestamp on items; non-null means soft-deleted. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN trashedAt INTEGER")
    }
}
