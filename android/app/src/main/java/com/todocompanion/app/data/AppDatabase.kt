package com.todocompanion.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.todocompanion.app.data.dao.ChecklistDao
import com.todocompanion.app.data.dao.ContextDao
import com.todocompanion.app.data.dao.DependencyDao
import com.todocompanion.app.data.dao.FilterDao
import com.todocompanion.app.data.dao.HabitDao
import com.todocompanion.app.data.dao.FocusDao
import com.todocompanion.app.data.dao.FlagDao
import com.todocompanion.app.data.dao.TemplateDao
import com.todocompanion.app.data.dao.FolderDao
import com.todocompanion.app.data.dao.ListDao
import com.todocompanion.app.data.dao.WorkspaceDao
import com.todocompanion.app.data.dao.ReminderDao
import com.todocompanion.app.data.dao.SettingDao
import com.todocompanion.app.data.dao.TagDao
import com.todocompanion.app.data.dao.TaskDao
import com.todocompanion.app.data.dao.AttachmentDao
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
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.data.entity.AttachmentEntity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkspaceEntity::class,
        FilterEntity::class,
        HabitEntity::class,
        HabitCheckinEntity::class,
        FocusSessionEntity::class,
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
        AttachmentEntity::class,
        FlagEntity::class,
        TemplateEntity::class,
    ],
    version = 19,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun filterDao(): FilterDao
    abstract fun habitDao(): HabitDao
    abstract fun focusDao(): FocusDao
    abstract fun folderDao(): FolderDao
    abstract fun listDao(): ListDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun tagDao(): TagDao
    abstract fun contextDao(): ContextDao
    abstract fun reminderDao(): ReminderDao
    abstract fun dependencyDao(): DependencyDao
    abstract fun settingDao(): SettingDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun flagDao(): FlagDao
    abstract fun templateDao(): TemplateDao

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

        /** v6→v7 adds the habit + check-in tables without wiping existing data. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habits` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`emoji` TEXT, `colorArgb` INTEGER, `targetPerDay` INTEGER NOT NULL, `sortOrder` REAL NOT NULL, " +
                        "`archived` INTEGER NOT NULL, `workspaceId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habit_checkins` (`habitId` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                        "`count` INTEGER NOT NULL, PRIMARY KEY(`habitId`, `epochDay`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_checkins_habitId` ON `habit_checkins` (`habitId`)")
            }
        }

        /** v7→v8 adds the focus-sessions table without wiping existing data. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `focus_sessions` (`id` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                        "`startMillis` INTEGER NOT NULL, `minutes` INTEGER NOT NULL, `kind` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v8→v9 adds task `pinned` + `isNote` columns without wiping data. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `isNote` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v9→v10 adds the attachments table without wiping existing data. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, `mime` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                        "`isImage` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `contentBase64` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_taskId` ON `attachments` (`taskId`)")
            }
        }

        /** v10→v11 adds list nesting (lists.parentListId) without wiping existing data. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `lists` ADD COLUMN `parentListId` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lists_parentListId` ON `lists` (`parentListId`)")
            }
        }

        /** v11→v12 adds tasks.reviewedAt (GTD per-item review) without wiping data. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `reviewedAt` INTEGER")
            }
        }

        /** v12→v13 adds dependencies.delayDays (delayed activation) without wiping data. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `dependencies` ADD COLUMN `delayDays` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v13→v14 introduces named/ordered flags. Creates the `flags` table + `tasks.flagId`,
         * seeds the five default flags (whose colours match the old single-colour flag palette),
         * then back-fills each task's flagId from its legacy flagColorArgb so nothing is lost.
         * A `flagsSeeded` setting marks the defaults as planted so the app won't re-seed them
         * (e.g. after the user deletes all flags).
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `flags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`colorArgb` INTEGER NOT NULL, `icon` TEXT NOT NULL, `sortOrder` REAL NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `flagId` TEXT")
                val defaults = listOf(
                    Triple("flag-red", "Red", 0xFFE5484DL),
                    Triple("flag-amber", "Amber", 0xFFF59E0BL),
                    Triple("flag-teal", "Teal", 0xFF12A594L),
                    Triple("flag-blue", "Blue", 0xFF3E7BFAL),
                    Triple("flag-purple", "Purple", 0xFF8B5CF6L),
                )
                defaults.forEachIndexed { i, (id, name, color) ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `flags` (`id`,`name`,`colorArgb`,`icon`,`sortOrder`,`createdAt`) " +
                            "VALUES (?, ?, ?, 'flag', ?, 0)",
                        arrayOf<Any>(id, name, color, (i + 1).toDouble()),
                    )
                    db.execSQL("UPDATE `tasks` SET `flagId` = ? WHERE `flagColorArgb` = ?", arrayOf<Any>(id, color))
                }
                db.execSQL("INSERT OR REPLACE INTO `settings` (`key`,`value`) VALUES ('flagsSeeded','true')")
            }
        }

        /** v14→v15 adds the reusable task-templates table without wiping existing data. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `templates` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v15→v16 adds lists.backgroundBase64 (optional per-list background image). */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `lists` ADD COLUMN `backgroundBase64` TEXT")
            }
        }

        /** v16→v17 adds focus_sessions.taskId (link a focus session to a task). */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `focus_sessions` ADD COLUMN `taskId` TEXT")
            }
        }

        /** v17→v18 adds tasks.progressPct (manual completion percentage) without wiping data. */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `progressPct` INTEGER")
            }
        }
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contexts` ADD COLUMN `sortOrder` REAL NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "todocompanion.db",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
