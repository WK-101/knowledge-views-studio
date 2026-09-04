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
        com.todocompanion.app.data.entity.CountdownEntity::class,
        com.todocompanion.app.data.entity.ActivityEntity::class,
        com.todocompanion.app.data.entity.TaskRevisionEntity::class,
        com.todocompanion.app.data.entity.TimeActivityEntity::class,
        com.todocompanion.app.data.entity.TimeEntryEntity::class,
        com.todocompanion.app.data.entity.SealedNoteEntity::class,
        com.todocompanion.app.data.entity.CravingEventEntity::class,
        com.todocompanion.app.data.entity.CoreValueEntity::class,
        com.todocompanion.app.data.entity.WitnessEventEntity::class,
        com.todocompanion.app.data.entity.ScorecardItemEntity::class,
        com.todocompanion.app.data.entity.BuddySnapshotEntity::class,
        com.todocompanion.app.data.entity.IntegrityReviewEntity::class,
        com.todocompanion.app.data.entity.ExperimentEntity::class,
        com.todocompanion.app.data.entity.ActivationItemEntity::class,
        com.todocompanion.app.data.entity.DayLogEntity::class,
        com.todocompanion.app.data.entity.EscrowEntity::class,
        com.todocompanion.app.data.entity.NudgeEventEntity::class,
        com.todocompanion.app.data.entity.EventCalendarEntity::class,
        com.todocompanion.app.data.entity.EventEntity::class,
    ],
    version = 61,
    // R73 — export the schema JSON (to app/schemas/) on every build. With 54 hand-written migrations
    // this is the safety net: it lets an instrumented MigrationTest replay the whole chain in CI and
    // fail the build the moment a migration drifts from the entity definitions. Turned on from v59;
    // each future version's schema is committed alongside its migration.
    exportSchema = true,
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
    abstract fun countdownDao(): com.todocompanion.app.data.dao.CountdownDao
    abstract fun activityDao(): com.todocompanion.app.data.dao.ActivityDao
    abstract fun revisionDao(): com.todocompanion.app.data.dao.TaskRevisionDao
    abstract fun timeTrackingDao(): com.todocompanion.app.data.dao.TimeTrackingDao
    abstract fun sealedNoteDao(): com.todocompanion.app.data.dao.SealedNoteDao
    abstract fun cravingDao(): com.todocompanion.app.data.dao.CravingDao
    abstract fun coreValueDao(): com.todocompanion.app.data.dao.CoreValueDao
    abstract fun witnessDao(): com.todocompanion.app.data.dao.WitnessDao
    abstract fun scorecardDao(): com.todocompanion.app.data.dao.ScorecardDao
    abstract fun buddyDao(): com.todocompanion.app.data.dao.BuddyDao
    abstract fun integrityReviewDao(): com.todocompanion.app.data.dao.IntegrityReviewDao
    abstract fun experimentDao(): com.todocompanion.app.data.dao.ExperimentDao
    abstract fun activationDao(): com.todocompanion.app.data.dao.ActivationDao
    abstract fun dayLogDao(): com.todocompanion.app.data.dao.DayLogDao
    abstract fun escrowDao(): com.todocompanion.app.data.dao.EscrowDao
    abstract fun nudgeEventDao(): com.todocompanion.app.data.dao.NudgeEventDao
    abstract fun eventCalendarDao(): com.todocompanion.app.data.dao.EventCalendarDao
    abstract fun eventDao(): com.todocompanion.app.data.dao.EventDao

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
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `unit` TEXT")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `scheduleDays` TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `countdowns` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `targetMillis` INTEGER NOT NULL, `emoji` TEXT, `colorArgb` INTEGER, `pinned` INTEGER NOT NULL DEFAULT 0, `sortOrder` REAL NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `reminderTimes` TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `folderId` TEXT")
            }
        }
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_activity` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `type` TEXT NOT NULL, `at` INTEGER NOT NULL, `detail` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_activity_taskId` ON `task_activity` (`taskId`)")
            }
        }
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `deadlineDate` INTEGER")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `energy` INTEGER")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `latitude` REAL")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `longitude` REAL")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `radiusM` REAL")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `placeName` TEXT")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `onEnter` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `escalate` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `attachments` ADD COLUMN `filePath` TEXT")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_revisions` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `at` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, `label` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_revisions_taskId` ON `task_revisions` (`taskId`)")
            }
        }

        // Tier I: widen the habit model to specialist depth (type, comparison, flexible frequency,
        // increment, extra goal, start date, description, pause, money, category) + skip check-ins.
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `habitType` TEXT NOT NULL DEFAULT 'build'")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `targetComparison` TEXT NOT NULL DEFAULT 'atleast'")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `freqType` TEXT NOT NULL DEFAULT 'weekly'")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `freqParam` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `clickIncrement` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `extraTarget` INTEGER")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `startDate` INTEGER")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `paused` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `moneyPerUnit` REAL")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'done'")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `reason` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Tier K: identity, habit-stacking anchor, streak freezes, self-reward, place geofence, day photo.
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `identity` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `anchorHabitId` TEXT")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `freezeTokens` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `rewardText` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `rewardAtStreak` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `latitude` REAL")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `longitude` REAL")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `geofenceRadius` REAL")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `placeLabel` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `photoUri` TEXT")
            }
        }

        // Batch D: free-text description on lists and folders.
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `lists` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Tier O2: minute-of-day a check-in was marked done, for real "you usually do this at…" timing.
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `doneAtMinute` INTEGER")
            }
        }

        // Tier Q2: goal/project "why" (identity) + reward text, mirroring the habit vocabulary.
        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `whyText` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `rewardText` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Tier S: time tracking — activities + recorded intervals. Two fresh tables (no defaults, to
        // match Room's generated schema exactly).
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `time_activities` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT, `colorArgb` INTEGER, `archived` INTEGER NOT NULL, `sortOrder` REAL NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `time_entries` (`id` TEXT NOT NULL, `activityId` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER, `note` TEXT NOT NULL, `taskId` TEXT, `habitId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        // Tier T: the modular fusion links + time goals + interval origin. All additive columns with
        // defaults that match the entities' Kotlin defaults.
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `time_entries` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE `time_activities` ADD COLUMN `goalMinutesPerDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `time_activities` ADD COLUMN `goalDays` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `defaultActivityId` TEXT")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `timeActivityId` TEXT")
            }
        }

        // Tier U11: free-form tags on a tracked interval (for per-tag reporting). One additive column.
        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `time_entries` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Tier V: link-completion mode + encouragements on habits, and a per-day journal note.
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `linkMode` TEXT NOT NULL DEFAULT 'minutes'")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `encouragements` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Plan C: hot-path indices on time_entries (day/week/month window scans + per-activity history).
        // Purely additive — index names must match Room's generated `index_<table>_<col>` exactly.
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_entries_startMillis` ON `time_entries` (`startMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_entries_activityId` ON `time_entries` (`activityId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_entries_taskId` ON `time_entries` (`taskId`)")
            }
        }

        // R24: scope tags & contexts to a workspace (like lists/folders/filters/habits). Additive —
        // legacy rows fall into the 'default' workspace, matching the app's default active workspace.
        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tags` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE `contexts` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT 'default'")
            }
        }

        // R27 The Done Record: additive completion metadata on tasks (outcome/impact, win flag, learned
        // note, praise quote, day mood). All nullable/defaulted, so existing rows keep working; every
        // field lands in the lossless JSON backup. No new tables, no new permissions, 0 network.
        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `outcomeNote` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `winFlag` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `learnedNote` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `praiseQuote` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `mood` INTEGER")
            }
        }

        // R28 #3: per-task workspace ownership for the Trash (workspaces are independent except the Inbox).
        // Backfill from the task's list, then its folder, so existing trashed tasks land in the right
        // workspace's Trash; Inbox tasks (no workspaced list/folder) keep the 'default' workspace.
        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("UPDATE `tasks` SET `workspaceId` = (SELECT `workspaceId` FROM `lists` WHERE `lists`.`id` = `tasks`.`listId`) WHERE `listId` IN (SELECT `id` FROM `lists`)")
                db.execSQL("UPDATE `tasks` SET `workspaceId` = (SELECT `workspaceId` FROM `folders` WHERE `folders`.`id` = `tasks`.`folderId`) WHERE `folderId` IS NOT NULL AND `folderId` IN (SELECT `id` FROM `folders`)")
            }
        }

        // R32 — sealed "letter to your future self" table (Living Record #7). Additive, no data touched.
        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sealed_notes` (`id` TEXT NOT NULL, `createdEpochDay` INTEGER NOT NULL, `revealEpochDay` INTEGER NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `anchorHash` TEXT NOT NULL, `sealedCount` INTEGER NOT NULL, `acknowledged` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
            }
        }

        // R33 — the habit BUILDER layer: additive habit columns + a craving/urge log table. No data touched.
        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `cueTime` INTEGER")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `cueContext` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `rampFinalTarget` INTEGER")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `rampAddPerStep` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `rampStepDays` INTEGER NOT NULL DEFAULT 7")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `rampLastStepDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `quitSinceMillis` INTEGER")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `minutesPerUnit` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `lastPledgeDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `replacementHabitId` TEXT")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `journeyKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS `craving_events` (`id` TEXT NOT NULL, `habitId` TEXT NOT NULL, `atMillis` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `minuteOfDay` INTEGER NOT NULL, `intensity` INTEGER NOT NULL, `trigger` TEXT NOT NULL DEFAULT '', `surfed` INTEGER NOT NULL DEFAULT 1, `note` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_craving_events_habitId` ON `craving_events` (`habitId`)")
            }
        }

        // R34 — the LIFE-SYSTEMS layer: additive habit/check-in/craving columns + five small new tables
        // (values, witness log, scorecard, buddy digests, integrity reviews). No existing data touched.
        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `woopOutcome` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `woopObstacle` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `woopCoping` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `valueId` TEXT")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `competingResponse` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `contractText` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `refereeName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `forfeitText` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `forfeitLevel` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `pendingEaseMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `pendingEaseTarget` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `ctxEnergy` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `ctxMood` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `habit_checkins` ADD COLUMN `ctxPlace` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `craving_events` ADD COLUMN `halt` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `craving_events` ADD COLUMN `durationSec` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `core_values` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT, `colorArgb` INTEGER, `statement` TEXT NOT NULL DEFAULT '', `orderIndex` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `witness_events` (`id` TEXT NOT NULL, `habitId` TEXT NOT NULL, `refereeName` TEXT NOT NULL, `milestoneLabel` TEXT NOT NULL, `atMillis` INTEGER NOT NULL, `note` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_witness_events_habitId` ON `witness_events` (`habitId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `scorecard_items` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `sign` INTEGER NOT NULL DEFAULT 0, `orderIndex` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `buddy_snapshots` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `importedAtMillis` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `integrity_reviews` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `periodKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `note` TEXT NOT NULL DEFAULT '', `statsJson` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))")
            }
        }

        // R35 — the THIRD-WAVE layer: additive habit columns + three small tables (experiments,
        // behavioral-activation items, daily bookend logs). No existing data touched.
        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `frictionSteps` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `cueToDisrupt` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `cueDisruptionPlan` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `futureScene` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `graduated` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `experiments` (`id` TEXT NOT NULL, `habitId` TEXT NOT NULL, `outcome` TEXT NOT NULL, `startDay` INTEGER NOT NULL, `blockLenDays` INTEGER NOT NULL DEFAULT 3, `blocks` INTEGER NOT NULL DEFAULT 4, `active` INTEGER NOT NULL DEFAULT 1, `note` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `activation_items` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `valueId` TEXT, `plannedDay` INTEGER NOT NULL, `done` INTEGER NOT NULL DEFAULT 0, `pleasure` INTEGER NOT NULL DEFAULT 0, `mastery` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `day_logs` (`epochDay` INTEGER NOT NULL, `amIntention` TEXT NOT NULL DEFAULT '', `pmReflection` TEXT NOT NULL DEFAULT '', `amMood` INTEGER NOT NULL DEFAULT 0, `pmMood` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`epochDay`))")
            }
        }

        // R36 — the FOURTH-WAVE layer: two small tables (self-escrows, nudge-MRT events). No data touched.
        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `escrows` (`id` TEXT NOT NULL, `habitId` TEXT, `description` TEXT NOT NULL, `kind` TEXT NOT NULL, `milestoneKind` TEXT NOT NULL, `milestoneValue` INTEGER NOT NULL, `released` INTEGER NOT NULL DEFAULT 0, `redeemed` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `nudge_events` (`id` TEXT NOT NULL, `habitId` TEXT NOT NULL, `variant` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `acted` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        // R37 — habit-science ports to tasks: task↔value link, deferral-chain counter, escrow-on-a-task,
        // and the nudge MRT extended to task reminders. All additive columns with safe defaults.
        private val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `valueId` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `deferCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `lastDeferDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `escrows` ADD COLUMN `taskId` TEXT")
                db.execSQL("ALTER TABLE `nudge_events` ADD COLUMN `targetKind` TEXT NOT NULL DEFAULT 'habit'")
            }
        }

        // R38 — the DEDICATED-CALENDAR layer: local colour-coded calendars + first-class events. New tables.
        private val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `event_calendars` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `visible` INTEGER NOT NULL DEFAULT 1, `orderIndex` INTEGER NOT NULL DEFAULT 0, `isDefault` INTEGER NOT NULL DEFAULT 0, `workspaceId` TEXT NOT NULL DEFAULT 'default', `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `events` (`id` TEXT NOT NULL, `calendarId` TEXT NOT NULL, `title` TEXT NOT NULL, `location` TEXT NOT NULL DEFAULT '', `notes` TEXT NOT NULL DEFAULT '', `url` TEXT NOT NULL DEFAULT '', `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, `allDay` INTEGER NOT NULL DEFAULT 0, `floating` INTEGER NOT NULL DEFAULT 0, `timezone` TEXT NOT NULL DEFAULT '', `colorArgb` INTEGER, `rrule` TEXT NOT NULL DEFAULT '', `exDates` TEXT NOT NULL DEFAULT '', `recurrenceParentId` TEXT, `recurrenceDate` INTEGER NOT NULL DEFAULT 0, `alertsMinutes` TEXT NOT NULL DEFAULT '', `busy` INTEGER NOT NULL DEFAULT 1, `linkedTaskId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_calendarId` ON `events` (`calendarId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_startMillis` ON `events` (`startMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_recurrenceParentId` ON `events` (`recurrenceParentId`)")
            }
        }

        // R43 — "Occasions": life-events fields on the countdowns table. Additive; old rows become plain
        // COUNTDOWNs with year known and no prep task, so nothing changes for them.
        private val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `eventType` TEXT NOT NULL DEFAULT 'COUNTDOWN'")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `yearly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `yearKnown` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `personName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `prepLeadDays` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `prepTaskId` TEXT")
            }
        }

        // R45 — "beyond countdowns": count-up, per-occasion unit, category/archive/favorite, photo face,
        // biometric lock. Additive; old occasions keep their behaviour.
        private val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `countUp` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `unit` TEXT NOT NULL DEFAULT 'days'")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `photoBase64` TEXT")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `locked` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // R46 — relationship + intelligence: keep-in-touch cadence, logged moments (JSON on the row, so it
        // rides the existing countdowns backup/sync), and an alternate recurrence calendar. Additive.
        private val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `keepInTouchDays` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `momentsJson` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `recurCalendar` TEXT NOT NULL DEFAULT 'gregorian'")
            }
        }

        // R47 — "next frontier": countdown chains + letters to the future. Additive.
        private val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `chainNextId` TEXT")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `sealedLetter` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `countdowns` ADD COLUMN `sealedUntil` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // R52 — GTD Someday (tasks), archive folders, calendar invitations (events), and scale indices on
        // the tasks table. All additive; index names match Room's generated `index_<table>_<column>`.
        private val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `someday` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `organizer` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `attendees` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `rsvp` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_folderId` ON `tasks` (`folderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_workspaceId` ON `tasks` (`workspaceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_completed` ON `tasks` (`completed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_trashed` ON `tasks` (`trashed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_someday` ON `tasks` (`someday`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_dueDate` ON `tasks` (`dueDate`)")
            }
        }

        // R53 — invite lifecycle: an iCalendar UID (stable across updates) + revision number, so a
        // re-imported invite updates in place and METHOD:CANCEL can find its event.
        private val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `sequence` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // R55 — a general free-form notes field on habits (like a task's note).
        private val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''")
            }
        }
        // R57 (Wave B · index audit) — composite indices for the hottest WHERE combinations, so the DB-side
        // aggregates and workspace-scoped reads stay fast as the store grows. Purely additive.
        private val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_workspaceId_trashed` ON `tasks` (`workspaceId`, `trashed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_completed_trashed` ON `tasks` (`completed`, `trashed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_calendarId_startMillis` ON `events` (`calendarId`, `startMillis`)")
            }
        }
        // R59 (Wave 1 · colour parity) — an optional per-folder colour, so folders can be tinted like lists.
        // Purely additive; nullable so existing folders keep their default (icon-only) look.
        private val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `folders` ADD COLUMN `colorArgb` INTEGER")
            }
        }
        // R59 (Wave 2 · expert reminders) — recurring-reminder-with-count on a task reminder. Additive.
        private val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `repeatEveryMin` INTEGER")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `repeatCount` INTEGER")
            }
        }
        // R62 — complete workspace isolation. Every remaining top-level feature (occasions, time tracking,
        // flags, templates, focus, sealed notes, and the life-systems cluster) gains a `workspaceId`.
        // Purely additive: each ALTER adds the column with DEFAULT 'default', so every existing row is
        // backfilled to the default workspace (WorkspaceEntity.DEFAULT_ID) — no data is moved or lost.
        private val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val tables = listOf(
                    "countdowns", "time_activities", "time_entries", "flags", "templates",
                    "focus_sessions", "sealed_notes", "core_values", "scorecard_items",
                    "buddy_snapshots", "integrity_reviews", "activation_items", "escrows",
                    "witness_events", "experiments", "craving_events", "nudge_events",
                )
                tables.forEach { t ->
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT 'default'")
                }
            }
        }
        // R62 — day logs go per-workspace. epochDay was the whole primary key, so this recreates the table
        // with a composite (epochDay, workspaceId) key. Every existing row is copied forward into the default
        // workspace, then the old table is dropped — a table recreate, but strictly lossless (all rows kept).
        private val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_logs_new` (`epochDay` INTEGER NOT NULL, " +
                        "`amIntention` TEXT NOT NULL, `pmReflection` TEXT NOT NULL, `amMood` INTEGER NOT NULL, " +
                        "`pmMood` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `workspaceId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`epochDay`, `workspaceId`))",
                )
                db.execSQL(
                    "INSERT INTO `day_logs_new` (`epochDay`, `amIntention`, `pmReflection`, `amMood`, `pmMood`, `updatedAt`, `workspaceId`) " +
                        "SELECT `epochDay`, `amIntention`, `pmReflection`, `amMood`, `pmMood`, `updatedAt`, 'default' FROM `day_logs`",
                )
                db.execSQL("DROP TABLE `day_logs`")
                db.execSQL("ALTER TABLE `day_logs_new` RENAME TO `day_logs`")
            }
        }

        // R106 — richer daily-review reflection fields on day_logs.
        private val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `dayRating` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `energy` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `highlight` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `gratitude` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `lesson` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `tomorrowFocus` TEXT NOT NULL DEFAULT ''")
            }
        }

        // Phase B — reflection-depth fields on day_logs (three good things, morning-intention outcome,
        // and the answer to the day's rotating prompt). Purely additive, all with safe defaults.
        private val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `good1` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `good2` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `good3` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `intentionOutcome` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `day_logs` ADD COLUMN `promptAnswer` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * The complete, ordered v5→v61 migration chain. Exposed (and used by the builder below) so an
         * instrumented [androidTest] MigrationTest can replay it against a real SQLite DB and assert the
         * result matches the exported schema — turning a silent migration bug into a failing build.
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
            MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
            MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
            MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35,
            MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41,
            MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47,
            MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53,
            MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59,
            MIGRATION_59_60, MIGRATION_60_61,
        )

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // Plan A: bring the DB file to the user's chosen at-rest state (plaintext ↔ SQLCipher)
                    // BEFORE Room opens it, then hand Room the matching open-helper factory. Reconcile is
                    // a guarded, verified, rollback-safe migration; a no-op in the common case.
                    val app = context.applicationContext
                    com.todocompanion.app.data.security.SecureDb.init(app)
                    com.todocompanion.app.data.security.SecureDb.reconcile(app)
                    val factory = runCatching { com.todocompanion.app.data.security.SecureDb.openFactory(app) }.getOrNull()
                    Room.databaseBuilder(app, AppDatabase::class.java, "todocompanion.db")
                        .apply { if (factory != null) openHelperFactory(factory) }
                        // R52 — scale foundations for a DB used over years/decades. WAL gives one writer +
                        // many concurrent readers; on each open we ask SQLite to refresh its query-planner
                        // stats (PRAGMA optimize) so it keeps choosing the R52 indices as the tables grow.
                        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                        .addCallback(object : RoomDatabase.Callback() {
                            override fun onOpen(db: SupportSQLiteDatabase) {
                                super.onOpen(db)
                                runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
                                runCatching { db.execSQL("PRAGMA optimize") }
                            }
                        })
                        .addMigrations(*ALL_MIGRATIONS)
                        // R68 — data-safety: NEVER silently wipe a real user's database on a forward upgrade.
                        // The full v5→v59 migration chain above is exhaustive, so a normal upgrade never needs
                        // a fallback. We keep destructive fallback ONLY for a DOWNGRADE (installing an older
                        // build over a newer schema) — the one case a migration genuinely can't exist for.
                        // A missing FORWARD migration now fails loudly in testing instead of erasing years of
                        // tasks, habits and history in the field.
                        .fallbackToDestructiveMigrationOnDowngrade()
                        .build()
                        .also { INSTANCE = it }
                }
            }
    }
}
