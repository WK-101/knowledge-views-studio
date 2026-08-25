package com.todocompanion.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.data.entity.AttachmentEntity
import com.todocompanion.app.data.entity.AttachmentMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY sortOrder ASC")
    suspend fun childrenOf(parentId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE listId = :listId AND parentId IS :parentId ORDER BY sortOrder ASC")
    suspend fun childrenIn(listId: String, parentId: String?): List<TaskEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0.0) FROM tasks WHERE listId = :listId AND parentId IS :parentId")
    suspend fun maxSortOrder(listId: String, parentId: String?): Double

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_sessions ORDER BY startMillis DESC")
    fun observeAll(): Flow<List<FocusSessionEntity>>
    @Query("SELECT * FROM focus_sessions") suspend fun getAll(): List<FocusSessionEntity>
    @Upsert suspend fun upsert(s: FocusSessionEntity)
    @Upsert suspend fun upsertAll(s: List<FocusSessionEntity>)
    @Query("DELETE FROM focus_sessions") suspend fun clear()
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder")
    fun observeAll(): Flow<List<HabitEntity>>
    @Query("SELECT * FROM habits")
    suspend fun getAll(): List<HabitEntity>
    @Upsert suspend fun upsert(h: HabitEntity)
    @Upsert suspend fun upsertAll(h: List<HabitEntity>)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM habits") suspend fun clear()

    @Query("SELECT * FROM habit_checkins")
    fun observeCheckins(): Flow<List<HabitCheckinEntity>>
    @Query("SELECT * FROM habit_checkins") suspend fun getCheckins(): List<HabitCheckinEntity>
    @Upsert suspend fun upsertCheckin(c: HabitCheckinEntity)
    @Upsert suspend fun upsertCheckins(c: List<HabitCheckinEntity>)
    @Query("DELETE FROM habit_checkins WHERE habitId = :habitId AND epochDay = :day") suspend fun deleteCheckin(habitId: String, day: Long)
    @Query("DELETE FROM habit_checkins WHERE habitId = :habitId") suspend fun clearHabit(habitId: String)
    @Query("DELETE FROM habit_checkins") suspend fun clearCheckins()
}

@Dao
interface FlagDao {
    @Query("SELECT * FROM flags ORDER BY sortOrder")
    fun observeAll(): Flow<List<FlagEntity>>

    @Query("SELECT * FROM flags")
    suspend fun getAll(): List<FlagEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0.0) FROM flags")
    suspend fun maxSortOrder(): Double

    @Upsert
    suspend fun upsert(f: FlagEntity)

    @Upsert
    suspend fun upsertAll(f: List<FlagEntity>)

    @Query("DELETE FROM flags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM flags")
    suspend fun clear()
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY name")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun getAll(): List<TemplateEntity>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun getById(id: String): TemplateEntity?

    @Upsert
    suspend fun upsert(t: TemplateEntity)

    @Upsert
    suspend fun upsertAll(t: List<TemplateEntity>)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM templates")
    suspend fun clear()
}

@Dao
interface CountdownDao {
    @Query("SELECT * FROM countdowns ORDER BY targetMillis")
    fun observeAll(): Flow<List<com.todocompanion.app.data.entity.CountdownEntity>>

    @Query("SELECT * FROM countdowns")
    suspend fun getAll(): List<com.todocompanion.app.data.entity.CountdownEntity>

    @Upsert
    suspend fun upsert(c: com.todocompanion.app.data.entity.CountdownEntity)

    @Upsert
    suspend fun upsertAll(c: List<com.todocompanion.app.data.entity.CountdownEntity>)

    @Query("DELETE FROM countdowns WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM countdowns")
    suspend fun clear()
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM task_activity WHERE taskId = :taskId ORDER BY at DESC")
    fun observeForTask(taskId: String): Flow<List<com.todocompanion.app.data.entity.ActivityEntity>>

    @Query("SELECT * FROM task_activity")
    suspend fun getAll(): List<com.todocompanion.app.data.entity.ActivityEntity>

    @Insert
    suspend fun insert(a: com.todocompanion.app.data.entity.ActivityEntity)

    @Insert
    suspend fun insertAll(a: List<com.todocompanion.app.data.entity.ActivityEntity>)

    @Query("DELETE FROM task_activity WHERE taskId = :taskId")
    suspend fun clearForTask(taskId: String)

    @Query("DELETE FROM task_activity")
    suspend fun clear()
}

@Dao
interface FilterDao {
    @Query("SELECT * FROM filters ORDER BY sortOrder")
    fun observeAll(): Flow<List<FilterEntity>>

    @Query("SELECT * FROM filters")
    suspend fun getAll(): List<FilterEntity>

    @Upsert
    suspend fun upsert(f: FilterEntity)

    @Upsert
    suspend fun upsertAll(f: List<FilterEntity>)

    @Query("DELETE FROM filters WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM filters")
    suspend fun clear()
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY sortOrder")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    @Upsert
    suspend fun upsert(w: WorkspaceEntity)

