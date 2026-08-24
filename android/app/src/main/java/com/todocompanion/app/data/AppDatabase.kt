package com.todocompanion.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.todocompanion.app.data.dao.ChecklistDao
import com.todocompanion.app.data.dao.ContextDao
import com.todocompanion.app.data.dao.DependencyDao
import com.todocompanion.app.data.dao.FilterDao
import com.todocompanion.app.data.dao.FolderDao
import com.todocompanion.app.data.dao.ListDao
import com.todocompanion.app.data.dao.WorkspaceDao
import com.todocompanion.app.data.dao.ReminderDao
import com.todocompanion.app.data.dao.SettingDao
import com.todocompanion.app.data.dao.TagDao
import com.todocompanion.app.data.dao.TaskDao
import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.SettingEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.WorkspaceEntity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkspaceEntity::class,
        FilterEntity::class,
        FolderEntity::class,
        ListEntity::class,
        TaskEntity::class,
        ChecklistItemEntity::class,
        TagEntity::class,
        TaskTagCrossRef::class,
        ContextEntity::class,
        TaskContextCrossRef::class,
        ReminderEntity::class,
        DependencyEntity::class,
        SettingEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun filterDao(): FilterDao
    abstract fun folderDao(): FolderDao
    abstract fun listDao(): ListDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun tagDao(): TagDao
    abstract fun contextDao(): ContextDao
    abstract fun reminderDao(): ReminderDao
    abstract fun dependencyDao(): DependencyDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v5→v6 adds the saved-filters table without wiping existing data. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `filters` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sortOrder` REAL NOT NULL, " +
                        "`workspaceId` TEXT NOT NULL, `queryJson` TEXT NOT NULL, `colorArgb` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "todocompanion.db",
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
