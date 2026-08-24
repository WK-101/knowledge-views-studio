package com.todocompanion.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.todocompanion.app.data.dao.ChecklistDao
import com.todocompanion.app.data.dao.ContextDao
import com.todocompanion.app.data.dao.DependencyDao
import com.todocompanion.app.data.dao.FolderDao
import com.todocompanion.app.data.dao.ListDao
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
import com.todocompanion.app.data.entity.TaskTagCrossRef

@Database(
    entities = [
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
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "todocompanion.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