    @Upsert
    suspend fun upsertAll(w: List<WorkspaceEntity>)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM workspaces")
    suspend fun clear()
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sortOrder")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Upsert
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM folders")
    suspend fun clear()
}

@Dao
interface ListDao {
    @Query("SELECT * FROM lists ORDER BY sortOrder")
    fun observeAll(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists")
    suspend fun getAll(): List<ListEntity>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun getById(id: String): ListEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0.0) FROM lists")
    suspend fun maxSortOrder(): Double

    @Upsert
    suspend fun upsert(list: ListEntity)

    @Upsert
    suspend fun upsertAll(lists: List<ListEntity>)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM lists")
    suspend fun clear()
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist_items ORDER BY sortOrder")
    fun observeAll(): Flow<List<ChecklistItemEntity>>

    @Query("SELECT * FROM checklist_items")
    suspend fun getAll(): List<ChecklistItemEntity>

    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY sortOrder")
    suspend fun forTask(taskId: String): List<ChecklistItemEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0.0) FROM checklist_items WHERE taskId = :taskId")
    suspend fun maxSortOrder(taskId: String): Double

    @Upsert
    suspend fun upsert(item: ChecklistItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<ChecklistItemEntity>)

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM checklist_items WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM checklist_items")
    suspend fun clear()
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM task_tags")
    fun observeCrossRefs(): Flow<List<TaskTagCrossRef>>

    @Query("SELECT * FROM task_tags")
    suspend fun getCrossRefs(): List<TaskTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: TaskTagCrossRef)

    @Delete
    suspend fun unlink(ref: TaskTagCrossRef)

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun unlinkAllForTask(taskId: String)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(refs: List<TaskTagCrossRef>)

    @Query("DELETE FROM tags")
    suspend fun clear()

    @Query("DELETE FROM task_tags")
    suspend fun clearCrossRefs()
}

@Dao
interface ContextDao {
    @Query("SELECT * FROM contexts ORDER BY name")
    fun observeAll(): Flow<List<ContextEntity>>

    @Query("SELECT * FROM contexts")
    suspend fun getAll(): List<ContextEntity>

    @Upsert
    suspend fun upsert(context: ContextEntity)

    @Query("DELETE FROM contexts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM task_contexts")
    fun observeCrossRefs(): Flow<List<TaskContextCrossRef>>

    @Query("SELECT * FROM task_contexts")
    suspend fun getCrossRefs(): List<TaskContextCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: TaskContextCrossRef)

    @Delete
    suspend fun unlink(ref: TaskContextCrossRef)

    @Query("DELETE FROM task_contexts WHERE taskId = :taskId")
    suspend fun unlinkAllForTask(taskId: String)

    @Upsert
    suspend fun upsertAll(contexts: List<ContextEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(refs: List<TaskContextCrossRef>)

    @Query("DELETE FROM contexts")
    suspend fun clear()

    @Query("DELETE FROM task_contexts")
    suspend fun clearCrossRefs()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE taskId = :taskId")
    suspend fun forTask(taskId: String): List<ReminderEntity>

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Upsert
    suspend fun upsertAll(reminders: List<ReminderEntity>)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM reminders WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM reminders")
    suspend fun clear()
}

@Dao
interface DependencyDao {
    @Query("SELECT * FROM dependencies")
    fun observeAll(): Flow<List<DependencyEntity>>

    @Query("SELECT * FROM dependencies")
    suspend fun getAll(): List<DependencyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(dep: DependencyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(deps: List<DependencyEntity>)

    @Delete
    suspend fun remove(dep: DependencyEntity)

    @Query("DELETE FROM dependencies WHERE taskId = :taskId OR dependsOnTaskId = :taskId")
    suspend fun removeAllInvolving(taskId: String)

    @Query("DELETE FROM dependencies")
    suspend fun clear()
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Upsert
    suspend fun put(setting: SettingEntity)

    @Upsert
    suspend fun putAll(settings: List<SettingEntity>)

    @Query("DELETE FROM settings")
    suspend fun clear()
}

@Dao
interface AttachmentDao {
    /** Metadata only (no Base64 bytes) so the observed flow stays cheap. */
    @Query("SELECT id, taskId, fileName, mime, sizeBytes, isImage, addedAt FROM attachments WHERE taskId = :taskId ORDER BY addedAt")
    fun observeMetaForTask(taskId: String): Flow<List<AttachmentMeta>>

    /** Metadata for every attachment (no bytes) — powers the Attachments hub. */
    @Query("SELECT id, taskId, fileName, mime, sizeBytes, isImage, addedAt FROM attachments ORDER BY addedAt DESC")
    fun observeAllMeta(): Flow<List<AttachmentMeta>>

    @Query("SELECT contentBase64 FROM attachments WHERE id = :id")
    suspend fun contentOf(id: String): String?

    @Query("SELECT * FROM attachments")
    suspend fun getAll(): List<AttachmentEntity>

    @Query("SELECT COUNT(*) FROM attachments WHERE taskId = :taskId")
    fun observeCountForTask(taskId: String): Flow<Int>

    @Upsert
    suspend fun upsert(a: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(items: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM attachments WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM attachments")
    suspend fun clear()
}
