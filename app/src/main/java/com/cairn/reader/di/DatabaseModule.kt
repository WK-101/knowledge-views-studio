package com.cairn.reader.di

import android.content.Context
import androidx.room.Room
import com.cairn.reader.data.db.CairnDatabase
import com.cairn.reader.data.db.CollectionDao
import com.cairn.reader.data.db.HighlightDao
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.MIGRATION_1_2
import com.cairn.reader.data.db.MIGRATION_2_3
import com.cairn.reader.data.db.MIGRATION_3_4
import com.cairn.reader.data.db.MIGRATION_4_5
import com.cairn.reader.data.db.MIGRATION_5_6
import com.cairn.reader.data.db.MIGRATION_6_7
import com.cairn.reader.data.db.MIGRATION_8_9
import com.cairn.reader.data.db.MIGRATION_9_10
import com.cairn.reader.data.db.MIGRATION_7_8
import com.cairn.reader.data.db.SourceDao
import com.cairn.reader.data.db.SyncDao
import com.cairn.reader.data.db.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CairnDatabase =
        Room.databaseBuilder(context, CairnDatabase::class.java, "cairn.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideItemDao(db: CairnDatabase): ItemDao = db.itemDao()

    @Provides
    fun provideSourceDao(db: CairnDatabase): SourceDao = db.sourceDao()

    @Provides
    fun provideTagDao(db: CairnDatabase): TagDao = db.tagDao()

    @Provides
    fun provideCollectionDao(db: CairnDatabase): CollectionDao = db.collectionDao()

    @Provides
    fun provideHighlightDao(db: CairnDatabase): HighlightDao = db.highlightDao()

    @Provides
    fun provideSyncDao(db: CairnDatabase): SyncDao = db.syncDao()
}
