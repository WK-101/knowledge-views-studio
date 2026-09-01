package com.todocompanion.app.data

import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.TemplateTask
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.SettingEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.data.entity.AttachmentEntity
import com.todocompanion.app.data.entity.AttachmentMeta
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.port.BackupFile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Single source of truth over Room. Reads are reactive Flows; writes are suspend. */
class AppRepository(private val db: AppDatabase) {

    /**
     * R53 — user-triggered storage maintenance for a DB kept over years: checkpoint the WAL, VACUUM to
     * compact + defragment the file (deletes only free-list pages otherwise), and refresh the query
     * planner's stats. All offline; safe to run occasionally from Settings.
     */
    suspend fun optimizeStorage(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val sdb = db.openHelper.writableDatabase
            runCatching { rebuildTaskFtsBlocking(sdb) }   // R54 — recover a stale/missing search index too
            runCatching { sdb.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
            runCatching { sdb.execSQL("VACUUM") }
            runCatching { sdb.execSQL("PRAGMA optimize") }
            true
        }.getOrDefault(false)
    }

    /** R54 — total on-disk size of the database (main file + WAL + shared-memory), for the storage panel. */
    fun databaseSizeBytes(): Long = runCatching {
        val base = db.openHelper.writableDatabase.path ?: return 0L
        listOf(base, "$base-wal", "$base-shm").sumOf { p -> runCatching { java.io.File(p).length() }.getOrDefault(0L) }
    }.getOrDefault(0L)

