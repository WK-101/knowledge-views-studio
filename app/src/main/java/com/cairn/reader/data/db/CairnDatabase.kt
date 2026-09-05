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
        ItemCollectionCrossRef::class,
        HighlightEntity::class,
        ItemFtsEntity::class,
        TombstoneEntity::class,
        SyncOpEntity::class,
        RuleEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class CairnDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun sourceDao(): SourceDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun highlightDao(): HighlightDao
    abstract fun syncDao(): SyncDao
    abstract fun ruleDao(): RuleDao
    abstract fun insightsDao(): InsightsDao
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

/** v3.62: a discussion/comments URL on items (RSS <comments>). Nullable. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN commentsUrl TEXT")
    }
}

/** v3.64: per-feed mute. Non-null with a default, so existing feeds are unmuted. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3.67: broken-link watchdog columns on items, plus the item↔collection join table for the
 *  many-to-many "item in multiple collections" feature. All additive. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN linkStatus TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN linkCheckedAt INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS item_collections (" +
                "itemId TEXT NOT NULL, collectionId TEXT NOT NULL, " +
                "PRIMARY KEY(itemId, collectionId), " +
                "FOREIGN KEY(itemId) REFERENCES items(id) ON DELETE CASCADE, " +
                "FOREIGN KEY(collectionId) REFERENCES collections(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_item_collections_collectionId ON item_collections(collectionId)")
        // Seed the join table from the existing single-collection column so nothing moves.
        db.execSQL("INSERT OR IGNORE INTO item_collections (itemId, collectionId) SELECT id, collectionId FROM items WHERE collectionId IS NOT NULL")
    }
}

/** v3.68: the on-device rules / automation engine. A single table; conditions and actions are
 *  JSON columns so the rule shape can evolve without further migrations. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS rules (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                "enabled INTEGER NOT NULL DEFAULT 1, matchAll INTEGER NOT NULL DEFAULT 1, " +
                "conditionsJson TEXT NOT NULL, actionsJson TEXT NOT NULL, " +
                "stopAfter INTEGER NOT NULL DEFAULT 0, sortOrder INTEGER NOT NULL DEFAULT 0, " +
                "createdAt INTEGER NOT NULL)"
        )
    }
}

/** v3.74: WebSub hub URL on feeds (real-time-aware). Nullable, additive. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN hubUrl TEXT")
    }
}
