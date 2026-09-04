package com.cairn.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = false,
)
abstract class CairnDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun sourceDao(): SourceDao
    abstract fun tagDao(): TagDao
    abstract fun highlightDao(): HighlightDao
    abstract fun syncDao(): SyncDao
}