    // ── R54 · full-text search (FTS4) ───────────────────────────────────────────────────────────────
    // A best-effort acceleration for large task histories. The virtual table is invisible to Room's own
    // schema (created with raw SQL, IF NOT EXISTS, all guarded by runCatching), so it can never fail a
    // migration or lose data — search always has an in-memory fallback. Kept fresh by an incremental
    // upsert on the two content-authoring paths (create/save) plus a cheap count-mismatch rebuild.
    private fun ftsDb() = db.openHelper.writableDatabase
    private fun ftsCreate(sdb: androidx.sqlite.db.SupportSQLiteDatabase) {
        sdb.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS task_fts USING fts4(taskId, title, note, tokenize=unicode61)")
    }
    private fun rebuildTaskFtsBlocking(sdb: androidx.sqlite.db.SupportSQLiteDatabase) {
        ftsCreate(sdb)
        sdb.execSQL("DELETE FROM task_fts")
        sdb.execSQL("INSERT INTO task_fts(taskId, title, note) SELECT id, title, note FROM tasks")
    }
    /** Incremental index update for one task's searchable content. Off the main thread, fully guarded. */
    suspend fun syncTaskFts(id: String, title: String, note: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val sdb = ftsDb(); ftsCreate(sdb)
            sdb.execSQL("DELETE FROM task_fts WHERE taskId = ?", arrayOf<Any?>(id))
            sdb.execSQL("INSERT INTO task_fts(taskId, title, note) VALUES(?, ?, ?)", arrayOf<Any?>(id, title, note))
        }
        Unit
    }
    private fun deleteTaskFts(sdb: androidx.sqlite.db.SupportSQLiteDatabase, id: String) {
        runCatching { sdb.execSQL("DELETE FROM task_fts WHERE taskId = ?", arrayOf<Any?>(id)) }
    }
    /** Create the index if missing; rebuild only when it's clearly stale (row-count mismatch — cheap). */
    private fun ensureFtsFresh(sdb: androidx.sqlite.db.SupportSQLiteDatabase) {
        ftsCreate(sdb)
        val ftsCount = runCatching { sdb.query("SELECT count(*) FROM task_fts").use { if (it.moveToFirst()) it.getLong(0) else -1L } }.getOrDefault(-1L)
        val taskCount = runCatching { sdb.query("SELECT count(*) FROM tasks").use { if (it.moveToFirst()) it.getLong(0) else -2L } }.getOrDefault(-2L)
        if (ftsCount != taskCount) rebuildTaskFtsBlocking(sdb)
    }
    /** Task ids whose title/note match [query] (prefix, all-terms). Empty on any failure → caller falls back. */
    suspend fun searchTaskIds(query: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val match = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .joinToString(" ") { it.replace(Regex("[\"*^:()]"), "") + "*" }
            if (match.isBlank()) return@runCatching emptyList<String>()
            val sdb = ftsDb(); ensureFtsFresh(sdb)
            val ids = ArrayList<String>()
            sdb.query("SELECT taskId FROM task_fts WHERE task_fts MATCH ?", arrayOf<Any?>(match)).use { c ->
                while (c.moveToNext()) ids += c.getString(0)
            }
            ids
        }.getOrDefault(emptyList())
    }

    /**
     * R56 (Wave B / robustness R1) — DB-side COUNT(*) aggregates. A `SELECT count(*)` is orders of
     * magnitude cheaper than materialising rows into memory just to count them, and it stays fast as the
     * store grows. Read-only, fully guarded, off the hot path; powers the maintenance "database health"
     * readout and demonstrates the aggregate-in-SQL pattern the heavier counters will move onto.
     */
    suspend fun databaseRowCounts(): Map<String, Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sdb = db.openHelper.writableDatabase
        fun cnt(sql: String): Long = runCatching { sdb.query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L } }.getOrDefault(0L)
        linkedMapOf(
            "Active tasks" to cnt("SELECT count(*) FROM tasks WHERE trashed = 0"),
            "Completed" to cnt("SELECT count(*) FROM tasks WHERE completed = 1 AND trashed = 0"),
            "In Trash" to cnt("SELECT count(*) FROM tasks WHERE trashed = 1"),
            "Events" to cnt("SELECT count(*) FROM events"),
            "Habit check-ins" to cnt("SELECT count(*) FROM habit_checkins"),
            "Time entries" to cnt("SELECT count(*) FROM time_entries"),
            "Attachments" to cnt("SELECT count(*) FROM attachments"),
            "Occasions" to cnt("SELECT count(*) FROM countdowns"),
        )
    }

    private val tasks = db.taskDao()
    private val folders = db.folderDao()
    private val lists = db.listDao()
    private val checklist = db.checklistDao()
    private val tags = db.tagDao()
    private val contexts = db.contextDao()
    private val reminders = db.reminderDao()
    private val deps = db.dependencyDao()
    private val settings = db.settingDao()
    private val attachments = db.attachmentDao()
    private val flags = db.flagDao()
    private val templates = db.templateDao()
    private val countdowns = db.countdownDao()
    private val activity = db.activityDao()
    private val revisions = db.revisionDao()
    private val timeTrack = db.timeTrackingDao()
    private val sealedNotes = db.sealedNoteDao()
    private val cravings = db.cravingDao()
    private val coreValues = db.coreValueDao()
    private val witnesses = db.witnessDao()
    private val scorecard = db.scorecardDao()
    private val buddies = db.buddyDao()
    private val integrityReviews = db.integrityReviewDao()
    private val experiments = db.experimentDao()
    private val activation = db.activationDao()
    private val dayLogs = db.dayLogDao()
    private val escrows = db.escrowDao()
    private val nudgeEvents = db.nudgeEventDao()
    private val eventCalendars = db.eventCalendarDao()
    private val events = db.eventDao()
    private val templateJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ----- task time-travel: sparse revision history (H5) -----
    private val lastRevSig = HashMap<String, Int>()
    private val lastRevAt = HashMap<String, Long>()
    private companion object { const val REV_KEEP = 25; const val REV_MIN_GAP_MS = 30_000L }

    /** Fields worth versioning — cosmetic/order/timestamp churn is deliberately excluded. */
    private fun revisionSignature(t: TaskEntity): Int = listOf(
        t.title, t.note, t.importance, t.urgency, t.startDate, t.dueDate, t.deadlineDate,
        t.listId, t.folderId, t.flagId, t.energy, t.rrule, t.estimateMin, t.estimateMax,
        t.durationMin, t.completed, t.abandoned, t.isNote, t.isGoal, t.isProject, t.progressPct,
    ).hashCode()

    private fun revisionLabel(t: TaskEntity): String = when {
        t.abandoned -> "Won't do — ${t.title.take(40)}"
        t.completed -> "Completed — ${t.title.take(40)}"
        else -> t.title.ifBlank { "Untitled" }.take(48)
    }

    /**
     * Records a snapshot when a meaningful field changed since the last one, coalescing rapid
     * keystrokes (min gap) so history stays sparse. Cheap: one hash compare on the hot path.
     */
    private suspend fun maybeRecordRevision(t: TaskEntity) {
        val sig = revisionSignature(t)
        val prevSig = lastRevSig[t.id]
        if (prevSig == sig) return
        val now = now()
        val last = lastRevAt[t.id] ?: revisions.lastAt(t.id) ?: 0L
        if (prevSig != null && now - last < REV_MIN_GAP_MS) { lastRevSig[t.id] = sig; return }
        runCatching {
            revisions.insert(
                com.todocompanion.app.data.entity.TaskRevisionEntity(
                    id = uid(), taskId = t.id, at = now, snapshotJson = templateJson.encodeToString(TaskEntity.serializer(), t), label = revisionLabel(t),
                )
            )
            revisions.trim(t.id, REV_KEEP)
        }
        lastRevSig[t.id] = sig; lastRevAt[t.id] = now
    }

    fun taskRevisions(taskId: String): Flow<List<com.todocompanion.app.data.entity.TaskRevisionEntity>> = revisions.observeForTask(taskId)

    /** Restores a saved revision, snapshotting the current state first so the restore is reversible. */
    suspend fun restoreRevision(revisionId: String) {
        val rev = revisions.byId(revisionId) ?: return
        val current = tasks.getById(rev.taskId)
        if (current != null) maybeRecordRevision(current)
        val restored = runCatching { templateJson.decodeFromString(TaskEntity.serializer(), rev.snapshotJson) }.getOrNull() ?: return
        tasks.upsert(restored.copy(updatedAt = now()))
        logActivity(rev.taskId, "restored", "version")
    }

    // ----- activity log (private, on-device audit trail) -----
    fun taskActivity(taskId: String): Flow<List<com.todocompanion.app.data.entity.ActivityEntity>> = activity.observeForTask(taskId)
    val allActivity: Flow<List<com.todocompanion.app.data.entity.ActivityEntity>> = activity.observeAll()
    suspend fun getActivitiesOnce(): List<com.todocompanion.app.data.entity.ActivityEntity> = activity.getAll()
    // R23: the activity trail is an independent append-only log — deleting one row (or clearing the task's
    // whole history) removes only those rows; no cascade. Derived state (e.g. a recurring task's reliability
    // score, computed on the fly from completion events) simply recomputes from what remains.
    suspend fun deleteActivity(id: String) = activity.deleteById(id)
    suspend fun clearTaskActivity(taskId: String) = activity.clearForTask(taskId)
    suspend fun getFocusSessionsOnce(): List<com.todocompanion.app.data.entity.FocusSessionEntity> = focus.getAll()
    private suspend fun logActivity(taskId: String, type: String, detail: String? = null) {
        activity.insert(com.todocompanion.app.data.entity.ActivityEntity(uid(), taskId, type, now(), detail))
    }

    // ----- reactive reads -----
    val allTasks: Flow<List<TaskEntity>> = tasks.observeAll()
    val allFolders: Flow<List<FolderEntity>> = folders.observeAll()
    val allLists: Flow<List<ListEntity>> = lists.observeAll()
    val allChecklist: Flow<List<ChecklistItemEntity>> = checklist.observeAll()
    val allTags: Flow<List<TagEntity>> = tags.observeAll()
    val allContexts: Flow<List<ContextEntity>> = contexts.observeAll()
    val taskTagRefs: Flow<List<TaskTagCrossRef>> = tags.observeCrossRefs()
    val taskContextRefs: Flow<List<TaskContextCrossRef>> = contexts.observeCrossRefs()
    val allReminders: Flow<List<ReminderEntity>> = reminders.observeAll()
    val allDependencies: Flow<List<DependencyEntity>> = deps.observeAll()
    val allFlags: Flow<List<FlagEntity>> = flags.observeAll()
    val allTemplates: Flow<List<TemplateEntity>> = templates.observeAll()
    val allCountdowns: Flow<List<com.todocompanion.app.data.entity.CountdownEntity>> = countdowns.observeAll()
    suspend fun allCountdownsOnce(): List<com.todocompanion.app.data.entity.CountdownEntity> = countdowns.getAll()
    suspend fun upsertCountdown(c: com.todocompanion.app.data.entity.CountdownEntity) = countdowns.upsert(c)
    suspend fun deleteCountdown(id: String) = countdowns.deleteById(id)
    val allSealedNotes: Flow<List<com.todocompanion.app.data.entity.SealedNoteEntity>> = sealedNotes.observeAll()
    suspend fun upsertSealedNote(n: com.todocompanion.app.data.entity.SealedNoteEntity) = sealedNotes.upsert(n)
    suspend fun deleteSealedNote(id: String) = sealedNotes.deleteById(id)
    val allCravings: Flow<List<com.todocompanion.app.data.entity.CravingEventEntity>> = cravings.observeAll()
    suspend fun upsertCraving(c: com.todocompanion.app.data.entity.CravingEventEntity) = cravings.upsert(c)
    suspend fun deleteCraving(id: String) = cravings.deleteById(id)
    // R34 — life-systems layer accessors.
    val allCoreValues: Flow<List<com.todocompanion.app.data.entity.CoreValueEntity>> = coreValues.observeAll()
    suspend fun upsertCoreValue(v: com.todocompanion.app.data.entity.CoreValueEntity) = coreValues.upsert(v)
    suspend fun deleteCoreValue(id: String) = coreValues.deleteById(id)
    val allWitnessEvents: Flow<List<com.todocompanion.app.data.entity.WitnessEventEntity>> = witnesses.observeAll()
    suspend fun upsertWitness(w: com.todocompanion.app.data.entity.WitnessEventEntity) = witnesses.upsert(w)
    suspend fun deleteWitness(id: String) = witnesses.deleteById(id)
    val allScorecardItems: Flow<List<com.todocompanion.app.data.entity.ScorecardItemEntity>> = scorecard.observeAll()
    suspend fun upsertScorecardItem(s: com.todocompanion.app.data.entity.ScorecardItemEntity) = scorecard.upsert(s)
    suspend fun deleteScorecardItem(id: String) = scorecard.deleteById(id)
    val allBuddies: Flow<List<com.todocompanion.app.data.entity.BuddySnapshotEntity>> = buddies.observeAll()
    suspend fun upsertBuddy(b: com.todocompanion.app.data.entity.BuddySnapshotEntity) = buddies.upsert(b)
    suspend fun deleteBuddy(id: String) = buddies.deleteById(id)
    val allIntegrityReviews: Flow<List<com.todocompanion.app.data.entity.IntegrityReviewEntity>> = integrityReviews.observeAll()
    suspend fun upsertIntegrityReview(r: com.todocompanion.app.data.entity.IntegrityReviewEntity) = integrityReviews.upsert(r)
    suspend fun deleteIntegrityReview(id: String) = integrityReviews.deleteById(id)
    // R35 — third-wave accessors.
    val allExperiments: Flow<List<com.todocompanion.app.data.entity.ExperimentEntity>> = experiments.observeAll()
    suspend fun upsertExperiment(e: com.todocompanion.app.data.entity.ExperimentEntity) = experiments.upsert(e)
    suspend fun deleteExperiment(id: String) = experiments.deleteById(id)
    val allActivationItems: Flow<List<com.todocompanion.app.data.entity.ActivationItemEntity>> = activation.observeAll()
    suspend fun upsertActivationItem(a: com.todocompanion.app.data.entity.ActivationItemEntity) = activation.upsert(a)
    suspend fun deleteActivationItem(id: String) = activation.deleteById(id)
    val allDayLogs: Flow<List<com.todocompanion.app.data.entity.DayLogEntity>> = dayLogs.observeAll()
    suspend fun dayLogFor(day: Long): com.todocompanion.app.data.entity.DayLogEntity? = dayLogs.forDay(day, activeWs())
    suspend fun upsertDayLog(d: com.todocompanion.app.data.entity.DayLogEntity) = dayLogs.upsert(d)
    // R36 — fourth-wave accessors.
    val allEscrows: Flow<List<com.todocompanion.app.data.entity.EscrowEntity>> = escrows.observeAll()
    suspend fun upsertEscrow(e: com.todocompanion.app.data.entity.EscrowEntity) = escrows.upsert(e)
    suspend fun deleteEscrow(id: String) = escrows.deleteById(id)
    val allNudgeEvents: Flow<List<com.todocompanion.app.data.entity.NudgeEventEntity>> = nudgeEvents.observeAll()
    suspend fun nudgeForHabitDay(habitId: String, day: Long): com.todocompanion.app.data.entity.NudgeEventEntity? = nudgeEvents.forHabitDay(habitId, day)
    suspend fun openNudgesSince(sinceDay: Long): List<com.todocompanion.app.data.entity.NudgeEventEntity> = nudgeEvents.openSince(sinceDay)
    suspend fun upsertNudgeEvent(e: com.todocompanion.app.data.entity.NudgeEventEntity) = nudgeEvents.upsert(e)
    // R38 — dedicated-calendar accessors.
    val allEventCalendars: Flow<List<com.todocompanion.app.data.entity.EventCalendarEntity>> = eventCalendars.observeAll()
    suspend fun upsertEventCalendar(c: com.todocompanion.app.data.entity.EventCalendarEntity) = eventCalendars.upsert(c)
    suspend fun deleteEventCalendar(id: String) = eventCalendars.deleteById(id)
    suspend fun eventCalendarsOnce(): List<com.todocompanion.app.data.entity.EventCalendarEntity> = eventCalendars.getAll()
    val allEvents: Flow<List<com.todocompanion.app.data.entity.EventEntity>> = events.observeAll()
    suspend fun eventById(id: String): com.todocompanion.app.data.entity.EventEntity? = events.getById(id)
    suspend fun eventsOnce(): List<com.todocompanion.app.data.entity.EventEntity> = events.getAll()
    suspend fun upsertEvent(e: com.todocompanion.app.data.entity.EventEntity) = events.upsert(e)
    suspend fun upsertEvents(e: List<com.todocompanion.app.data.entity.EventEntity>) = events.upsertAll(e)
    suspend fun deleteEvent(id: String) { events.deleteOverridesOf(id); events.deleteById(id) }
    val allSettings: Flow<List<SettingEntity>> = settings.observeAll()
    private val habits = db.habitDao()
    val allHabits: Flow<List<HabitEntity>> = habits.observeAll()
    val allCheckins: Flow<List<HabitCheckinEntity>> = habits.observeCheckins()
    suspend fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, workspaceId: String, unit: String? = null, scheduleDays: String = "", reminderTimes: String = ""): String {
        val id = uid()
        habits.upsert(HabitEntity(id = id, name = name, emoji = emoji, colorArgb = colorArgb, targetPerDay = target.coerceAtLeast(1), unit = unit, scheduleDays = scheduleDays, reminderTimes = reminderTimes, sortOrder = now().toDouble(), workspaceId = workspaceId, createdAt = now()))
        return id
    }
    /** Create from a fully-built habit (Tier I editor); id/sortOrder/createdAt filled if blank. */
    suspend fun createHabit(h: HabitEntity): String {
        val id = h.id.ifBlank { uid() }
        habits.upsert(h.copy(id = id, sortOrder = if (h.sortOrder == 0.0) now().toDouble() else h.sortOrder, createdAt = if (h.createdAt == 0L) now() else h.createdAt))
        return id
    }
    suspend fun getHabitsOnce(): List<HabitEntity> = habits.getAll()
    suspend fun getHabitCheckinsOnce(): List<HabitCheckinEntity> = habits.getCheckins()
    suspend fun upsertHabit(h: HabitEntity) = habits.upsert(h)
    /** Persist a manual habit order by rewriting sortOrder to the given list index. */
    suspend fun setHabitOrder(orderedIds: List<String>) {
        val byId = habits.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { habits.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun deleteHabit(id: String) { habits.clearHabit(id); habits.deleteById(id) }
    /**
     * Cycle today's progress by [increment] up to the ceiling (extra goal if set, else target),
     * then back to 0 (removes the check-in). Marks the day "done".
     */
    suspend fun cycleCheckin(habitId: String, epochDay: Long, target: Int, current: Int, increment: Int = 1, extra: Int? = null) {
        val ceiling = (extra ?: target).coerceAtLeast(1)
        val next = current + increment.coerceAtLeast(1)
        if (next > ceiling) habits.deleteCheckin(habitId, epochDay)
        else {
            val existing = habits.getCheckin(habitId, epochDay)
            habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, next, status = "done", reason = existing?.reason ?: "", photoUri = existing?.photoUri,
                doneAtMinute = existing?.doneAtMinute ?: stampMinute(epochDay)))
            // K2: reaching the stretch goal earns a streak-freeze token (once per day, capped).
            if (extra != null && current < extra && next >= extra) awardFreeze(habitId)
        }
    }
    /** O2: the current minute-of-day (0–1439) when marking *today* done — else null (past days unstamped). */
    private fun stampMinute(epochDay: Long): Int? {
        val z = java.time.ZoneId.systemDefault()
        if (java.time.LocalDate.now(z).toEpochDay() != epochDay) return null
        val t = java.time.LocalTime.now(z); return t.hour * 60 + t.minute
    }
    /** K2: grant one streak-freeze token, capped at 5, for overachieving. */
    suspend fun awardFreeze(habitId: String) {
        habits.getAll().firstOrNull { it.id == habitId }?.let { h ->
            if (h.freezeTokens < 5) habits.upsert(h.copy(freezeTokens = h.freezeTokens + 1))
        }
    }
    /** K2: spend one freeze to protect a missed day — records it as a neutral skip. No-op if none left. */
    suspend fun spendFreeze(habitId: String, epochDay: Long): Boolean {
        val h = habits.getAll().firstOrNull { it.id == habitId } ?: return false
        if (h.freezeTokens <= 0) return false
        habits.upsert(h.copy(freezeTokens = h.freezeTokens - 1))
        habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, 0, status = "skip", reason = "❄️ Streak freeze"))
        return true
    }
    /** K5: attach or clear a photo on a day, preserving the day's count/status/note. */
    suspend fun setCheckinPhoto(habitId: String, epochDay: Long, photoUri: String?) {
        val existing = habits.getCheckin(habitId, epochDay)
        val base = existing ?: HabitCheckinEntity(habitId, epochDay, 0, status = "done")
        habits.upsertCheckin(base.copy(photoUri = photoUri))
    }
    /** Set an exact value for a day (numeric entry / relapse amount). 0 clears the day. Preserves any note. */
    suspend fun setCheckinValue(habitId: String, epochDay: Long, count: Int) {
        if (count <= 0) habits.deleteCheckin(habitId, epochDay)
        else {
            val existing = habits.getCheckin(habitId, epochDay)
            habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, count, status = "done", reason = existing?.reason ?: "", photoUri = existing?.photoUri,
                doneAtMinute = existing?.doneAtMinute ?: stampMinute(epochDay)))
        }
    }
    /**
     * Write a whole day at once from the per-day editor: [count] value, [status] ("done"/"skip"),
     * and a free-text [note]. An empty done day with no note clears the record entirely.
     */
    suspend fun setDay(habitId: String, epochDay: Long, count: Int, status: String, note: String) {
        val c = count.coerceAtLeast(0)
        val photo = habits.getCheckin(habitId, epochDay)?.photoUri
        if (status == "done" && c <= 0 && note.isBlank() && photo == null) habits.deleteCheckin(habitId, epochDay)
        else habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, c, status = status, reason = note, photoUri = photo))
    }
    /** Mark a day as skipped (a neutral rest day: streak and score are unaffected). */
    suspend fun skipDay(habitId: String, epochDay: Long, reason: String = "") =
        habits.upsertCheckin(HabitCheckinEntity(habitId, epochDay, 0, status = "skip", reason = reason))
    /** Remove any record for a day (back to unmarked). */
    suspend fun clearCheckin(habitId: String, epochDay: Long) = habits.deleteCheckin(habitId, epochDay)
    /** R34 · LS2 — attach the context tags (energy/mood/place) to a day's check-in without disturbing
     *  its count/status; seeds a done record if none exists yet. */
    suspend fun setCheckinContext(habitId: String, epochDay: Long, energy: Int, mood: Int, place: String) {
        val existing = habits.getCheckin(habitId, epochDay) ?: HabitCheckinEntity(habitId, epochDay, 1, status = "done")
        habits.upsertCheckin(existing.copy(ctxEnergy = energy, ctxMood = mood, ctxPlace = place))
    }
    /** Pause / resume a whole habit (vacation) without touching its history. */
    suspend fun setHabitPaused(habitId: String, paused: Boolean) {
        habits.getAll().firstOrNull { it.id == habitId }?.let { habits.upsert(it.copy(paused = paused)) }
    }
    /** Pause or resume every habit in a workspace at once. */
    suspend fun pauseAllHabits(workspaceId: String, paused: Boolean) {
        habits.getAll().filter { it.workspaceId == workspaceId && !it.archived }.forEach { habits.upsert(it.copy(paused = paused)) }
    }

    private val focus = db.focusDao()
    val allFocusSessions: Flow<List<FocusSessionEntity>> = focus.observeAll()
    suspend fun addFocusSession(epochDay: Long, startMillis: Long, minutes: Int, kind: String, taskId: String? = null) =
        focus.upsert(FocusSessionEntity(uid(), epochDay, startMillis, minutes, kind, taskId, activeWs()))

    // ----- Tier S: time tracking -----
    val allTimeActivities: Flow<List<com.todocompanion.app.data.entity.TimeActivityEntity>> = timeTrack.observeActivities()
    val allTimeEntries: Flow<List<com.todocompanion.app.data.entity.TimeEntryEntity>> = timeTrack.observeEntries()

    suspend fun createTimeActivity(name: String, emoji: String?, colorArgb: Long?, goalMinutesPerDay: Int = 0): String {
        val id = uid()
        val order = (timeTrack.getActivities().maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0
        timeTrack.upsertActivity(com.todocompanion.app.data.entity.TimeActivityEntity(id, name.trim().ifBlank { "Activity" }, emoji, colorArgb, false, order, now(), goalMinutesPerDay, "", activeWs()))
        return id
    }
    suspend fun upsertTimeActivity(a: com.todocompanion.app.data.entity.TimeActivityEntity) = timeTrack.upsertActivity(a)
    /** Soft-archive: keep the activity's tracked time but hide it from the picker (its stats survive). */
    suspend fun archiveTimeActivity(id: String) {
        timeTrack.getActivities().firstOrNull { it.id == id }?.let { timeTrack.upsertActivity(it.copy(archived = true)) }
    }

    /**
     * Full delete — removes an activity from EVERY place it's referenced, so nothing stale lingers:
     * its time entries, the tasks that defaulted to it, the habits linked to it, the pinned tiles, the
     * automation rules that fire on it, and the nested-activity parent map (its children are re-parented
     * to its own parent, or promoted to top level). Use archiveTimeActivity to keep the time instead.
     */
    suspend fun deleteTimeActivity(id: String) {
        timeTrack.deleteEntriesForActivity(id)
        tasks.getAll().filter { it.defaultActivityId == id }.forEach { tasks.upsert(it.copy(defaultActivityId = null, updatedAt = now())) }
        habits.getAll().filter { it.timeActivityId == id }.forEach { habits.upsert(it.copy(timeActivityId = null)) }
        val s = settingsSnapshot()
        val parents = s.timeActivityParents
        val grandparent = parents[id]
        val newParents = parents
            .filterKeys { it != id }                                   // drop it as a child
            .mapValues { (_, p) -> if (p == id) (grandparent ?: "") else p }   // re-parent its children
            .filterValues { it.isNotBlank() }
        val rules = com.todocompanion.app.domain.AutomationRules.parse(s.automationRulesJson)
            .filter { it.whenActivityId != id && it.startActivityId != id }
        saveSettings(s.copy(
            pinnedActivities = s.pinnedActivities - id,
            timeActivityParents = newParents,
            automationRulesJson = com.todocompanion.app.domain.AutomationRules.encode(rules),
        ))
        timeTrack.deleteActivity(id)
    }

    /**
     * Start tracking an activity. [stopFirst] keeps the single-timer discipline (default): any running
     * entry is stopped first. Pass false (U15 multi-timer) to let activities overlap. Passing [startMillis]
     * (U5 timeline-fill) back-dates the start so the new block closes a gap since the last one ended.
     */
    suspend fun startTimeTracking(activityId: String, taskId: String? = null, habitId: String? = null, stopFirst: Boolean = true, startMillis: Long? = null, kind: String = "manual"): String {
        if (stopFirst) stopTimeTracking()
        val id = uid()
        val start = startMillis ?: now()
        timeTrack.upsertEntry(com.todocompanion.app.data.entity.TimeEntryEntity(id, activityId, start, null, "", taskId, habitId, now(), kind = kind, workspaceId = activeWs()))
        return id
    }
    /** All currently-running entries (multi-timer aware). */
    suspend fun runningTimeEntries(): List<com.todocompanion.app.data.entity.TimeEntryEntity> = timeTrack.getEntries().filter { it.running }
    /** Every recorded time entry (R41 planner: planned-vs-actual, estimate calibration, weekly audit). */
    suspend fun timeEntriesOnce(): List<com.todocompanion.app.data.entity.TimeEntryEntity> = timeTrack.getEntries()
    /** Stop the (first) running entry, if any. With multi-timer on this stops one; callers can loop. */
    suspend fun stopTimeTracking() { timeTrack.runningEntry()?.let { finalizeEntry(it) } }
    /** Stop a specific running entry by id (U15). */
    suspend fun stopTimeEntry(id: String) { timeTrack.getEntries().firstOrNull { it.id == id && it.running }?.let { finalizeEntry(it) } }
    /** Close one running interval, discarding zero-length blips and crediting any linked habit once. */
    private suspend fun finalizeEntry(running: com.todocompanion.app.data.entity.TimeEntryEntity) {
        val end = now()
        if (end - running.startMillis < 1_000L) { timeTrack.deleteEntry(running.id); return }
        timeTrack.upsertEntry(running.copy(endMillis = end))
        // T3 (I4): a habit-linked interval credits the habit's check-in once, with its minutes — the same
        // single auto-log the Focus coach uses, so a timed habit is never logged twice. The link can be
        // direct (entry.habitId) or via the activity a habit is bound to (Habit.timeActivityId).
        val mins = ((end - running.startMillis) / 60_000L).toInt()
        if (mins > 0) {
            val all = habits.getAll()
            val h = running.habitId?.let { id -> all.firstOrNull { it.id == id } }
                ?: all.firstOrNull { it.timeActivityId == running.activityId && !it.archived }
            // V3: credit by the habit's link mode — minutes (default), one session, or off.
            if (h != null && h.linkMode != "off") {
                val add = if (h.linkMode == "sessions") h.clickIncrement.coerceAtLeast(1) else mins
                val day = java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                val cur = habits.getCheckins().firstOrNull { it.habitId == h.id && it.epochDay == day }?.count ?: 0
                setCheckinValue(h.id, day, cur + add)
            }
        }
    }
    /** Read the current settings snapshot (for automation/behaviour toggles outside the VM). */
    suspend fun automationRulesOnce(): List<com.todocompanion.app.domain.AutomationRule> =
        com.todocompanion.app.domain.AutomationRules.parse(settingsSnapshot().automationRulesJson)
    suspend fun addManualTimeEntry(activityId: String, startMillis: Long, endMillis: Long, note: String = "", taskId: String? = null, habitId: String? = null) =
        timeTrack.upsertEntry(com.todocompanion.app.data.entity.TimeEntryEntity(uid(), activityId, startMillis, endMillis, note, taskId, habitId, now(), workspaceId = activeWs()))
    suspend fun upsertTimeEntry(e: com.todocompanion.app.data.entity.TimeEntryEntity) = timeTrack.upsertEntry(e)
    suspend fun deleteTimeEntry(id: String) = timeTrack.deleteEntry(id)
    /** U4: split a completed interval in two at [atMillis], keeping both halves' links and tags. */
    suspend fun splitTimeEntry(id: String, atMillis: Long) {
        val e = timeTrack.getEntries().firstOrNull { it.id == id } ?: return
        val end = e.endMillis ?: return
        if (atMillis <= e.startMillis || atMillis >= end) return
        timeTrack.upsertEntry(e.copy(endMillis = atMillis))
        timeTrack.upsertEntry(e.copy(id = uid(), startMillis = atMillis, endMillis = end, createdAt = now()))
    }
    suspend fun runningTimeEntry(): com.todocompanion.app.data.entity.TimeEntryEntity? = timeTrack.runningEntry()
    suspend fun getTimeActivitiesOnce(): List<com.todocompanion.app.data.entity.TimeActivityEntity> = timeTrack.getActivities()

    /** T1 (I1): a stable "Focus" activity used to mirror Focus/Pomodoro sessions onto the one timeline. */
    suspend fun ensureFocusActivity(): String {
        timeTrack.getActivities().firstOrNull { it.name == "Focus" && !it.archived }?.let { return it.id }
        return createTimeActivity("Focus", "🎯", 0xFF6650A4L)
    }
    /** T1 (I1): record a completed Focus session as a time interval (kind=focus) so the tracker is the
     *  single source of truth for time. A completed interval — it never disturbs the running entry. */
    suspend fun mirrorFocusInterval(startMillis: Long, endMillis: Long, activityId: String, taskId: String?, habitId: String?) {
        if (endMillis <= startMillis) return
        timeTrack.upsertEntry(
            com.todocompanion.app.data.entity.TimeEntryEntity(
                id = uid(), activityId = activityId, startMillis = startMillis, endMillis = endMillis,
                note = "", taskId = taskId, habitId = habitId, createdAt = now(), kind = "focus",
                workspaceId = activeWs(),
            )
        )
    }
    /** T2: total minutes tracked against a task (all intervals, focus + manual). Single source of truth. */
    suspend fun trackedMinutesForTask(taskId: String): Int =
        timeTrack.getEntries().filter { it.taskId == taskId }.sumOf { it.minutes(now()) }
    /** T2: a stable "Tasks" activity — the generic bucket for time tracked against a task with no chosen activity. */
    suspend fun ensureTaskActivity(): String {
        timeTrack.getActivities().firstOrNull { it.name == "Tasks" && !it.archived }?.let { return it.id }
        return createTimeActivity("Tasks", "📋", 0xFF3E6DDFL)
    }

    private val filters = db.filterDao()
    val allFilters: Flow<List<FilterEntity>> = filters.observeAll()
    suspend fun upsertFilter(f: FilterEntity) = filters.upsert(f)
    suspend fun deleteFilter(id: String) = filters.deleteById(id)
    suspend fun setFilterOrder(orderedIds: List<String>) {
        val byId = filters.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { filters.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun createFilter(name: String, workspaceId: String): String {
        val id = uid()
        filters.upsert(FilterEntity(id = id, name = name, sortOrder = now().toDouble(), workspaceId = workspaceId))
        return id
    }

    fun observeTask(id: String): Flow<TaskEntity?> = tasks.observeById(id)
    suspend fun getTask(id: String): TaskEntity? = tasks.getById(id)
    suspend fun allTasksOnce(): List<TaskEntity> = tasks.getAll()
    suspend fun allListsOnce(): List<ListEntity> = lists.getAll()
    suspend fun setCompletedById(id: String, completed: Boolean) {
        tasks.getById(id)?.let { setCompleted(it, completed) }
    }

    private fun now() = System.currentTimeMillis()
    private fun uid() = UUID.randomUUID().toString()

    // ============ tasks ============
    suspend fun createTask(
        listId: String,
        title: String,
        parentId: String? = null,
        importance: Int = 2,   // default "None" priority (was 3 = Low)
        urgency: Int = 2,
        dueDate: Long? = null,
        startDate: Long? = null,
        folderId: String? = null,
    ): String {
        val id = uid()
        val order = tasks.maxSortOrder(listId, parentId) + 1.0
        tasks.upsert(
            TaskEntity(
                id = id,
                listId = listId,
                folderId = folderId,
                parentId = parentId,
                sortOrder = order,
                title = title.ifBlank { "Untitled" },
                importance = importance,
                urgency = urgency,
                dueDate = dueDate,
                startDate = startDate,
                createdAt = now(),
                updatedAt = now(),
            )
        )
        syncTaskFts(id, title.ifBlank { "Untitled" }, "")
        logActivity(id, "created")
        return id
    }

    /**
     * One-shot quick capture for the home-screen popup widget and app shortcuts: parse natural-language
     * text (date/time, p1-p4, #estimate, *, ~list) and create the task in the Inbox (or a named ~list),
     * entirely off the UI thread. Returns the new id, or null if the text has no title. Fully offline.
     */
    suspend fun quickCaptureTask(text: String): String? {
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(text, handleActivity = false)
        val parsed = com.todocompanion.app.domain.nlp.QuickAddParser.parse(tok.text)
        val title = parsed.title.trim()
        if (title.isBlank()) return null
        val zone = java.time.ZoneId.systemDefault()
        val due = parsed.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
        val imp = parsed.priority?.importance ?: 2
        val urg = parsed.priority?.urgency ?: 2
        // Make sure the Inbox exists (first-run seeding may not have happened if a widget fires first).
        if (lists.getById(ListEntity.INBOX_ID) == null) lists.upsert(ListEntity(id = ListEntity.INBOX_ID, name = "Inbox", sortOrder = 0.0))
        val listId = parsed.list?.let { name -> lists.getAll().firstOrNull { !it.archived && it.name.equals(name, ignoreCase = true) }?.id }
            ?: ListEntity.INBOX_ID
        val id = createTask(listId, title, importance = imp, urgency = urg, dueDate = due)
        if (parsed.rrule != null || tok.estimateMin != null || tok.star) getTask(id)?.let {
            saveTask(it.copy(rrule = parsed.rrule ?: it.rrule, estimateMin = tok.estimateMin ?: it.estimateMin, star = it.star || tok.star))
        }
        if (parsed.hasTime && due != null)
            upsertReminder(com.todocompanion.app.data.entity.ReminderEntity(uid(), taskId = id, type = "absolute", atTime = due))
        return id
    }

    suspend fun saveTask(task: TaskEntity) {
        // Capture user-visible reschedules for the activity log (title/note edits don't log).
        val old = tasks.getById(task.id)
        var saved = task.copy(updatedAt = now())
        // R37 · deferral chain ("never defer twice"): pushing a due-today-or-overdue OPEN task to a later
        // day counts as a defer (once per day). Moving it earlier resets the chain. Recurrence advances a
        // just-completed task, so the "both open" guard keeps those from counting.
        val od = old?.dueDate; val nd = saved.dueDate
        if (old != null && !old.completed && !saved.completed && od != null && nd != null) {
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone).toEpochDay()
            val oldDay = java.time.Instant.ofEpochMilli(od).atZone(zone).toLocalDate().toEpochDay()
            val newDay = java.time.Instant.ofEpochMilli(nd).atZone(zone).toLocalDate().toEpochDay()
            if (newDay > oldDay && oldDay <= today) {
                val bump = if (saved.lastDeferDay == today) saved.deferCount else saved.deferCount + 1
                saved = saved.copy(deferCount = bump, lastDeferDay = today)
            } else if (newDay < oldDay) {
                saved = saved.copy(deferCount = 0)
            }
        }
        tasks.upsert(saved)
        syncTaskFts(saved.id, saved.title, saved.note)
        if (old != null && old.dueDate != task.dueDate) logActivity(task.id, "rescheduled", task.dueDate?.toString())
        maybeRecordRevision(saved)
    }

    suspend fun setCompleted(task: TaskEntity, completed: Boolean) {
        val transition = completed && !task.completed
        // R37: finishing clears the deferral chain (you did it — no penalty carried forward).
        tasks.upsert(task.copy(completed = completed, completedAt = if (completed) now() else null, abandoned = false,
            deferCount = if (completed) 0 else task.deferCount, updatedAt = now()))
        logActivity(task.id, if (completed) "completed" else "reopened")
        if (transition) onTaskCompleted(task)
    }
    /** V3: completing a task ticks any habit linked to it (via a shared time-activity). V12: earns a point. */
    private suspend fun onTaskCompleted(task: TaskEntity) {
        val actId = task.defaultActivityId
        if (actId != null) {
            val day = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            habits.getAll().filter { it.timeActivityId == actId && !it.archived && !it.paused && it.linkMode != "off" && it.habitType != "break" }.forEach { h ->
                val cur = habits.getCheckins().firstOrNull { it.habitId == h.id && it.epochDay == day }?.count ?: 0
                setCheckinValue(h.id, day, cur + h.clickIncrement.coerceAtLeast(1))
            }
        }
        awardPoints(1)
    }
    /** V12: add momentum points to the wallet (earned by finishing work). */
    suspend fun awardPoints(n: Int) {
        if (n == 0) return
        val s = settingsSnapshot()
        saveSettings(s.copy(pointsBalance = (s.pointsBalance + n).coerceAtLeast(0)))
    }

    /**
     * P1: a recurring task advances in place on completion (it never sits "completed"), so it would
     * otherwise leave no completion record. Log one explicitly — the timestamped "completed" rows are
     * what the reliability score and time-of-day rhythm read.
     */
    suspend fun logRecurringCompletion(taskId: String) = logActivity(taskId, "completed")

    suspend fun setAbandoned(task: TaskEntity, abandoned: Boolean) {
        tasks.upsert(task.copy(abandoned = abandoned, completed = false, updatedAt = now()))
        logActivity(task.id, if (abandoned) "wontdo" else "reopened")
    }

    suspend fun setCollapsed(task: TaskEntity, collapsed: Boolean) =
        tasks.upsert(task.copy(collapsed = collapsed, updatedAt = now()))

    private suspend fun subtreeIds(rootId: String): List<String> {
        val out = mutableListOf(rootId)
        var frontier = listOf(rootId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 10_000) {
            val next = frontier.flatMap { tasks.childrenOf(it).map { c -> c.id } }
            out.addAll(next)
            frontier = next
        }
        return out
    }

    /** Move a task (and subtree) to Trash, or restore it. When trashing, [workspaceId] stamps which
     *  workspace owns the trash entry so a trashed task doesn't leak across workspaces via the shared Inbox. */
    suspend fun setTrashed(rootId: String, trashed: Boolean, workspaceId: String? = null) {
        val ids = subtreeIds(rootId)
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            tasks.upsert(t.copy(
                trashed = trashed, trashedAt = if (trashed) now() else null,
                workspaceId = if (trashed && workspaceId != null) workspaceId else t.workspaceId,
                updatedAt = now(),
            ))
        }
        logActivity(rootId, if (trashed) "trashed" else "restored")
    }

    /** Permanently delete a task and its subtree. */
    suspend fun deleteSubtree(rootId: String) {
        for (id in subtreeIds(rootId)) {
            tags.unlinkAllForTask(id)
            contexts.unlinkAllForTask(id)
            reminders.deleteForTask(id)
            deps.removeAllInvolving(id)
            checklist.deleteForTask(id)
            activity.clearForTask(id)
            tasks.deleteById(id)
            runCatching { deleteTaskFts(ftsDb(), id) }   // R54 — keep the search index aligned
        }
    }

    /** Empty the Trash. When [workspaceId] is given, only tasks trashed in that workspace are purged —
     *  the shared Inbox otherwise let one workspace's "Empty Trash" delete another's trashed tasks. */
    suspend fun emptyTrash(workspaceId: String? = null) {
        tasks.getAll().filter { it.trashed && (workspaceId == null || it.workspaceId == workspaceId) }.forEach { deleteSubtree(it.id) }
    }

    private suspend fun siblingsIn(listId: String, parentId: String?): List<TaskEntity> =
        tasks.childrenIn(listId, parentId)

    suspend fun indent(task: TaskEntity) {
        val sibs = siblingsIn(task.listId, task.parentId)
        val idx = sibs.indexOfFirst { it.id == task.id }
        if (idx <= 0) return
        val newParent = sibs[idx - 1]
        val order = tasks.maxSortOrder(task.listId, newParent.id) + 1.0
        tasks.upsert(task.copy(parentId = newParent.id, sortOrder = order, updatedAt = now()))
    }

    suspend fun outdent(task: TaskEntity) {
        val parentId = task.parentId ?: return
        val parent = tasks.getById(parentId) ?: return
        tasks.upsert(task.copy(parentId = parent.parentId, sortOrder = parent.sortOrder + 0.5, updatedAt = now()))
        renormalize(task.listId, parent.parentId)
    }

    suspend fun moveUp(task: TaskEntity) = swap(task, -1)
    suspend fun moveDown(task: TaskEntity) = swap(task, +1)
    private suspend fun swap(task: TaskEntity, dir: Int) {
        val sibs = siblingsIn(task.listId, task.parentId)
        val idx = sibs.indexOfFirst { it.id == task.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        tasks.upsert(task.copy(sortOrder = other.sortOrder, updatedAt = now()))
        tasks.upsert(other.copy(sortOrder = task.sortOrder, updatedAt = now()))
    }

    private suspend fun renormalize(listId: String, parentId: String?) {
        siblingsIn(listId, parentId).sortedBy { it.sortOrder }.forEachIndexed { i, t ->
            val target = (i + 1).toDouble()
            if (t.sortOrder != target) tasks.upsert(t.copy(sortOrder = target))
        }
    }

    /** Move a task and its whole subtree to another list; the root becomes a top-level task there. */
    suspend fun moveToList(rootId: String, newListId: String) {
        val ids = subtreeIds(rootId)
        val rootOrder = tasks.maxSortOrder(newListId, null) + 1.0
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            // Moving into a real list clears any folder-direct association so it lives in one place.
            if (id == rootId) {
                tasks.upsert(t.copy(listId = newListId, folderId = null, parentId = null, sortOrder = rootOrder, updatedAt = now()))
            } else {
                tasks.upsert(t.copy(listId = newListId, folderId = null, updatedAt = now()))
            }
        }
        logActivity(rootId, "moved", lists.getById(newListId)?.name)
    }

    /** Move a task (and its subtree) directly into a folder, with no list — mirrors [moveToList]. */
    suspend fun moveToFolder(rootId: String, folderId: String) {
        val ids = subtreeIds(rootId)
        val rootOrder = tasks.maxSortOrder("", null) + 1.0
        for (id in ids) {
            val t = tasks.getById(id) ?: continue
            if (id == rootId) tasks.upsert(t.copy(listId = "", folderId = folderId, parentId = null, sortOrder = rootOrder, updatedAt = now()))
            else tasks.upsert(t.copy(listId = "", folderId = folderId, updatedAt = now()))
        }
        logActivity(rootId, "moved", folders.getAll().firstOrNull { it.id == folderId }?.name)
    }

    // ============ workspaces ============
    val allWorkspaces: Flow<List<WorkspaceEntity>> = db.workspaceDao().observeAll()
    private val workspaces = db.workspaceDao()
    suspend fun ensureDefaultWorkspace() {
        if (workspaces.getAll().none { it.id == WorkspaceEntity.DEFAULT_ID }) {
            workspaces.upsert(WorkspaceEntity(WorkspaceEntity.DEFAULT_ID, "Personal", 0.0))
        }
    }
    suspend fun upsertWorkspace(w: WorkspaceEntity) = workspaces.upsert(w)
    suspend fun createWorkspace(name: String): String {
        val id = uid()
        workspaces.upsert(WorkspaceEntity(id, name, now().toDouble()))
        return id
    }
    /** Delete a workspace, reassigning its folders/lists/tags/contexts (and thus tasks) to the default
     *  space so nothing is left stranded on a workspaceId that no longer exists (which would hide it). */
    suspend fun deleteWorkspace(id: String) {
        if (id == WorkspaceEntity.DEFAULT_ID) return
        val def = WorkspaceEntity.DEFAULT_ID
        // R62 — deleting a workspace never loses data: EVERY workspace-scoped row it owns is reassigned to
        // the default space (matching how folders/lists always behaved), across the whole feature set.
        folders.getAll().filter { it.workspaceId == id }.forEach { folders.upsert(it.copy(workspaceId = def)) }
        lists.getAll().filter { it.workspaceId == id }.forEach { lists.upsert(it.copy(workspaceId = def)) }
        tags.getAll().filter { it.workspaceId == id }.forEach { tags.upsert(it.copy(workspaceId = def)) }
        contexts.getAll().filter { it.workspaceId == id }.forEach { contexts.upsert(it.copy(workspaceId = def)) }
        tasks.upsertAll(tasks.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        habits.upsertAll(habits.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        filters.upsertAll(filters.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        flags.upsertAll(flags.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        templates.upsertAll(templates.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        countdowns.upsertAll(countdowns.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        focus.upsertAll(focus.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        timeTrack.upsertActivities(timeTrack.getActivities().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        timeTrack.upsertEntries(timeTrack.getEntries().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        sealedNotes.upsertAll(sealedNotes.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        cravings.upsertAll(cravings.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        coreValues.upsertAll(coreValues.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        witnesses.upsertAll(witnesses.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        scorecard.upsertAll(scorecard.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        buddies.upsertAll(buddies.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        integrityReviews.upsertAll(integrityReviews.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        experiments.upsertAll(experiments.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        activation.upsertAll(activation.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        escrows.upsertAll(escrows.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        nudgeEvents.upsertAll(nudgeEvents.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        // Events follow their calendar, so reassigning the calendars carries the events with them.
        eventCalendars.upsertAll(eventCalendars.getAll().filter { it.workspaceId == id }.map { it.copy(workspaceId = def) })
        workspaces.deleteById(id)
    }

    // ============ folders ============
    suspend fun createFolder(name: String, parentId: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID): String {
        val id = uid()
        folders.upsert(FolderEntity(id = id, parentId = parentId, name = name, sortOrder = now().toDouble(), workspaceId = workspaceId))
        return id
    }

    suspend fun saveFolder(folder: FolderEntity) = folders.upsert(folder)

    /** Delete a folder; its lists and child folders move up to its parent. */
    suspend fun deleteFolder(id: String) {
        val f = folders.getAll().firstOrNull { it.id == id } ?: return
        lists.getAll().filter { it.folderId == id }.forEach { lists.upsert(it.copy(folderId = f.parentId)) }
        folders.getAll().filter { it.parentId == id }.forEach { folders.upsert(it.copy(parentId = f.parentId)) }
        folders.deleteById(id)
    }

    // ============ lists ============
    suspend fun ensureInbox() {
        if (lists.getById(ListEntity.INBOX_ID) == null) {
            lists.upsert(ListEntity(id = ListEntity.INBOX_ID, name = "Inbox", sortOrder = 0.0))
        }
    }

    suspend fun createList(name: String, folderId: String? = null, colorArgb: Long? = null, emoji: String? = null, workspaceId: String = WorkspaceEntity.DEFAULT_ID, parentListId: String? = null): String {
        val id = uid()
        val order = lists.maxSortOrder() + 1.0
        lists.upsert(ListEntity(id = id, folderId = folderId, parentListId = parentListId, name = name, colorArgb = colorArgb, emoji = emoji, sortOrder = order, workspaceId = workspaceId))
        return id
    }

    suspend fun saveList(list: ListEntity) = lists.upsert(list)
    suspend fun getList(id: String): ListEntity? = lists.getById(id)
    /** Set (or clear, when null) a list's embedded background image (already-encoded JPEG base64). */
    suspend fun setListBackground(listId: String, base64: String?) {
        lists.getById(listId)?.let { lists.upsert(it.copy(backgroundBase64 = base64)) }
    }

    /** Delete a list and permanently remove its tasks. Child lists are re-parented up
     *  (to this list's own parent / folder root) so they aren't orphaned. */
    suspend fun deleteList(id: String) {
        if (id == ListEntity.INBOX_ID) return
        val victim = lists.getById(id)
        lists.getAll().filter { it.parentListId == id }.forEach {
            lists.upsert(it.copy(parentListId = victim?.parentListId, folderId = victim?.folderId ?: it.folderId))
        }
        tasks.getAll().filter { it.listId == id && it.parentId == null }.forEach { deleteSubtree(it.id) }
        // any orphaned tasks with this listId (safety)
        tasks.getAll().filter { it.listId == id }.forEach { tasks.deleteById(it.id) }
        lists.deleteById(id)
    }

    // ============ drawer reordering / nesting ============
    suspend fun moveListOrder(list: ListEntity, dir: Int) {
        val sibs = lists.getAll().filter { it.folderId == list.folderId && it.parentListId == list.parentListId && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == list.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        lists.upsert(list.copy(sortOrder = other.sortOrder))
        lists.upsert(other.copy(sortOrder = list.sortOrder))
    }

    suspend fun moveFolderOrder(folder: FolderEntity, dir: Int) {
        val sibs = folders.getAll().filter { it.parentId == folder.parentId }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == folder.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        folders.upsert(folder.copy(sortOrder = other.sortOrder))
        folders.upsert(other.copy(sortOrder = folder.sortOrder))
    }

    suspend fun moveListToFolder(listId: String, folderId: String?) {
        val all = lists.getAll()
        val list = all.firstOrNull { it.id == listId } ?: return
        // Moving into a folder makes the list top-level there (clears any list nesting).
        lists.upsert(list.copy(folderId = folderId, parentListId = null, sortOrder = now().toDouble()))
        // R31 #3 — carry the whole sub-list subtree into the new folder too, so folder counts, folder
        // views and folder-scoped filters/calendar/matrix stay consistent with the sidebar's visual
        // nesting. Without this, a moved parent's children keep a stale folderId and silently vanish
        // from the target folder's totals while lingering in the old folder's.
        cascadeSublistFolder(listId, folderId, all)
    }

    /** Nest a list under another list (or pass null to un-nest to folder root). Cycle-safe;
     *  the child adopts the parent's folder so the subtree stays in one place. */
    suspend fun setListParent(listId: String, parentListId: String?) {
        if (listId == parentListId || listId == ListEntity.INBOX_ID) return
        val all = lists.getAll()
        val list = all.firstOrNull { it.id == listId } ?: return
        // prevent cycles: parentListId must not be a descendant of listId
        val descendants = mutableSetOf(listId)
        var changed = true
        while (changed) {
            changed = false
            all.forEach { if (it.parentListId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        if (parentListId != null && parentListId in descendants) return
        val newFolder = if (parentListId != null) all.firstOrNull { it.id == parentListId }?.folderId else list.folderId
        lists.upsert(list.copy(parentListId = parentListId, folderId = newFolder, sortOrder = now().toDouble()))
        // R31 #3 — descendants follow the moved list into its new folder (see moveListToFolder).
        cascadeSublistFolder(listId, newFolder, all)
    }

    /** Push [folderId] onto every list nested (directly or transitively) under [rootListId] via
     *  parentListId. The [snapshot] is the list of all lists read before the root was re-parented, so
     *  the descendant edges are still intact. The root itself is assumed already updated by the caller. */
    private suspend fun cascadeSublistFolder(rootListId: String, folderId: String?, snapshot: List<ListEntity>) {
        val descendants = mutableSetOf(rootListId)
        var changed = true
        while (changed) {
            changed = false
            snapshot.forEach { if (it.parentListId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        descendants.remove(rootListId)
        descendants.forEach { id ->
            snapshot.firstOrNull { it.id == id }?.let { if (it.folderId != folderId) lists.upsert(it.copy(folderId = folderId)) }
        }
    }

    suspend fun moveFolderToParent(folderId: String, parentId: String?) {
        if (folderId == parentId) return
        // prevent cycles: parentId must not be a descendant of folderId
        val all = folders.getAll()
        val descendants = mutableSetOf(folderId)
        var changed = true
        while (changed) {
            changed = false
            all.forEach { if (it.parentId in descendants && it.id !in descendants) { descendants.add(it.id); changed = true } }
        }
        if (parentId != null && parentId in descendants) return
        all.firstOrNull { it.id == folderId }?.let { folders.upsert(it.copy(parentId = parentId, sortOrder = now().toDouble())) }
    }

    // ============ checklist ============
    suspend fun checklistFor(taskId: String): List<ChecklistItemEntity> = checklist.forTask(taskId)
    suspend fun addChecklistItem(taskId: String, text: String) {
        val order = checklist.maxSortOrder(taskId) + 1.0
        checklist.upsert(ChecklistItemEntity(id = uid(), taskId = taskId, sortOrder = order, text = text))
    }
    suspend fun saveChecklistItem(item: ChecklistItemEntity) = checklist.upsert(item)
    suspend fun deleteChecklistItem(id: String) = checklist.deleteById(id)

    // ============ attachments ============
    /** Max size accepted PER FILE (50 MB). There is no limit on the NUMBER of attachments a
     *  task can hold. Bytes live Base64 in the DB and travel losslessly in JSON backups. Any
     *  file type is accepted (images, PDF, Office docs, epub, txt/md, etc.); the per-file cap
     *  just keeps any single file from bloating the backup. */
    val maxAttachmentBytes = 50L * 1024 * 1024
    fun attachmentMeta(taskId: String): Flow<List<AttachmentMeta>> = attachments.observeMetaForTask(taskId)
    val allAttachmentMeta: Flow<List<AttachmentMeta>> = attachments.observeAllMeta()
    fun attachmentCount(taskId: String): Flow<Int> = attachments.observeCountForTask(taskId)
    suspend fun attachmentContent(id: String): String? = attachments.contentOf(id)
    suspend fun attachmentFilePath(id: String): String? = attachments.filePathOf(id)
    /** Store raw bytes as a task attachment (Base64 in the DB — used by imports). */
    suspend fun addAttachment(taskId: String, fileName: String, mime: String, bytes: ByteArray): Boolean {
        if (bytes.size > maxAttachmentBytes) return false
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        attachments.upsert(
            AttachmentEntity(
                id = uid(), taskId = taskId, fileName = fileName, mime = mime,
                sizeBytes = bytes.size.toLong(), isImage = mime.startsWith("image/"),
                addedAt = now(), contentBase64 = b64,
            ),
        )
        return true
    }
    /** File-backed attachment (F4): the caller has already written [filePath]; the DB stays lean. */
    suspend fun addAttachmentFile(taskId: String, fileName: String, mime: String, sizeBytes: Long, filePath: String) {
        attachments.upsert(
            AttachmentEntity(
                id = uid(), taskId = taskId, fileName = fileName, mime = mime,
                sizeBytes = sizeBytes, isImage = mime.startsWith("image/"),
                addedAt = now(), contentBase64 = "", filePath = filePath,
            ),
        )
    }
    suspend fun deleteAttachment(id: String) {
        attachments.filePathOf(id)?.let { runCatching { java.io.File(it).delete() } }
        attachments.deleteById(id)
    }

    /** Attachments with bytes materialised inline (reads file-backed ones from disk) — for a
     *  lossless JSON export. filePath is dropped so the backup is portable. */
    private suspend fun hydratedAttachments(): List<AttachmentEntity> = attachments.getAll().map { a ->
        if (a.contentBase64.isBlank() && !a.filePath.isNullOrBlank()) {
            val f = java.io.File(a.filePath!!)
            if (f.exists()) a.copy(contentBase64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP), filePath = null) else a
        } else a
    }

    // ============ tags / contexts ============
    suspend fun getTagsOnce(): List<TagEntity> = tags.getAll()
    suspend fun getContextsOnce(): List<ContextEntity> = contexts.getAll()
    suspend fun upsertTag(tag: TagEntity) = tags.upsert(tag)
    suspend fun deleteTag(id: String) = tags.deleteById(id)
    /** Persist a new order for the given tags by rewriting their sortOrder to the list index. */
    suspend fun setTagOrder(orderedIds: List<String>) {
        val byId = tags.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { tags.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun setTaskTags(taskId: String, tagIds: List<String>) {
        tags.unlinkAllForTask(taskId)
        tags.linkAll(tagIds.map { TaskTagCrossRef(taskId, it) })
    }
    suspend fun upsertContext(context: ContextEntity) = contexts.upsert(context)
    suspend fun deleteContext(id: String) = contexts.deleteById(id)
    suspend fun setContextOrder(orderedIds: List<String>) {
        val byId = contexts.getAll().associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { contexts.upsert(it.copy(sortOrder = i.toDouble())) } }
    }
    suspend fun setTaskContexts(taskId: String, contextIds: List<String>) {
        contexts.unlinkAllForTask(taskId)
        contexts.linkAll(contextIds.map { TaskContextCrossRef(taskId, it) })
    }

    // ============ flags ============
    suspend fun getFlagsOnce(): List<FlagEntity> = flags.getAll()
    suspend fun upsertFlag(f: FlagEntity) = flags.upsert(f)
    suspend fun createFlag(name: String, colorArgb: Long, icon: String = "bookmark"): String {
        val id = uid()
        flags.upsert(FlagEntity(id = id, name = name.ifBlank { "Flag" }, colorArgb = colorArgb, icon = icon, sortOrder = flags.maxSortOrder() + 1.0, createdAt = now(), workspaceId = activeWs()))
        return id
    }
    /** Delete a flag and clear it (id + colour cache) from every task that wore it. */
    suspend fun deleteFlag(id: String) {
        tasks.getAll().filter { it.flagId == id }.forEach { tasks.upsert(it.copy(flagId = null, flagColorArgb = null, updatedAt = now())) }
        flags.deleteById(id)
    }
    suspend fun moveFlagOrder(flag: FlagEntity, dir: Int) {
        val sibs = flags.getAll().sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == flag.id }
        val j = idx + dir
        if (idx < 0 || j < 0 || j >= sibs.size) return
        val other = sibs[j]
        flags.upsert(flag.copy(sortOrder = other.sortOrder))
        flags.upsert(other.copy(sortOrder = flag.sortOrder))
    }
    /** Assign (or clear, when [flagId] is null) a task's flag, caching the flag colour on the task. */
    suspend fun setTaskFlag(task: TaskEntity, flagId: String?) {
        val color = flagId?.let { fid -> flags.getAll().firstOrNull { it.id == fid }?.colorArgb }
        tasks.upsert(task.copy(flagId = flagId, flagColorArgb = color, updatedAt = now()))
    }
    /** Seed the default flags once, unless the user has already been given them. */
    suspend fun ensureDefaultFlags() {
        if (settings.get("flagsSeeded") == "true") return
        if (flags.getAll().isEmpty()) flags.upsertAll(FlagEntity.DEFAULTS)
        settings.put(SettingEntity("flagsSeeded", "true"))
    }

    // ============ templates ============
    suspend fun deleteTemplate(id: String) = templates.deleteById(id)
    suspend fun getTemplatesOnce(): List<TemplateEntity> = templates.getAll()
    suspend fun renameTemplate(id: String, name: String) {
        templates.getById(id)?.let { templates.upsert(it.copy(name = name.ifBlank { it.name })) }
    }

    private fun dayOffset(millis: Long?, todayStart: Long): Int? =
        millis?.let { ((it - todayStart) / 86_400_000L).toInt() }

    /** Freeze a task subtree (note, priority, flag, recurrence, checklist, tags, contexts,
     *  relative dates) into a named, reusable template. */
    suspend fun saveAsTemplate(rootTaskId: String, name: String): String? {
        val root = tasks.getById(rootTaskId) ?: return null
        val tagName = tags.getAll().associate { it.id to it.name }
        val ctxName = contexts.getAll().associate { it.id to it.name }
        val tagRefs = tags.getCrossRefs().groupBy { it.taskId }
        val ctxRefs = contexts.getCrossRefs().groupBy { it.taskId }
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        suspend fun node(t: TaskEntity): TemplateTask = TemplateTask(
            title = t.title, note = t.note, isNote = t.isNote,
            importance = t.importance, urgency = t.urgency,
            flagId = t.flagId, flagColorArgb = t.flagColorArgb,
            durationMin = t.durationMin, estimateMin = t.estimateMin, leadTimeMin = t.leadTimeMin,
            completeInOrder = t.completeInOrder, isProject = t.isProject, isGoal = t.isGoal,
            rrule = t.rrule, recurrenceMode = t.recurrenceMode,
            startOffsetDays = dayOffset(t.startDate, todayStart), dueOffsetDays = dayOffset(t.dueDate, todayStart),
            tagNames = tagRefs[t.id].orEmpty().mapNotNull { tagName[it.tagId] },
            contextNames = ctxRefs[t.id].orEmpty().mapNotNull { ctxName[it.contextId] },
            checklist = checklist.forTask(t.id).map { it.text },
            children = tasks.childrenOf(t.id).map { node(it) },
        )

        val payload = node(root)
        val id = uid()
        templates.upsert(TemplateEntity(id, name.ifBlank { root.title }, templateJson.encodeToString(TemplateTask.serializer(), payload), now(), workspaceId = activeWs()))
        return id
    }

    /** Instantiate a template into [listId] under [parentId], returning the new root task id.
     *  If [folderId] is set (and [parentId] is null), the root is captured directly into that folder. */
    suspend fun instantiateTemplate(templateId: String, listId: String, parentId: String? = null, folderId: String? = null): String? {
        val tpl = templates.getById(templateId) ?: return null
        val payload = runCatching { templateJson.decodeFromString(TemplateTask.serializer(), tpl.payloadJson) }.getOrNull() ?: return null
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tagByName = tags.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
        val ctxByName = contexts.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()

        suspend fun tagId(name: String): String = tagByName.getOrPut(name.lowercase()) {
            uid().also { tags.upsert(TagEntity(it, name)) }
        }
        suspend fun ctxId(name: String): String = ctxByName.getOrPut(name.lowercase()) {
            uid().also { contexts.upsert(ContextEntity(id = it, name = name)) }
        }

        suspend fun create(node: TemplateTask, parent: String?): String {
            val id = uid()
            val order = tasks.maxSortOrder(listId, parent) + 1.0
            tasks.upsert(
                TaskEntity(
                    id = id, listId = listId, folderId = if (parent == null) folderId else null, parentId = parent, sortOrder = order,
                    title = node.title.ifBlank { "Untitled" }, note = node.note, isNote = node.isNote,
                    importance = node.importance, urgency = node.urgency,
                    flagId = node.flagId, flagColorArgb = node.flagColorArgb,
                    durationMin = node.durationMin, estimateMin = node.estimateMin, leadTimeMin = node.leadTimeMin,
                    completeInOrder = node.completeInOrder, isProject = node.isProject, isGoal = node.isGoal,
                    rrule = node.rrule, recurrenceMode = node.recurrenceMode,
                    startDate = node.startOffsetDays?.let { todayStart + it * 86_400_000L },
                    dueDate = node.dueOffsetDays?.let { todayStart + it * 86_400_000L },
                    createdAt = now(), updatedAt = now(),
                ),
            )
            node.checklist.forEachIndexed { i, text -> checklist.upsert(ChecklistItemEntity(id = uid(), taskId = id, sortOrder = (i + 1).toDouble(), text = text)) }
            if (node.tagNames.isNotEmpty()) tags.linkAll(node.tagNames.map { TaskTagCrossRef(id, tagId(it)) })
            if (node.contextNames.isNotEmpty()) contexts.linkAll(node.contextNames.map { TaskContextCrossRef(id, ctxId(it)) })
            node.children.forEach { create(it, id) }
            return id
        }
        return create(payload, parentId)
    }

    // ============ reminders / deps ============
    suspend fun remindersFor(taskId: String): List<ReminderEntity> = reminders.forTask(taskId)
    suspend fun upsertReminder(reminder: ReminderEntity) = reminders.upsert(reminder)
    suspend fun deleteReminder(id: String) = reminders.deleteById(id)
    suspend fun allRemindersOnce(): List<ReminderEntity> = reminders.getAll()
    suspend fun addDependency(taskId: String, dependsOn: String, mode: String = "AND", delayDays: Int = 0) =
        deps.add(DependencyEntity(taskId, dependsOn, mode, delayDays))
    suspend fun removeDependency(dep: DependencyEntity) = deps.remove(dep)

    // ============ settings ============
    suspend fun settingsSnapshot(): AppSettings =
        AppSettings.fromMap(settings.getAll().associate { it.key to it.value })
    /** R62 — the active workspace, read synchronously, so repo-side creates stamp the right isolation. */
    suspend fun activeWs(): String = settingsSnapshot().activeWorkspaceId

    // R62 — workspace-scoped one-shot reads for home-screen widgets and background notifications, so those
    // surfaces show ONLY the active workspace's data. The scoping logic lives here once, mirroring the flows.
    /** Tasks in the active workspace — by list/folder membership (+ the shared Inbox), exactly like the app. */
    suspend fun wsTasksOnce(): List<TaskEntity> {
        val ws = activeWs()
        val listIds = lists.getAll().filter { it.workspaceId == ws }.map { it.id }.toSet() + ListEntity.INBOX_ID
        val folderIds = folders.getAll().filter { it.workspaceId == ws }.map { it.id }.toSet()
        return tasks.getAll().filter { it.listId in listIds || (it.folderId != null && it.folderId in folderIds) }
    }
    suspend fun wsCountdownsOnce(): List<com.todocompanion.app.data.entity.CountdownEntity> =
        countdowns.getAll().filter { it.workspaceId == activeWs() }
    suspend fun wsHabitsOnce(): List<HabitEntity> = habits.getAll().filter { it.workspaceId == activeWs() }
    suspend fun wsTimeActivitiesOnce(): List<com.todocompanion.app.data.entity.TimeActivityEntity> =
        timeTrack.getActivities().filter { it.workspaceId == activeWs() }
    suspend fun wsFocusSessionsOnce(): List<com.todocompanion.app.data.entity.FocusSessionEntity> =
        focus.getAll().filter { it.workspaceId == activeWs() }
    suspend fun wsActivitiesOnce(): List<com.todocompanion.app.data.entity.ActivityEntity> {
        val taskIds = wsTasksOnce().map { it.id }.toSet()
        return activity.getAll().filter { it.taskId in taskIds }
    }
    suspend fun wsEventsOnce(): List<com.todocompanion.app.data.entity.EventEntity> {
        val calIds = eventCalendars.getAll().filter { it.workspaceId == activeWs() }.map { it.id }.toSet()
        return events.getAll().filter { it.calendarId in calIds }
    }
    suspend fun saveSettings(s: AppSettings) =
        settings.putAll(s.toMap().map { SettingEntity(it.key, it.value) })

    // ============ export / import ============
    /** Settings for export/sync, minus device-secret keys (the encryption passphrase never leaves). */
    private suspend fun exportableSettings(): List<SettingEntity> =
        settings.getAll().filterNot { it.key == com.todocompanion.app.domain.AppSettings.Keys.SYNC_PASS }

    suspend fun exportJson(): String = Backup.encode(
        BackupFile(
            exportedAt = now(),
            workspaces = workspaces.getAll(),
            filters = filters.getAll(),
            habits = habits.getAll(),
            habitCheckins = habits.getCheckins(),
            focusSessions = focus.getAll(),
            folders = folders.getAll(),
            lists = lists.getAll(),
            tasks = tasks.getAll(),
            checklist = checklist.getAll(),
            tags = tags.getAll(),
            taskTags = tags.getCrossRefs(),
            contexts = contexts.getAll(),
            taskContexts = contexts.getCrossRefs(),
            reminders = reminders.getAll(),
            dependencies = deps.getAll(),
            settings = exportableSettings(),
            attachments = hydratedAttachments(),
            flags = flags.getAll(),
            templates = templates.getAll(),
            countdowns = countdowns.getAll(),
            activities = activity.getAll(),
            timeActivities = timeTrack.getActivities(),
            timeEntries = timeTrack.getEntries(),
            sealedNotes = sealedNotes.getAll(),
            cravingEvents = cravings.getAll(),
            coreValues = coreValues.getAll(),
            witnessEvents = witnesses.getAll(),
            scorecardItems = scorecard.getAll(),
            buddySnapshots = buddies.getAll(),
            integrityReviews = integrityReviews.getAll(),
            experiments = experiments.getAll(),
            activationItems = activation.getAll(),
            dayLogs = dayLogs.getAll(),
            escrows = escrows.getAll(),
            nudgeEvents = nudgeEvents.getAll(),
            revisions = revisions.getAll(),
            eventCalendars = eventCalendars.getAll(),
            events = events.getAll(),
        )
    )

    /** Human-readable Markdown outline (lossy, portable). */
    suspend fun exportMarkdown(includeCompleted: Boolean): String =
        com.todocompanion.app.domain.port.Export.toMarkdown(
            tasks = tasks.getAll(), lists = lists.getAll(), tags = tags.getAll(),
            taskTagPairs = tags.getCrossRefs().map { it.taskId to it.tagId }, includeCompleted = includeCompleted,
        )

    /** iCalendar (.ics) of dated tasks + deadlines — importable by any calendar app. */
    suspend fun exportIcs(includeCompleted: Boolean): String =
        com.todocompanion.app.domain.port.Export.toIcs(
            tasks = tasks.getAll(), includeCompleted = includeCompleted, now = System.currentTimeMillis(),
        )

    /** Habit check-ins as long-format CSV (re-importable). */
    suspend fun exportHabitsCsv(): String =
        com.todocompanion.app.domain.port.Export.toHabitsCsv(habits.getAll(), habits.getCheckins())

    /** Import habit check-ins from our CSV or a Loop "Checkmarks" export; returns rows imported. */
    suspend fun importHabitsCsv(text: String): Int {
        val res = com.todocompanion.app.data.sync.HabitImporter.parse(text) ?: return 0
        val existing = habits.getAll().associateBy { it.name.lowercase() }.toMutableMap()
        res.rows.groupBy { it.habit }.forEach { (name, rows) ->
            val h = existing[name.lowercase()] ?: run {
                val id = createHabit(name.trim(), null, null, 1, com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID)
                habits.getAll().first { it.id == id }.also { existing[name.lowercase()] = it }
            }
            rows.forEach { r ->
                if (r.count > 0 || r.status == "skip")
                    habits.upsertCheckin(HabitCheckinEntity(h.id, r.epochDay, r.count, r.status))
            }
        }
        return res.rows.size
    }

    /** Flat CSV (lossy, portable — opens in any spreadsheet). */
    suspend fun exportCsv(includeCompleted: Boolean): String =
        com.todocompanion.app.domain.port.Export.toCsv(
            tasks = tasks.getAll(), lists = lists.getAll(), tags = tags.getAll(),
            taskTagPairs = tags.getCrossRefs().map { it.taskId to it.tagId }, includeCompleted = includeCompleted,
        )

    suspend fun importJsonReplace(text: String) {
        val b = Backup.decode(text)
        tasks.clear(); folders.clear(); lists.clear(); checklist.clear()
        tags.clear(); tags.clearCrossRefs(); contexts.clear(); contexts.clearCrossRefs()
        reminders.clear(); deps.clear(); settings.clear(); workspaces.clear(); filters.clear()
        habits.clear(); habits.clearCheckins(); focus.clear(); attachments.clear(); flags.clear(); templates.clear(); countdowns.clear(); activity.clear(); revisions.clear()
        timeTrack.clearEntries(); timeTrack.clearActivities(); sealedNotes.clear(); cravings.clear()
        coreValues.clear(); witnesses.clear(); scorecard.clear(); buddies.clear(); integrityReviews.clear()
        experiments.clear(); activation.clear(); dayLogs.clear()
        escrows.clear(); nudgeEvents.clear(); eventCalendars.clear(); events.clear()
        folders.upsertAll(b.folders)
        lists.upsertAll(b.lists)
        tasks.upsertAll(b.tasks)
        checklist.upsertAll(b.checklist)
        tags.upsertAll(b.tags); tags.linkAll(b.taskTags)
        contexts.upsertAll(b.contexts); contexts.linkAll(b.taskContexts)
        reminders.upsertAll(b.reminders)
        deps.addAll(b.dependencies)
        settings.putAll(b.settings)
        workspaces.upsertAll(b.workspaces)
        filters.upsertAll(b.filters)
        habits.upsertAll(b.habits); habits.upsertCheckins(b.habitCheckins)
        focus.upsertAll(b.focusSessions)
        attachments.upsertAll(b.attachments)
        flags.upsertAll(b.flags)
        templates.upsertAll(b.templates)
        countdowns.upsertAll(b.countdowns)
        activity.insertAll(b.activities)
        timeTrack.upsertActivities(b.timeActivities); timeTrack.upsertEntries(b.timeEntries)
        sealedNotes.upsertAll(b.sealedNotes)
        cravings.upsertAll(b.cravingEvents)
        coreValues.upsertAll(b.coreValues); witnesses.upsertAll(b.witnessEvents); scorecard.upsertAll(b.scorecardItems)
        buddies.upsertAll(b.buddySnapshots); integrityReviews.upsertAll(b.integrityReviews)
        experiments.upsertAll(b.experiments); activation.upsertAll(b.activationItems); dayLogs.upsertAll(b.dayLogs)
        escrows.upsertAll(b.escrows); nudgeEvents.upsertAll(b.nudgeEvents); revisions.upsertAll(b.revisions)
        eventCalendars.upsertAll(b.eventCalendars); events.upsertAll(b.events)
        ensureDefaultWorkspace()
        ensureInbox()
        ensureDefaultFlags()
    }

    /**
     * O4: import a backup by MERGING into the current data instead of replacing it — moving between
     * phones, or combining two devices, without losing either side. Reuses the last-write-wins sync
     * policy (tasks by updatedAt, check-ins by higher count, structural rows by the newer snapshot).
     */
    suspend fun importJsonMerge(text: String) {
        val incoming = Backup.decode(text)
        val merged = com.todocompanion.app.data.sync.SyncEngine.merge(snapshot(), incoming)
        applyMerged(merged)
        // R62 — the sync snapshot only reconciles the core 19 tables. Union the remaining tables additively
        // (insert only rows this device doesn't already have, by id) so a merge-import NEVER drops the other
        // device's time tracking, calendar, occasions, life-systems, sealed notes, revisions or attachments.
        mergeNewerTables(incoming)
        ensureDefaultWorkspace(); ensureInbox(); ensureDefaultFlags()
    }

    /** Additive, lossless union of the tables outside the sync snapshot: keep every local row, add any
     *  incoming row whose id (or natural key) isn't already present. Used only by merge-import. */
    private suspend fun mergeNewerTables(b: com.todocompanion.app.domain.port.BackupFile) {
        fun <T> missing(local: List<T>, incoming: List<T>, key: (T) -> Any?): List<T> {
            val have = local.map(key).toHashSet(); return incoming.filter { key(it) !in have }
        }
        timeTrack.upsertActivities(missing(timeTrack.getActivities(), b.timeActivities) { it.id })
        timeTrack.upsertEntries(missing(timeTrack.getEntries(), b.timeEntries) { it.id })
        sealedNotes.upsertAll(missing(sealedNotes.getAll(), b.sealedNotes) { it.id })
        cravings.upsertAll(missing(cravings.getAll(), b.cravingEvents) { it.id })
        coreValues.upsertAll(missing(coreValues.getAll(), b.coreValues) { it.id })
        witnesses.upsertAll(missing(witnesses.getAll(), b.witnessEvents) { it.id })
        scorecard.upsertAll(missing(scorecard.getAll(), b.scorecardItems) { it.id })
        buddies.upsertAll(missing(buddies.getAll(), b.buddySnapshots) { it.id })
        integrityReviews.upsertAll(missing(integrityReviews.getAll(), b.integrityReviews) { it.id })
        experiments.upsertAll(missing(experiments.getAll(), b.experiments) { it.id })
        activation.upsertAll(missing(activation.getAll(), b.activationItems) { it.id })
        dayLogs.upsertAll(missing(dayLogs.getAll(), b.dayLogs) { it.epochDay to it.workspaceId })
        escrows.upsertAll(missing(escrows.getAll(), b.escrows) { it.id })
        nudgeEvents.upsertAll(missing(nudgeEvents.getAll(), b.nudgeEvents) { it.id })
        revisions.upsertAll(missing(revisions.getAll(), b.revisions) { it.id })
        eventCalendars.upsertAll(missing(eventCalendars.getAll(), b.eventCalendars) { it.id })
        events.upsertAll(missing(events.getAll(), b.events) { it.id })
        attachments.upsertAll(missing(attachments.getAll(), b.attachments) { it.id })
    }

    /** Full snapshot of the current data as a BackupFile (for sync merges). */
    suspend fun snapshot(): com.todocompanion.app.domain.port.BackupFile =
        com.todocompanion.app.domain.port.BackupFile(
            exportedAt = now(),
            workspaces = workspaces.getAll(), filters = filters.getAll(), habits = habits.getAll(),
            habitCheckins = habits.getCheckins(), focusSessions = focus.getAll(), folders = folders.getAll(),
            lists = lists.getAll(), tasks = tasks.getAll(), checklist = checklist.getAll(),
            tags = tags.getAll(), taskTags = tags.getCrossRefs(), contexts = contexts.getAll(),
            taskContexts = contexts.getCrossRefs(), reminders = reminders.getAll(), dependencies = deps.getAll(),
            // Attachment bytes stay local — syncing megabytes through the folder isn't worth it.
            settings = exportableSettings(), attachments = attachments.getAll().map { it.copy(contentBase64 = "") }, flags = flags.getAll(),
            templates = templates.getAll(), countdowns = countdowns.getAll(), activities = activity.getAll(),
        )

    /** Apply a merged snapshot to the local DB, preserving this device's own settings (sync/backup
     *  folder URIs, device id, theme). Used by the folder-sync engine. */
    suspend fun applyMerged(b: com.todocompanion.app.domain.port.BackupFile) {
        // Attachments are intentionally NOT synced (their bytes live locally in files), so they're
        // left untouched here — only structural + task data is reconciled.
        tasks.clear(); folders.clear(); lists.clear(); checklist.clear()
        tags.clear(); tags.clearCrossRefs(); contexts.clear(); contexts.clearCrossRefs()
        reminders.clear(); deps.clear(); workspaces.clear(); filters.clear()
        habits.clear(); habits.clearCheckins(); focus.clear(); flags.clear(); templates.clear(); countdowns.clear(); activity.clear()
        folders.upsertAll(b.folders); lists.upsertAll(b.lists); tasks.upsertAll(b.tasks); checklist.upsertAll(b.checklist)
        tags.upsertAll(b.tags); tags.linkAll(b.taskTags); contexts.upsertAll(b.contexts); contexts.linkAll(b.taskContexts)
        reminders.upsertAll(b.reminders); deps.addAll(b.dependencies)
        workspaces.upsertAll(b.workspaces); filters.upsertAll(b.filters)
        habits.upsertAll(b.habits); habits.upsertCheckins(b.habitCheckins); focus.upsertAll(b.focusSessions)
        flags.upsertAll(b.flags); templates.upsertAll(b.templates)
        countdowns.upsertAll(b.countdowns); activity.insertAll(b.activities)
        ensureDefaultWorkspace(); ensureInbox(); ensureDefaultFlags()
    }

    // ============ first-run seed ============
    suspend fun ensureSeed() {
        ensureDefaultWorkspace()
        ensureInbox()
        ensureDefaultFlags()
        if (tasks.getAll().isNotEmpty()) return
        val work = createFolder("Work")
        val personal = createFolder("Personal")
        val quarterly = createList("Quarterly Report", folderId = work, colorArgb = 0xFFE5484D)
        val admin = createList("Admin", folderId = work, colorArgb = 0xFFF59E0B)
        val home = createList("Home", folderId = personal, colorArgb = 0xFF3E7BFA)

        createTask(ListEntity.INBOX_ID, "Try quick-add: \"pay rent tomorrow 5pm !! #home\"")
        createTask(ListEntity.INBOX_ID, "Everything is offline, private, and free")
        val report = createTask(quarterly, "Draft summary for board deck", importance = 5, urgency = 4)
        createTask(quarterly, "Collect figures", parentId = report, importance = 4)
        createTask(quarterly, "Write exec overview", parentId = report)
        createTask(admin, "File expense receipts", importance = 3, urgency = 4)
        createTask(home, "Water the plants", importance = 2)
        createTask(home, "Book dentist", importance = 3, urgency = 4)
    }
}
